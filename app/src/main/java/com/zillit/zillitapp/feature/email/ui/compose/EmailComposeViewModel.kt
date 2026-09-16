package com.zillit.zillitapp.feature.email.ui.compose

import androidx.lifecycle.SavedStateHandle
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.session.CurrentUserStore
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.storage.S3Client
import com.zillit.zillitapp.core.storage.StorageCredentialsStore
import com.zillit.zillitapp.core.storage.TransferState
import com.zillit.zillitapp.core.storage.UploadRequest
import com.zillit.zillitapp.core.ui.components.AttachmentState
import com.zillit.zillitapp.feature.email.data.AttachmentDto
import com.zillit.zillitapp.feature.email.data.EmailApi
import com.zillit.zillitapp.feature.email.data.EmailMailboxContext
import com.zillit.zillitapp.feature.email.data.EmailRepository
import com.zillit.zillitapp.feature.email.data.toDto
import com.zillit.zillitapp.feature.email.data.withInlineImagesAsCids
import com.zillit.zillitapp.feature.email.data.prepareInlineImage
import com.zillit.zillitapp.feature.email.data.asHtml
import com.zillit.zillitapp.feature.email.data.InlineImage
import com.zillit.zillitapp.feature.email.data.EmailSettingsRepository
import com.zillit.zillitapp.feature.email.data.RecipientDto
import com.zillit.zillitapp.feature.email.data.SaveDraftRequest
import com.zillit.zillitapp.feature.email.data.SendEmailRequest
import com.zillit.zillitapp.feature.email.domain.EmailAddress
import com.zillit.zillitapp.feature.email.domain.RecipientSuggestion
import com.zillit.zillitapp.navigation.EmailCompose
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
import com.zillit.zillitapp.feature.email.domain.EmailFolders

/**
 * Writing a mail.
 *
 * Every way in is the same screen with different arguments — new, reply, reply-all, forward,
 * a share from elsewhere, or reopening a draft. What differs is the prefill, which is
 * settled once in [prefill] rather than by a mode check at each field.
 *
 * ### The send order matters
 * Attachments are uploaded to S3 **before** the send request goes out, because the request
 * carries only their object keys — sending first would mail somebody a link to a file that
 * is not there yet. v2 solves this with a persistent queue and a ten-second retry timer
 * running for the life of the process; here the screen simply waits for its own uploads,
 * and a draft is already saved server-side if anything goes wrong.
 *
 * ### Arguments, not a route
 * What to write arrives as [EmailComposeArgs] through an assisted factory rather than being
 * read off the navigation route, for the same reason the reader does it: the screen is
 * hosted two ways. On a phone it is a destination of its own; on a window wide enough for
 * two panes, tapping a draft opens it beside the list, where there is no route to read
 * from. One constructor serves both, and the destination builds its args from its route.
 */
@HiltViewModel(assistedFactory = EmailComposeViewModel.Factory::class)
class EmailComposeViewModel @AssistedInject constructor(
    @Assisted private val route: EmailComposeArgs,
    private val savedStateHandle: SavedStateHandle,
    private val repository: EmailRepository,
    private val settings: EmailSettingsRepository,
    private val api: EmailApi,
    private val mailbox: EmailMailboxContext,
    private val directory: ProjectDirectory,
    private val currentUser: CurrentUserStore,
    private val session: SessionStore,
    private val s3: S3Client,
    private val storage: StorageCredentialsStore,
    /**
     * Outlives this view model, for the one save that must not be cancelled.
     *
     * Closing the composer navigates away, which clears the view model and cancels
     * `viewModelScope` — so a final draft save started there races its own cancellation and
     * loses roughly as often as it wins. v2 solves this by calling the save inside
     * `runBlocking` from `onCleared`, which blocks the main thread on a network round trip.
     * An application-scoped coroutine finishes the write without either problem.
     */
    @com.zillit.zillitapp.core.di.ApplicationScope
    private val appScope: kotlinx.coroutines.CoroutineScope,
    private val queue: com.zillit.zillitapp.feature.email.data.EmailSendQueue,
) : ViewModel() {


    /**
     * Used when the mailbox has no signature of its own.
     *
     * Supplied by [start] rather than hardcoded, so the wording stays translatable — a view
     * model has no resources.
     */
    private var defaultSignature: String = ""

    private val _state = MutableStateFlow(ComposeUiState(mode = route.mode))
    val state: StateFlow<ComposeUiState> = _state.asStateFlow()

    /** Filled once, then handed to the editor — see [initialBody]. */
    private val _initialBody = MutableStateFlow<String?>(null)
    val initialBody: StateFlow<String?> = _initialBody.asStateFlow()

    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    /**
     * Creates attempted with the current key.
     *
     * Anything past the first may be answered with a **replay** — the record the original
     * attempt created, carrying whatever that attempt sent — so a successful retry is
     * followed by a PUT of the current content. Declared above [draftUniqueId], which
     * writes to it while initialising.
     */
    private var createAttempts = 0

    /** The draft this composer owns, once one exists. */
    private var draftId: String? = route.draftId

    /**
     * The idempotency key sent as `unique_id` on `POST email-draft`.
     *
     * Generated once per compose session and kept in [SavedStateHandle], so a session
     * restored after process death replays the same key and the server answers with the
     * draft the first accepted POST created rather than making a second one. It changes
     * only when [prefillFromDraft] reopens a draft that already carries a key of its own.
     */
    private var draftUniqueId: String =
        savedStateHandle.get<String>(KEY_DRAFT_UNIQUE_ID)
            // A key restored from a previous process belongs to a create that may already
            // have gone through unseen, so it counts as an attempt already made.
            ?.also { createAttempts = 1 }
            ?: newDraftUniqueId().also { savedStateHandle[KEY_DRAFT_UNIQUE_ID] = it }

    private var draftJob: Job? = null
    private var suggestionJob: Job? = null
    private var hasSent = false

    /** Uploaded attachment metadata, keyed by the staged id the UI knows. */
    private val uploaded = mutableMapOf<String, AttachmentDto>()

    /**
     * The editor's current HTML.
     *
     * Mirrored here because a draft save triggered by a *recipient* or *subject* change
     * still has to persist the body — and the body lives in the editor, which the view
     * model cannot read on demand.
     */
    private var latestBody: String = ""

    /**
     * Serialises draft saves.
     *
     * The **first** save is what creates the draft and learns its id, and until it returns
     * `draftId` is still null. Two saves overlapping in that window — an auto-save and a
     * close, which is the ordinary case — would each POST and leave the user with two
     * drafts of the same mail. Holding the lock means the second sees the id the first
     * learned and issues a PUT instead.
     */
    private val draftLock = kotlinx.coroutines.sync.Mutex()

    private var started = false

    /**
     * Begins the prefill.
     *
     * Called by the screen rather than from `init` because the prefill needs the default
     * signature, and that is a string resource the composable has to hand in — doing the
     * work in `init` would read it before it had been set, which is a race that resolves
     * differently depending on how fast the signature request returns.
     *
     * Guarded so a recomposition does not re-prefill over what the user has typed.
     */
    fun start(defaultSignature: String) {
        if (started) return
        started = true

        this.defaultSignature = defaultSignature
        viewModelScope.launch { prefill() }
    }

    // ── Recipients ───────────────────────────────────────────────────────────

    fun setRecipientInput(row: RecipientRow, text: String) {
        _state.value = when (row) {
            RecipientRow.TO -> _state.value.copy(toInput = text)
            RecipientRow.CC -> _state.value.copy(ccInput = text)
            RecipientRow.BCC -> _state.value.copy(bccInput = text)
        }
        scheduleSuggestions(text)
    }

    /**
     * Turns typed text into a chip, or leaves it alone.
     *
     * A rejected address stays in the field to be corrected. v2 accepts anything containing
     * `@`, so `a@` becomes a chip and the send fails at the server with a message nobody can
     * act on.
     */
    fun commitRecipient(row: RecipientRow) {
        val current = _state.value
        val typed = when (row) {
            RecipientRow.TO -> current.toInput
            RecipientRow.CC -> current.ccInput
            RecipientRow.BCC -> current.bccInput
        }

        val parsed = parseRecipient(typed) ?: return
        addRecipient(row, parsed)
    }

    fun pickSuggestion(row: RecipientRow, suggestion: RecipientSuggestion) {
        addRecipient(row, EmailAddress(suggestion.address, suggestion.name))
        _state.value = _state.value.copy(suggestions = emptyList())
    }

    fun removeRecipient(row: RecipientRow, address: EmailAddress) {
        _state.value = when (row) {
            RecipientRow.TO -> _state.value.copy(to = _state.value.to - address)
            RecipientRow.CC -> _state.value.copy(cc = _state.value.cc - address)
            RecipientRow.BCC -> _state.value.copy(bcc = _state.value.bcc - address)
        }
        scheduleDraftSave()
    }

    fun focusRow(row: RecipientRow?) {
        // Leaving a row commits whatever was typed in it, so a half-entered address is not
        // silently thrown away by tapping elsewhere.
        _state.value.focusedRow?.let { if (it != row) commitRecipient(it) }
        _state.value = _state.value.copy(focusedRow = row, suggestions = emptyList())
    }

    fun setSubject(subject: String) {
        _state.value = _state.value.copy(subject = subject)
        scheduleDraftSave()
    }

    // ── Attachments ──────────────────────────────────────────────────────────

    /**
     * Stages a file and starts uploading it.
     *
     * The 25 MB cap is cumulative and is the service's. Checked here rather than at send
     * time so the user finds out when they add the file, not after writing the mail.
     */
    fun addAttachment(localPath: String, fileName: String, mimeType: String) {
        val file = File(localPath)
        val size = file.length()

        if (_state.value.totalAttachmentBytes + size > MAX_ATTACHMENT_BYTES) {
            _state.value = _state.value.copy(error = ERROR_TOO_LARGE)
            return
        }

        val staged = StagedAttachment(
            id = UUID.randomUUID().toString(),
            name = fileName,
            sizeBytes = size,
            state = AttachmentState.BUSY,
        )
        _state.value = _state.value.copy(attachments = _state.value.attachments + staged)
        localPaths[staged.id] = localPath
        mimeTypes[staged.id] = mimeType

        viewModelScope.launch {
            upload(staged, localPath, mimeType)
            // Saved only once the upload has finished: a draft that referenced a key S3
            // does not have yet would come back with a broken attachment.
            scheduleDraftSave()
        }
    }

    /**
     * Puts an image in the message body.
     *
     * Not an attachment: it is uploaded the same way, but it is marked `inline` and carries
     * a content id, so the recipient sees it **in** the message rather than as a file below
     * it. The editor shows the bytes directly while it is being written and the reference is
     * swapped for `cid:` at send time.
     *
     * Returns the HTML to insert, or null when the file is not an image or could not be
     * prepared — which the screen reports rather than inserting a broken box.
     */
    suspend fun addInlineImage(
        localPath: String,
        fileName: String,
        mimeType: String,
    ): String? {
        if (!mimeType.startsWith("image/", ignoreCase = true)) return null

        val prepared = prepareInlineImage(localPath, fileName, mimeType) ?: return null
        val staged = StagedAttachment(
            id = UUID.randomUUID().toString(),
            name = fileName,
            sizeBytes = File(localPath).length(),
            state = AttachmentState.BUSY,
        )

        localPaths[staged.id] = localPath
        mimeTypes[staged.id] = prepared.mimeType
        inlineImages += staged.id to prepared

        // Uploaded but never added to `state.attachments`: an inline image belongs to the
        // body, and listing it as a file chip as well would have the user see the picture
        // they just placed offered a second time as an attachment to remove.
        upload(staged, localPath, prepared.mimeType, contentId = prepared.contentId)
        scheduleDraftSave()

        return prepared.asHtml()
    }

    /** Images placed in the body, by staged id. */
    private val inlineImages = mutableMapOf<String, InlineImage>()

    fun removeAttachment(attachment: StagedAttachment) {
        uploaded.remove(attachment.id)
        // The picker copied the file into the app's cache to upload it. Dropping only the
        // entry left that copy behind for every attachment the user ever changed their mind
        // about — a mailbox's worth of files nothing would ever delete.
        localPaths.remove(attachment.id)?.let { path ->
            runCatching { java.io.File(path).delete() }
        }
        _state.value = _state.value.copy(
            attachments = _state.value.attachments - attachment,
        )
        scheduleDraftSave()
    }

    /**
     * Uploads a failed attachment again.
     *
     * Without it the only way past a dropped connection was to remove the file and pick it
     * again, which on a shared photo means finding it in the gallery a second time.
     */
    fun retryAttachment(attachment: StagedAttachment) {
        val path = localPaths[attachment.id] ?: return
        val mimeType = mimeTypes[attachment.id].orEmpty()

        _state.value = _state.value.copy(
            attachments = _state.value.attachments.map {
                if (it.id == attachment.id) it.copy(state = AttachmentState.BUSY) else it
            },
        )
        viewModelScope.launch { upload(attachment, path, mimeType) }
    }

    /** Where each staged file was copied to, so it can be retried and then cleaned up. */
    private val localPaths = mutableMapOf<String, String>()
    private val mimeTypes = mutableMapOf<String, String>()

    private suspend fun upload(
        staged: StagedAttachment,
        localPath: String,
        mimeType: String,
        contentId: String? = null,
    ) {
        val project = session.activeProject.value?.projectId.orEmpty()
        val userId = session.activeProject.value?.userId.orEmpty()
        val credentials = storage.credentials.value

        // v2's key layout, kept: the backend's lifecycle rules and the web client's
        // download paths both assume it.
        val remoteKey = "$project/email/file/$userId/Zillit_${System.currentTimeMillis()}_${staged.name}"

        val terminal = s3.upload(
            UploadRequest(
                remoteKey = remoteKey,
                localPath = localPath,
                fileName = staged.name,
                module = MODULE,
                contentType = mimeType,
            ),
        ).first { it is TransferState.Complete || it is TransferState.Failed }

        if (terminal is TransferState.Complete) {
            uploaded[staged.id] = AttachmentDto(
                id = staged.id,
                name = staged.name,
                media = terminal.remoteKey,
                bucket = credentials.uploadBucket,
                region = credentials.region,
                contentType = mimeType,
                contentId = contentId.orEmpty(),
                // The disposition is what tells the receiving client to render the part in
                // the body instead of listing it as a file to download.
                contentDisposition = if (contentId != null) INLINE_DISPOSITION else "",
                size = staged.sizeBytes,
                contentLength = staged.sizeBytes,
            )
            updateAttachment(staged.id, AttachmentState.READY)
        } else {
            // Shown as failed rather than as ready. v2 renders a failed upload identically
            // to a successful one, so a mail sends with an attachment nobody receives.
            updateAttachment(staged.id, AttachmentState.FAILED)
        }
    }

    private fun updateAttachment(id: String, state: AttachmentState) {
        _state.value = _state.value.copy(
            attachments = _state.value.attachments.map {
                if (it.id == id) it.copy(state = state) else it
            },
        )
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    /**
     * Sends, once everything it references actually exists.
     *
     * @param body the editor's current HTML, read at the moment of sending rather than
     *   tracked continuously — the signature and any quoted history live inside the editable
     *   area and the user may have edited both.
     */
    fun send(body: String) {
        val current = _state.value
        if (current.to.isEmpty()) {
            _state.value = current.copy(error = ERROR_NO_RECIPIENT)
            return
        }

        viewModelScope.launch {
            draftJob?.cancel()
            _state.value = _state.value.copy(sending = true, error = null)

            // A failed upload must not be sent: the request carries only object keys, so
            // the recipient would get a link to a file that is not there. v2 renders a
            // failed upload identically to a successful one and sends anyway.
            if (_state.value.attachments.any { it.state == AttachmentState.FAILED }) {
                _state.value = _state.value.copy(sending = false, error = ERROR_ATTACHMENT_FAILED)
                return@launch
            }

            // Nor an upload still in flight. The send button is disabled while one is, but
            // the no-subject dialog can return here after it started.
            if (_state.value.attachments.any { it.state == AttachmentState.BUSY }) {
                _state.value = _state.value.copy(sending = false, error = ERROR_ATTACHMENT_BUSY)
                return@launch
            }

            // Images placed in the body go out as `ingrained_attachment` and are referenced
            // from the HTML by `cid:`. Leaving the base64 in the body instead would send a
            // multi-megabyte message that most servers refuse outright.
            val inlineParts = inlineImages.keys.mapNotNull { uploaded[it] }
            val sentBody = body.withInlineImagesAsCids(inlineImages.values.toList())

            val request = SendEmailRequest(
                to = current.to.map { RecipientDto(it.address) },
                cc = current.cc.map { RecipientDto(it.address) },
                bcc = current.bcc.map { RecipientDto(it.address) },
                from = mailbox.fromHeader,
                subject = current.subject,
                body = sentBody,
                attachments = _state.value.attachments.mapNotNull { uploaded[it.id] },
                inlineAttachments = inlineParts,
                // Sending a draft tells the server to delete it, so it does not linger.
                draftId = draftId.orEmpty(),
                references = references,
            )

            // Handed to the outbox rather than sent from here. The screen closes on the
            // tap, and delivery outlives it: backgrounding the app or losing signal
            // mid-request used to lose the whole message with nothing to show for it.
            //
            // The mailbox is frozen now, not read at delivery — switching to the shared
            // mailbox while this is still queued must not send it from the shared one.
            hasSent = true
            // The compose session is over: drop the local copy. The server's draft is
            // removed by the send itself, through `email_draft_id`.
            val key = draftUniqueId
            appScope.launch { runCatching { repository.deleteLocalDraft(key) } }
            queue.enqueue(
                request = request,
                scope = mailbox.effectiveScope,
                subject = current.subject,
                recipients = current.to.joinToString(", ") { it.display },
            )
            _closed.value = true
        }
    }

    /**
     * Closes, saving whatever was written.
     *
     * There is no discard: closing always saves, which is v2's behaviour and what the
     * absence of a discard action promises.
     */
    fun close(body: String) {
        latestBody = body

        // The pending auto-save is cancelled first so the two cannot both create a draft.
        draftJob?.cancel()

        // Closing always saves — there is no discard, which is v2's behaviour and what the
        // absence of a discard action promises. Run on the application scope so navigating
        // away does not cancel the write half-done.
        if (!hasSent) {
            appScope.launch { saveDraft(body) }
        }

        _closed.value = true
    }

    fun onBodyChanged(body: String) {
        latestBody = body
        scheduleDraftSave()
    }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }

    @dagger.assisted.AssistedFactory
    interface Factory {
        fun create(args: EmailComposeArgs): EmailComposeViewModel
    }

    /**
     * Last resort for a composer that goes away without [close] — the process being killed
     * behind it, or the screen destroyed under the user.
     *
     * On the application scope for the same reason as [close], and guarded by [hasSent] so
     * a mail just sent is not also left behind as a draft. [saveDraft] takes the lock, so
     * this cannot duplicate a save already in flight.
     */
    override fun onCleared() {
        if (!hasSent) {
            appScope.launch { saveDraft(latestBody) }
        }
        super.onCleared()
    }

    // ── Drafts ───────────────────────────────────────────────────────────────

    /**
     * Queues an auto-save.
     *
     * Every edit schedules one — recipients, subject, body and attachments alike. The
     * previous version took the body as an argument and silently **returned** when it was
     * absent, which every caller but the body itself was, so adding a recipient or a
     * subject never saved anything.
     */
    private fun scheduleDraftSave() {
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            saveDraft(latestBody)
        }
    }

    /**
     * One save at a time, in the order v2 established:
     *
     *  1. the local copy — key **and** content — is written *before* the request, so a
     *     lost response or a killed process cannot lose either;
     *  2. no draft id yet → `POST` carrying this session's `unique_id`. A retry can be
     *     answered with a replay of an *earlier* payload, so a create that was not the
     *     first is followed by a `PUT` of what is on screen now;
     *  3. the local copy is written again with the id, so the row stops being pending.
     */
    private suspend fun saveDraft(body: String) = draftLock.withLock {
        // A mail already handed to the outbox must not be left behind as a draft as well.
        if (hasSent) return@withLock

        val current = _state.value
        // Nothing worth keeping — and note Cc/Bcc count, which the previous check ignored,
        // so a mail addressed only in Bcc was silently never drafted.
        if (!current.hasContent(body)) return@withLock

        val key = draftUniqueId
        val request = SaveDraftRequest(
            to = current.to.map { RecipientDto(it.address) },
            cc = current.cc.map { RecipientDto(it.address) },
            bcc = current.bcc.map { RecipientDto(it.address) },
            subject = current.subject,
            body = body,
            attachments = current.attachments.mapNotNull { uploaded[it.id] },
            inlineAttachments = inlineImages.keys.mapNotNull { uploaded[it] },
            uniqueId = key,
        )

        // Before the network call, so the draft exists locally even if nothing else works.
        runCatching { repository.saveLocalDraft(key, draftId, request) }

        val existing = draftId
        val result = if (existing == null) {
            createAttempts++
            repository.saveDraft(request).also { outcome ->
                if (outcome is ApiResult.Success) draftId = outcome.data
            }
        } else {
            // `unique_id` identifies a create; a PUT already names the record. v2 omits it.
            repository.updateDraft(existing, request.copy(uniqueId = null))
        }

        // An auto-save that fails is not worth interrupting anyone for; the local copy has
        // the content and the next keystroke schedules another attempt under the same key.
        if (result is ApiResult.Failure) {
            ZillitLog.w(TAG, "draft save failed: ${result.error.message}")
            return@withLock
        }

        val id = draftId
        // Only the FIRST accepted create is guaranteed to hold this content — a later one
        // may be the server replaying the original. Put the current content over it.
        if (existing == null && id != null && createAttempts > 1) {
            repository.updateDraft(id, request.copy(uniqueId = null))
        }

        runCatching { repository.saveLocalDraft(key, id, request) }
    }

    /** True once anything worth keeping has been entered. */
    private fun ComposeUiState.hasContent(body: String): Boolean =
        to.isNotEmpty() || cc.isNotEmpty() || bcc.isNotEmpty() ||
            subject.isNotBlank() || attachments.isNotEmpty() || !body.isBlankHtml()

    // ── Prefill ──────────────────────────────────────────────────────────────

    private var references: String = ""

    /**
     * The BCC addresses this mailbox copies on everything it sends.
     *
     * Applied to every new mail, reply and forward, which is what a preset means — v2 does
     * it in `init` for the same reason. The shared mailbox has its own set, kept apart from
     * the user's: a preset that copies your personal archive has no business applying to
     * mail the whole Accounts department sends.
     */
    private fun applyBccPresets() {
        val presets = if (mailbox.useAccountsParam) {
            mailbox.accountsBccPresets.value
        } else {
            currentUser.current?.bccPresets.orEmpty()
        }

        val addresses = presets
            .filter { it.isNotBlank() }
            .map(EmailAddress::parse)
            .distinctBy { it.address.lowercase() }

        if (addresses.isEmpty()) return

        _state.value = _state.value.copy(
            bcc = (_state.value.bcc + addresses).distinctBy { it.address.lowercase() },
        )
    }

    private suspend fun prefill() {
        // Before the mode-specific work, so a draft's own saved Bcc list wins over the
        // presets rather than the presets being added twice.
        if (route.mode != EmailCompose.MODE_EDIT_DRAFT) applyBccPresets()

        when (route.mode) {
            EmailCompose.MODE_REPLY, EmailCompose.MODE_REPLY_ALL, EmailCompose.MODE_FORWARD ->
                prefillFromSource()

            EmailCompose.MODE_EDIT_DRAFT -> prefillFromDraft()

            // Share and a plain new mail prefill the same way. Share additionally needs the
            // files the sharing screen was holding and the *sharing* project's identity on
            // the request — neither of which exists yet, because nothing in the app can
            // share into mail. Left as the shared branch rather than a stub, so it is
            // correct for the fields that do arrive.
            else -> {
                _state.value = _state.value.copy(
                    to = listOfNotNull(route.prefillTo?.let(EmailAddress::parse)),
                    subject = route.prefillSubject.orEmpty(),
                )
                _initialBody.value = signatureBlock(route.prefillBody.orEmpty())
            }
        }
    }

    private suspend fun prefillFromSource() {
        val folder = route.sourceFolderName
        val sourceId = route.sourceEmailId

        val source = if (folder != null && sourceId != null) {
            (repository.fetchTrail(folder, listOf(sourceId)) as? ApiResult.Success)
                ?.data?.firstOrNull()
        } else {
            null
        }

        // The message being replied to could not be fetched. The composer still opens, and
        // still opens with a signature — returning here would leave an empty body with no
        // sign of why.
        if (source == null) {
            _initialBody.value = signatureBlock("")
            return
        }

        val me = setOfNotNull(
            mailbox.activeAddress?.lowercase(),
            currentUser.current?.email?.lowercase(),
        )

        val isForward = route.mode == EmailCompose.MODE_FORWARD

        // Forwarding a mail forwards its files. They are already on the server, so they are
        // registered as uploaded rather than re-staged and re-sent — nothing is downloaded
        // and nothing is uploaded again. Inline images are excluded: they belong to the
        // quoted body, and listing them as attachments would show the sender's signature
        // logo as a file the user had chosen to attach.
        val carriedOver = if (isForward) {
            source.attachments.filterNot { it.isInline }
        } else {
            emptyList()
        }

        carriedOver.forEach { attachment ->
            uploaded[attachment.id] = AttachmentDto(
                id = attachment.id,
                attachmentId = attachment.attachmentId,
                name = attachment.name,
                media = attachment.media,
                bucket = attachment.bucket,
                region = attachment.region,
                contentType = attachment.contentType,
                contentId = attachment.contentId,
                size = attachment.size,
                contentLength = attachment.size,
                contentDisposition = attachment.contentDisposition,
            )
        }

        // Replying to something you sent goes to the people you sent it to, not to
        // yourself. Without this, replying from Sent addressed the mail back to the user.
        val fromSelf = source.folderName.equals(EmailFolders.SENT, ignoreCase = true) ||
            source.from.address.lowercase() in me

        val replyTo = if (fromSelf) {
            source.to.ifEmpty { listOf(source.from) }
        } else {
            listOf(source.from)
        }

        _state.value = _state.value.copy(
            // A forward starts with nobody in To — the whole point is choosing who.
            to = if (isForward) emptyList() else replyTo,
            cc = if (route.mode == EmailCompose.MODE_REPLY_ALL) {
                // Replying-all to your own mail keeps the original Cc; replying-all to
                // somebody else's adds everyone it was addressed to, minus you.
                val others = if (fromSelf) source.cc else source.to + source.cc
                others
                    .filterNot { it.address.lowercase() in me }
                    .filterNot { address -> replyTo.any { it.address.equals(address.address, true) } }
                    .distinctBy { it.address.lowercase() }
            } else {
                emptyList()
            },
            subject = source.subject.withPrefix(if (isForward) FORWARD_PREFIX else REPLY_PREFIX),
            attachments = carriedOver.map { attachment ->
                StagedAttachment(
                    id = attachment.id,
                    name = attachment.name,
                    sizeBytes = attachment.size,
                    state = AttachmentState.READY,
                )
            },
        )

        // Threads the reply. Space-separated on the wire, unlike everywhere else.
        references = (source.references + source.id).joinToString(" ")

        _initialBody.value = signatureBlock("") + quoted(source, isForward)
    }

    private suspend fun prefillFromDraft() {
        val id = route.draftId

        // The local copy first: it carries the key the draft was created with — reused, so
        // a replay lands on the same record — and for a draft whose create never got a
        // response it is the only copy there is.
        val local = id?.let { repository.localDraft(it) }
        val unsynced = local != null && local.pending

        val draft = when {
            unsynced -> local
            id != null ->
                (repository.drafts() as? ApiResult.Success)?.data?.firstOrNull {
                    it.id == id || it.draftUniqueId == id
                } ?: local

            else -> null
        }

        // An unsynced draft has no server id; anything else edits the record it names.
        draftId = draft?.id?.takeUnless { unsynced || it.isBlank() }

        draft?.draftUniqueId?.takeIf { it.isNotBlank() }?.let { key ->
            draftUniqueId = key
            savedStateHandle[KEY_DRAFT_UNIQUE_ID] = key
            // Its create may already have gone through unseen, so the next accepted POST
            // is treated as a possible replay and followed by a PUT.
            if (unsynced) createAttempts = 1
        }

        // A draft that cannot be found opens as a new mail rather than as a blank screen.
        // Its own signature is already inside its saved body, which is why the found case
        // does not add one.
        if (draft == null) {
            _initialBody.value = signatureBlock("")
            return
        }

        // The attachments come back already uploaded, so they are re-registered as such
        // rather than re-staged: without this a reopened draft showed no attachments and
        // the next auto-save dropped them from the server copy as well.
        // The mapper merges a draft's inline parts into `attachments`, so they have to be
        // told apart again here. Without this the signature logo and every embedded
        // screenshot came back as a file chip the user had apparently attached — and the
        // next auto-save wrote them into `attachments` for real, breaking the `cid:`
        // references in the body.
        val (inline, files) = draft.attachments.partition { attachment ->
            attachment.isInline || draft.body.contains("cid:${attachment.contentId}")
        }

        inline.forEach { attachment ->
            uploaded[attachment.id] = attachment.toDto()
            inlineImages[attachment.id] = InlineImage(
                contentId = attachment.contentId,
                // Already on the server, so the body references it by `cid:` and there is
                // no preview URI to swap out at send time.
                previewUri = "cid:${attachment.contentId}",
                localPath = "",
                fileName = attachment.name,
                mimeType = attachment.contentType,
            )
        }

        files.forEach { attachment ->
            uploaded[attachment.id] = AttachmentDto(
                id = attachment.id,
                attachmentId = attachment.attachmentId,
                name = attachment.name,
                media = attachment.media,
                bucket = attachment.bucket,
                region = attachment.region,
                contentType = attachment.contentType,
                contentId = attachment.contentId,
                size = attachment.size,
                contentLength = attachment.size,
                contentDisposition = attachment.contentDisposition,
            )
        }

        _state.value = _state.value.copy(
            to = draft.to,
            cc = draft.cc,
            bcc = draft.bcc,
            subject = draft.subject,
            attachments = files.map { attachment ->
                StagedAttachment(
                    id = attachment.id,
                    name = attachment.name,
                    sizeBytes = attachment.size,
                    state = AttachmentState.READY,
                )
            },
        )

        latestBody = draft.body
        _initialBody.value = draft.body
    }

    /**
     * The signature, above any quoted history.
     *
     * Injected into the **editable** body rather than appended at send time, so the user can
     * edit or delete it before sending — which is v2's behaviour and what people expect of a
     * signature.
     *
     * Which signature depends on what is being written: a reply and a forward honour
     * `use_for_reply_and_forward`, a new mail honours `use_for_new_email`. Falling back to
     * the only signature there is matters because the server allows at most one, so the
     * flags are usually both set on the same record anyway.
     *
     * Fetched **once** — the previous version asked the server twice, once per branch.
     */
    private suspend fun signatureBlock(existing: String): String {
        val all = (settings.signatures() as? ApiResult.Success)?.data.orEmpty()

        val isReplyOrForward = route.mode == EmailCompose.MODE_REPLY ||
            route.mode == EmailCompose.MODE_REPLY_ALL ||
            route.mode == EmailCompose.MODE_FORWARD

        val chosen = all.firstOrNull { if (isReplyOrForward) it.useForReply else it.useForNew }
            ?: all.firstOrNull()

        // No signature configured falls back to the default, rather than to nothing — the
        // body should never open blank when a signature was expected. v2 does the same.
        val block = chosen?.content?.takeIf { it.isNotBlank() } ?: defaultSignature

        return "$existing<br><br><div class=\"email-signature\">$block</div>"
    }

    private fun quoted(source: com.zillit.zillitapp.feature.email.domain.Email, forward: Boolean) =
        if (forward) {
            buildString {
                append("<br><br>$FORWARD_HEADER<br>")
                append("From: ${source.from.encoded}<br>")
                append("Date: ${com.zillit.zillitapp.feature.email.ui.EmailDates.quoteAttribution(source.createdAt)}<br>")
                append("Subject: ${source.subject}<br>")
                append("To: ${source.to.joinToString(", ") { it.encoded }}<br>")
                if (source.cc.isNotEmpty()) {
                    append("Cc: ${source.cc.joinToString(", ") { it.encoded }}<br>")
                }
                append("<br>${source.body.ifBlank { source.text }}")
            }
        } else {
            val stamp = com.zillit.zillitapp.feature.email.ui.EmailDates.quoteAttribution(source.createdAt)
            "<br><br>On $stamp, ${source.from.display} wrote:<blockquote>" +
                "${source.body.ifBlank { source.text }}</blockquote>"
        }

    // ── Suggestions ──────────────────────────────────────────────────────────

    /**
     * Who the user might mean.
     *
     * Project users first, then saved contacts, then groups — and de-duplicated by address,
     * so a colleague who is also a saved contact appears once rather than twice.
     */
    private fun scheduleSuggestions(query: String) {
        suggestionJob?.cancel()
        if (query.length < MIN_SUGGESTION_CHARS) {
            _state.value = _state.value.copy(suggestions = emptyList())
            return
        }

        suggestionJob = viewModelScope.launch {
            delay(SUGGESTION_DEBOUNCE_MS)

            val term = query.trim().lowercase()
            val projectId = session.activeProject.value?.projectId.orEmpty()

            val users = directory.observeUsers(projectId).first()
                .filter { it.enabled && !it.email.isNullOrBlank() }
                .filter {
                    it.fullName.lowercase().contains(term) ||
                        it.email.orEmpty().lowercase().contains(term)
                }
                .map {
                    RecipientSuggestion(
                        address = it.email.orEmpty(),
                        name = it.fullName,
                        source = RecipientSuggestion.Source.PROJECT_USER,
                        pictureKey = it.profilePictureUrl,
                        thumbnailKey = it.profileThumbnailKey,
                    )
                }

            val contacts = (settings.contacts() as? ApiResult.Success)?.data.orEmpty()
                .filter {
                    it.displayName.lowercase().contains(term) ||
                        it.emailAddress.lowercase().contains(term)
                }
                .map {
                    RecipientSuggestion(
                        address = it.emailAddress,
                        name = it.displayName,
                        source = RecipientSuggestion.Source.CONTACT,
                    )
                }

            val groups = (settings.groups() as? ApiResult.Success)?.data.orEmpty()
                .filter { it.groupName.lowercase().contains(term) && it.groupEmail.isNotBlank() }
                .map {
                    RecipientSuggestion(
                        address = it.groupEmail,
                        name = it.groupName,
                        source = RecipientSuggestion.Source.GROUP,
                    )
                }

            _state.value = _state.value.copy(
                suggestions = (users + contacts + groups)
                    .distinctBy { it.address.lowercase() }
                    .take(MAX_SUGGESTIONS),
            )
        }
    }

    private fun addRecipient(row: RecipientRow, address: EmailAddress) {
        val current = _state.value
        val exists = (current.to + current.cc + current.bcc)
            .any { it.address.equals(address.address, ignoreCase = true) }

        _state.value = when {
            exists -> current.clearInput(row)

            row == RecipientRow.TO -> current.copy(to = current.to + address).clearInput(row)
            row == RecipientRow.CC -> current.copy(cc = current.cc + address).clearInput(row)
            else -> current.copy(bcc = current.bcc + address).clearInput(row)
        }
        scheduleDraftSave()
    }

    private fun ComposeUiState.clearInput(row: RecipientRow) = when (row) {
        RecipientRow.TO -> copy(toInput = "", suggestions = emptyList())
        RecipientRow.CC -> copy(ccInput = "", suggestions = emptyList())
        RecipientRow.BCC -> copy(bccInput = "", suggestions = emptyList())
    }

    /** Adds `Re: ` or `Fwd: ` only when it is not already there. */
    private fun String.withPrefix(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) this else "$prefix$this"

    private companion object {
        const val TAG = "EmailComposeViewModel"
        const val MODULE = "email"

        /** The service's cumulative cap. */
        const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

        /** What marks a part as belonging in the body rather than below it. */
        const val INLINE_DISPOSITION = "inline"

        const val DRAFT_DEBOUNCE_MS = 1_000L
        const val SUGGESTION_DEBOUNCE_MS = 250L
        const val MIN_SUGGESTION_CHARS = 1
        const val MAX_SUGGESTIONS = 12

        const val REPLY_PREFIX = "Re: "
        const val FORWARD_PREFIX = "Fwd: "
        const val FORWARD_HEADER = "---------- Forwarded message ----------"

        const val ERROR_NO_RECIPIENT = "email_recipient_required"
        const val ERROR_TOO_LARGE = "email_attachment_limit"
        const val ERROR_ATTACHMENT_FAILED = "email_attachment_failed"
        const val ERROR_ATTACHMENT_BUSY = "email_attachment_in_progress"
    }
}

/**
 * Whether an HTML body carries nothing a person wrote.
 *
 * A composer that has only had a signature injected is still empty as far as the user is
 * concerned, so the signature block is dropped **before** the markup is stripped — stripping
 * alone leaves the signature's own words ("Sent from Android") behind, which read as content
 * and auto-saved a subject-less draft the server rejects (`email_subject_validation`) on
 * every open of the composer.
 */
private fun String.isBlankHtml(): Boolean = replace(SIGNATURE_BLOCK, "")
    .replace(Regex("<[^>]*>"), "")
    .replace("&nbsp;", " ")
    .isBlank()

/** The signature as [signatureBlock] injects it, wherever it sits in the body. */
private val SIGNATURE_BLOCK = Regex(
    "<div class=\"email-signature\">.*?</div>",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)


/**
 * SavedStateHandle key for the compose session's draft `unique_id`.
 *
 * A file-level constant rather than a companion, matching how the rest of this file keeps
 * its constants.
 */
private const val KEY_DRAFT_UNIQUE_ID = "draft_unique_id"

/** A fresh idempotency key. Opaque to the server, which only ever compares it. */
private fun newDraftUniqueId(): String = java.util.UUID.randomUUID().toString()

/**
 * What the composer opens with.
 *
 * Mirrors [EmailCompose] field for field, so the destination hands its route straight over
 * and a pane can build one from a list row with no route in sight.
 */
data class EmailComposeArgs(
    val mode: String = EmailCompose.MODE_NEW,
    val sourceEmailId: String? = null,
    val sourceFolderName: String? = null,
    val draftId: String? = null,
    val prefillTo: String? = null,
    val prefillSubject: String? = null,
    val prefillBody: String? = null,
) {
    companion object {
        fun from(route: EmailCompose): EmailComposeArgs = EmailComposeArgs(
            mode = route.mode,
            sourceEmailId = route.sourceEmailId,
            sourceFolderName = route.sourceFolderName,
            draftId = route.draftId,
            prefillTo = route.prefillTo,
            prefillSubject = route.prefillSubject,
            prefillBody = route.prefillBody,
        )

        /** Reopening a draft — the only way a pane ever opens this screen. */
        fun draft(draftId: String): EmailComposeArgs =
            EmailComposeArgs(mode = EmailCompose.MODE_EDIT_DRAFT, draftId = draftId)
    }
}
