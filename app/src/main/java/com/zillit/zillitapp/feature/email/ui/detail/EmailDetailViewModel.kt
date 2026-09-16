package com.zillit.zillitapp.feature.email.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.session.CurrentUserStore
import com.zillit.zillitapp.feature.email.data.EmailMailboxContext
import com.zillit.zillitapp.feature.email.data.EmailRealtime
import com.zillit.zillitapp.feature.email.data.EmailRepository
import com.zillit.zillitapp.feature.email.data.toDomain
import com.zillit.zillitapp.feature.email.domain.Email
import com.zillit.zillitapp.feature.email.domain.EmailAddress
import com.zillit.zillitapp.feature.email.domain.EmailFolders
import com.zillit.zillitapp.navigation.EmailDetail
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import com.zillit.zillitapp.feature.email.domain.EmailAttachment
import com.zillit.zillitapp.feature.email.ui.EmailDates
import com.zillit.zillitapp.core.ui.html.PrintableHtml

/**
 * One message, or one conversation.
 *
 * Opening a message is what **downloads its body** — the folder sync only ever fetches
 * headers — and it is also what marks it read, which in this module means retiring its badge
 * row rather than setting a flag.
 *
 * ### Arguments, not a route
 * What to show arrives as [EmailDetailArgs] through an assisted factory rather than being
 * read off the navigation route. The same screen is hosted two ways: as a destination of
 * its own on a phone, and as the right-hand pane beside the list on a wide window, where
 * there is no route to read from — the list simply picks a mail. One constructor serves
 * both; the destination builds its args from the route.
 */
@HiltViewModel(assistedFactory = EmailDetailViewModel.Factory::class)
class EmailDetailViewModel @AssistedInject constructor(
    @Assisted private val route: EmailDetailArgs,
    private val repository: EmailRepository,
    private val mailbox: EmailMailboxContext,
    private val realtime: EmailRealtime,
    private val directory: ProjectDirectory,
    private val currentUser: CurrentUserStore,
    private val cache: com.zillit.zillitapp.core.storage.MediaCache,
    private val settings: com.zillit.zillitapp.feature.email.data.EmailSettingsRepository,
) : ViewModel() {

    /** A failed action — a move, a delete, a download. Shown and then forgotten. */
    private val _actionError = MutableStateFlow<ApiError?>(null)
    val actionError: StateFlow<ApiError?> = _actionError.asStateFlow()

    private val _downloadFailed = MutableStateFlow(false)
    val downloadFailed: StateFlow<Boolean> = _downloadFailed.asStateFlow()

    private val _state = MutableStateFlow(
        EmailDetailUiState(folderName = route.folderName, loading = true),
    )
    val state: StateFlow<EmailDetailUiState> = _state.asStateFlow()

    @AssistedFactory
    interface Factory {
        fun create(args: EmailDetailArgs): EmailDetailViewModel
    }

    /**
     * Documents ready to print.
     *
     * An event for the same reason [openFile] is: held in state it would queue a print job
     * on every recomposition.
     */
    private val _printRequests = MutableSharedFlow<PrintableHtml>(extraBufferCapacity = 2)
    val printRequests: SharedFlow<PrintableHtml> = _printRequests.asSharedFlow()

    /**
     * Files ready to open.
     *
     * A flow rather than state because opening one is an **event**: holding it in state
     * would re-open the same file on every recomposition and after a rotation.
     */
    private val _openFile = MutableSharedFlow<java.io.File>(extraBufferCapacity = 4)
    val openFile: SharedFlow<java.io.File> = _openFile.asSharedFlow()

    /** Which attachments are being fetched, so their rows can show a spinner. */
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    /**
     * Downloads an attachment and hands back the file.
     *
     * The whole file arrives base64 inside the JSON — there is no streaming and no
     * presigned URL — so this is only ever done on an explicit tap.
     *
     * Written into the shared media cache, keyed by the attachment id rather than by its
     * **file name**: v2 keys by name alone, so two messages with a `scan.pdf` overwrite
     * each other and the user opens the wrong one.
     */
    fun downloadAttachment(email: Email, attachment: EmailAttachment) {
        val key = attachment.attachmentId.ifBlank { attachment.media }
        if (key in _downloading.value) return

        viewModelScope.launch {
            _downloading.value = _downloading.value + key

            val file = cache.fileFor(remoteKey = key, fileName = attachment.name)

            if (file.exists() && file.length() > 0) {
                _openFile.emit(file)
                _downloading.value = _downloading.value - key
                return@launch
            }

            val result = repository.attachmentBase64(
                folderName = email.folderName,
                messageId = email.id,
                attachmentId = attachment.attachmentId,
            )

            when (result) {
                is ApiResult.Success -> {
                    val written = runCatching {
                        file.parentFile?.mkdirs()
                        file.writeBytes(
                            android.util.Base64.decode(result.data, android.util.Base64.DEFAULT),
                        )
                        file
                    }.getOrNull()

                    if (written != null) {
                        _openFile.emit(written)
                    } else {
                        _downloadFailed.value = true
                    }
                }

                is ApiResult.Failure -> _actionError.value = result.error
            }

            _downloading.value = _downloading.value - key
        }
    }

    // Declared ABOVE init on purpose. Property initializers run in declaration order, and
    // load() starts its coroutine synchronously (viewModelScope is Main.immediate), so a
    // declaration below init is still null when load() reads it — an NPE the moment the
    // detail pane creates this view model during composition.

    /** The project's distribution-list addresses, lower-cased. Empty until they load. */
    private var groupAddresses: Set<String> = emptySet()

    /** The user's saved contact addresses, lower-cased. Empty until they load. */
    private var savedContacts: Set<String> = emptySet()

    init {
        load()

        // A message deleted from the conversation elsewhere has to leave this screen too.
        realtime.trailMessageDeleted
            .onEach { messageId ->
                if (_state.value.emails.any { it.id == messageId }) load()
            }
            .launchIn(viewModelScope)
    }

    fun load() {
        viewModelScope.launch {
            // Loaded once per screen and cached on the view model: Reply All has to know
            // whether any address on the mail is a distribution list.
            if (groupAddresses.isEmpty()) {
                groupAddresses = (settings.groups() as? ApiResult.Success)?.data
                    .orEmpty()
                    .map { it.groupEmail.lowercase() }
                    .filter { it.isNotBlank() }
                    .toSet()
            }
            if (savedContacts.isEmpty()) {
                savedContacts = (settings.contacts() as? ApiResult.Success)?.data
                    .orEmpty()
                    .map { it.emailAddress.lowercase() }
                    .filter { it.isNotBlank() }
                    .toSet()
            }

            _state.value = _state.value.copy(loading = true, error = null)

            // A conversation shows every message in the thread; a single mail shows one.
            // Both come from the cache first, so the screen has something to draw while the
            // bodies are fetched.
            val messageIds = if (route.threadId != null) {
                repository.threadMessages(route.threadId, route.folderName).map { it.messageId }
            } else {
                listOf(route.emailId)
            }

            // Whatever has already been downloaded goes up first. Without this a message
            // read five minutes ago could not be reopened on a train, because the trail was
            // fetched every time and there was no fallback when the fetch failed.
            val cached = repository.cachedTrail(route.folderName, messageIds)
            if (cached.isNotEmpty()) {
                val ordered = cached.sortedBy { it.createdAt }
                _state.value = _state.value.copy(
                    emails = ordered,
                    loading = false,
                    expandedIds = setOfNotNull(ordered.lastOrNull()?.id),
                    replyAction = defaultReplyAction(ordered.lastOrNull()),
                    canReplyAll = ordered.lastOrNull()?.let(::canReplyAll) ?: false,
                )
            }

            when (val result = repository.fetchTrail(route.folderName, messageIds)) {
                is ApiResult.Success -> {
                    val ordered = result.data.sortedBy { it.createdAt }
                    _state.value = _state.value.copy(
                        emails = ordered,
                        loading = false,
                        // Only the newest opens. v2 expands every message, so a ten-message
                        // thread renders ten full bodies and repeats the first one ten times.
                        expandedIds = setOfNotNull(ordered.lastOrNull()?.id),
                        replyAction = defaultReplyAction(ordered.lastOrNull()),
                        canReplyAll = ordered.lastOrNull()?.let(::canReplyAll) ?: false,
                    )
                    ordered.forEach { repository.markRead(it) }

                    // Inline images are fetched after the text is on screen: they are the
                    // slow part of a mail, and holding the body back for a signature logo
                    // would make every message feel slow.
                    val images = ordered.fold(emptyMap<String, String>()) { acc, email ->
                        acc + repository.inlineImages(email)
                    }
                    if (images.isNotEmpty()) {
                        _state.value = _state.value.copy(inlineImages = images)
                    }
                }

                // A failed refresh over cached content is not an error the user needs: the
                // message is on screen and readable. Only an empty screen reports one.
                is ApiResult.Failure -> _state.value = _state.value.copy(
                    loading = false,
                    error = result.error.takeIf { _state.value.emails.isEmpty() },
                )
            }
        }
    }

    fun toggleExpanded(email: Email) {
        val expanded = _state.value.expandedIds
        _state.value = _state.value.copy(
            expandedIds = if (email.id in expanded) expanded - email.id else expanded + email.id,
        )
    }

    fun setReplyAction(action: ReplyAction) {
        _state.value = _state.value.copy(replyAction = action)
    }

    /**
     * Deletes [ids], or the whole conversation when none are named.
     *
     * The per-message menu passes one id. It used to pass none, which deleted every message
     * in the trail — ten messages gone for one tap on one reply.
     */
    fun delete(ids: List<String>? = null) {
        val state = _state.value
        val targets = ids ?: state.emails.map { it.id }
        if (targets.isEmpty()) return

        viewModelScope.launch {
            // In Trash, delete means delete. Everywhere else it means "move to Trash",
            // which is recoverable — and is what the confirmation copy promises.
            val result = if (state.isTrash) {
                repository.delete(state.folderName, targets)
            } else {
                repository.move(targets, state.folderName, EmailFolders.TRASH)
            }
            applyRemoval(targets, result)
        }
    }

    /** Moves [ids], or the whole conversation when none are named. */
    fun move(target: String, ids: List<String>? = null) {
        val state = _state.value
        val targets = ids ?: state.emails.map { it.id }
        if (targets.isEmpty()) return

        viewModelScope.launch {
            applyRemoval(targets, repository.move(targets, state.folderName, target))
        }
    }

    /**
     * Drops what left the folder, or reports why it did not.
     *
     * The trail is not re-fetched: the messages are gone from this folder either way, and a
     * refetch would race the server's own indexing of the move.
     */
    private fun applyRemoval(targets: List<String>, result: ApiResult<*>) {
        if (result is ApiResult.Failure) {
            _actionError.value = result.error
            return
        }
        _state.value = _state.value.copy(
            emails = _state.value.emails.filterNot { it.id in targets },
        )
    }

    /** True once nothing is left to read — the route's cue to leave the screen. */
    fun isEmpty(): Boolean = _state.value.emails.isEmpty()

    /**
     * Where a `cid:` in the body points.
     *
     * Resolved from the map built when the trail loaded, not fetched on demand: the body is
     * rendered synchronously and a missing entry simply leaves the image broken, which is
     * the honest outcome.
     */
    fun resolveContentId(contentId: String): String? =
        _state.value.inlineImages[contentId.trim('<', '>')]

    /** A correspondent's photo, when they are someone on this project. */
    fun avatarFor(address: EmailAddress): Pair<String?, String?> {
        val user = directory.findByEmail(address.address) ?: return null to null
        return user.profilePictureUrl to user.profileThumbnailKey
    }

    /**
     * Whether Reply All is worth offering.
     *
     * True when there is somebody *other than the current user* still on the message. On a
     * two-person mail it would do exactly what Reply does, and offering both teaches people
     * to stop reading the difference.
     */
    /**
     * Whether Reply All is worth offering for [email].
     *
     * Public and per-message because the trail's own ⋮ menus ask it too: an older message
     * in a conversation can have a different recipient set from the newest one, so a single
     * answer computed for the thread is wrong for the rest of it.
     */
    fun canReplyAll(email: Email?): Boolean {
        if (email == null) return false

        val me = setOfNotNull(
            mailbox.activeAddress?.lowercase(),
            currentUser.current?.email?.lowercase(),
        )

        val others = (email.to + email.cc)
            .map { it.address.lowercase() }
            .filterNot { it in me }
            .distinct()

        // A group address stands for several people even though it is one entry, so a mail
        // addressed only to one is still worth replying to all of.
        val involvesGroup = groupAddresses.isNotEmpty() &&
            (others.any { it in groupAddresses } || email.from.address.lowercase() in groupAddresses)

        return others.size > 1 || email.cc.isNotEmpty() || involvesGroup
    }

    /**
     * Which reply the button starts on.
     *
     * Chosen per message rather than fixed: a mail addressed to six people wants Reply All
     * and a mail from one person does not, and making the user pick every time is how
     * reply-all accidents happen in both directions.
     */
    private fun defaultReplyAction(email: Email?): ReplyAction =
        if (canReplyAll(email)) ReplyAction.REPLY_ALL else ReplyAction.REPLY

    /** Everyone on this message who is neither the user nor already a saved contact. */
    fun outsiders(email: Email): List<EmailAddress> {
        val me = setOfNotNull(
            mailbox.activeAddress?.lowercase(),
            currentUser.current?.email?.lowercase(),
        )

        return (listOf(email.from) + email.allRecipients())
            .filterNot { it.address.lowercase() in me }
            .filterNot { directory.findByEmail(it.address) != null }
            // Somebody already in the address book is not an outsider either. Filtering
            // only against project users meant "Add to contacts" was offered for people
            // the user had already added.
            .filterNot { it.address.lowercase() in savedContacts }
            .distinctBy { it.address.lowercase() }
    }

    /** Is this address already in the user's address book? */
    fun isSavedContact(address: String): Boolean = address.lowercase() in savedContacts


    /**
     * A content URI another app may read.
     *
     * Goes through the shared [com.zillit.zillitapp.core.storage.MediaCache] rather than
     * calling `FileProvider` here, so the authority and the declared `file_paths` are the
     * ones already proven to work for chat attachments.
     */
    fun shareableUri(file: java.io.File): android.net.Uri? =
        runCatching { cache.shareableUri(file) }.getOrNull()

    /**
     * Builds a printable document and hands it to the print service.
     *
     * @param all true prints the whole conversation, false the newest message. v2 offers
     *   both, and only when there is more than one message to choose between.
     *
     * Each message is printed with its headers above the body, because a printed mail with
     * no sender or date on it is not evidence of anything — which is usually why somebody
     * is printing it.
     */
    /**
     * Prints the conversation, one named message, or the newest one.
     *
     * [emailId] is what the per-message menu passes: v2 prints the message whose menu was
     * used, and printing the newest one instead would quietly print the wrong mail.
     */
    fun print(all: Boolean, noContent: String, jobName: String, emailId: String? = null) {
        val messages = when {
            all -> _state.value.emails.sortedBy { it.createdAt }
            emailId != null -> _state.value.emails.filter { it.id == emailId }
            else -> listOfNotNull(_state.value.emails.maxByOrNull { it.createdAt })
        }
        if (messages.isEmpty()) return

        viewModelScope.launch {
            _printRequests.emit(
                PrintableHtml(jobName = jobName, html = buildPrintable(messages, noContent)),
            )
        }
    }

    private fun buildPrintable(messages: List<Email>, noContent: String): String = buildString {
        append("<html><head><meta charset=\"utf-8\">")
        append(
            "<style>" +
                "body{font-family:sans-serif;font-size:12pt;color:#000;}" +
                ".hdr{border-bottom:1px solid #999;margin-bottom:8pt;padding-bottom:6pt;}" +
                ".hdr div{margin:2pt 0;}" +
                ".lbl{font-weight:bold;}" +
                // Each message starts a new page, so a printed conversation reads in the
                // order it happened rather than running together.
                ".msg{page-break-after:always;}" +
                ".msg:last-child{page-break-after:auto;}" +
                "img{max-width:100%;}" +
                "</style></head><body>",
        )

        messages.forEach { email ->
            append("<div class=\"msg\"><div class=\"hdr\">")
            append(row("Subject", email.subject))
            append(row("From", email.from.encoded))
            append(row("To", email.to.joinToString(", ") { it.encoded }))
            if (email.cc.isNotEmpty()) {
                append(row("Cc", email.cc.joinToString(", ") { it.encoded }))
            }
            if (email.bcc.isNotEmpty()) {
                append(row("Bcc", email.bcc.joinToString(", ") { it.encoded }))
            }
            append(row("Date", EmailDates.full(email.createdAt)))
            append("</div>")

            val body = when {
                email.isHtml && email.body.isNotBlank() -> email.body
                email.text.isNotBlank() -> "<pre>${email.text.escapeHtml()}</pre>"
                else -> noContent
            }
            append(body)
            append("</div>")
        }

        append("</body></html>")
    }

    private fun row(label: String, value: String): String =
        if (value.isBlank()) "" else "<div><span class=\"lbl\">$label:</span> ${value.escapeHtml()}</div>"

    /** Escapes header values, which are user-supplied and would otherwise inject markup. */
    private fun String.escapeHtml(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** True when this message is one the user sent — the only kind with a read receipt. */
    fun isOwnMessage(email: Email): Boolean =
        email.from.address.equals(mailbox.activeAddress, ignoreCase = true)

    fun consumeError() {
        _actionError.value = null
        _downloadFailed.value = false
    }

    private companion object {
        const val DOWNLOAD_FAILED = "email_download_failed"
    }
}

/**
 * What the detail screen shows.
 *
 * Mirrors [EmailDetail] field for field, so the destination can hand its route straight
 * over, and so a pane can build one from a list row without a route existing at all.
 */
data class EmailDetailArgs(
    val emailId: String,
    val folderName: String,
    /** Set when conversation view is on: the whole trail is shown, not just this message. */
    val threadId: String? = null,
) {
    companion object {
        fun from(route: EmailDetail): EmailDetailArgs =
            EmailDetailArgs(route.emailId, route.folderName, route.threadId)
    }
}
