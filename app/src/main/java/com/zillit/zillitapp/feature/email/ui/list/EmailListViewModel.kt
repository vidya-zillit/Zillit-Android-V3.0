package com.zillit.zillitapp.feature.email.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.database.entity.EmailEntity
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.session.CurrentUserStore
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.email.data.ConversationViewPreference
import com.zillit.zillitapp.feature.email.data.EmailBadges
import com.zillit.zillitapp.feature.email.data.EmailMailboxContext
import com.zillit.zillitapp.feature.email.data.EmailRealtime
import com.zillit.zillitapp.feature.email.data.EmailRepository
import com.zillit.zillitapp.feature.email.data.toDomain
import com.zillit.zillitapp.feature.email.domain.Email
import com.zillit.zillitapp.feature.email.domain.EmailAddress
import com.zillit.zillitapp.feature.email.domain.EmailFolder
import com.zillit.zillitapp.feature.email.domain.EmailFolders
import com.zillit.zillitapp.feature.email.domain.MailboxScope
import com.zillit.zillitapp.feature.email.ui.drawer.FolderDrawerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import com.zillit.zillitapp.feature.email.ui.EmailDates
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The mail list, the folder drawer, and everything either of them can do.
 *
 * One view model for both because they are one screen: the drawer changes which folder the
 * list is showing, and both read the same folder set and the same badge tree. Splitting them
 * would mean two subscriptions to each and a channel between them.
 *
 * ### Why read state is combined rather than stored
 * An email is unread because a **badge row** names it, not because of a column in Realm. So
 * the rows flow and the badge flow are combined, and the list re-maps whenever either
 * changes. A `read` value resolved once at write time would be wrong the moment anything was
 * read on another device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EmailListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: EmailRepository,
    private val badges: EmailBadges,
    private val mailbox: EmailMailboxContext,
    private val realtime: EmailRealtime,
    private val session: SessionStore,
    private val currentUser: CurrentUserStore,
    private val directory: ProjectDirectory,
    private val conversationPreference: ConversationViewPreference,
    private val labels: com.zillit.zillitapp.feature.email.ui.EmailRowLabels,
    private val queue: com.zillit.zillitapp.feature.email.data.EmailSendQueue,
    private val network: com.zillit.zillitapp.core.network.NetworkMonitor,
) : ViewModel() {

    private val projectId: String get() = session.activeProject.value?.projectId.orEmpty()

    private val _folderName = MutableStateFlow(EmailFolders.INBOX)
    val folderName: StateFlow<String> = _folderName

    private val _selection = MutableStateFlow(EmailSelection())
    private val _readFilter = MutableStateFlow(EmailReadFilter.ALL)
    private val _attachmentFilter = MutableStateFlow(false)
    private val _refreshing = MutableStateFlow(false)
    private val _loading = MutableStateFlow(true)
    private val _selectionLimitReached = MutableStateFlow(false)
    private val _error = MutableStateFlow<ApiError?>(null)

    /** Drafts are API-only, so they arrive here rather than through Realm. */
    private val _drafts = MutableStateFlow<List<Email>>(emptyList())

    val error: StateFlow<ApiError?> = _error

    /** Raised when a tap was refused because the 30-row cap is reached. */
    val selectionLimitReached: StateFlow<Boolean> = _selectionLimitReached

    /**
     * Conversation view.
     *
     * A property of the mailbox, not of this screen — the setting lives in email settings
     * and the list only reads it.
     */
    private val conversationView = conversationPreference.enabled

    // Re-queried on a mailbox switch: the counts are scoped to the open mailbox, and a
    // query built once would keep reporting the mailbox the screen was opened in.
    private val folderCounts = mailbox.selected
        .flatMapLatest { badges.observeFolderCounts(projectId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyMap())

    /**
     * The uids still unread in the open folder.
     *
     * Kept as a set and combined into the rows rather than resolved per row: a folder can
     * hold thousands of messages, and this re-evaluates every time anything is read
     * anywhere — including on the user's other devices.
     */
    private val unreadUids: StateFlow<Set<String>> = _folderName
        .flatMapLatest { folder -> badges.observeUnreadUids(projectId, folder) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptySet())

    /**
     * Unread across every folder, for conversation counts that span them.
     *
     * Every folder, not just the inbox: a rule can file a reply into a folder of its own,
     * and the conversation's count in the inbox has to include it. Asking only about the
     * inbox reported those threads as fully read.
     */
    private val allUnreadUids: StateFlow<Set<String>> = mailbox.selected
        .flatMapLatest { badges.observeAllUnreadUids(projectId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptySet())

    /**
     * Mail that has been sent but has not reached the server yet.
     *
     * Drawn in Sent with a clock, so a mail written offline is visible rather than being
     * nowhere at all until the connection comes back.
     */
    private val pendingSends: StateFlow<List<EmailRowState>> = mailbox.selected
        .flatMapLatest { queue.observePending(projectId, mailbox.effectiveScope) }
            .map { pending ->
                pending.map {
                    EmailRowState(
                        id = it.id,
                        threadId = it.id,
                        folderName = EmailFolders.SENT,
                        correspondent = it.recipients,
                        initials = it.recipients.take(1).uppercase(),
                        subject = it.subject.ifBlank { labels.noSubject },
                        snippet = "",
                        time = EmailDates.listRow(it.createdAt),
                        unread = false,
                        pending = true,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    private val folders: StateFlow<List<EmailFolder>> = combine(
        repository.observeFolders(),
        folderCounts,
    ) { entities, counts ->
        // Before the first sync — a cold start, or offline — the drawer would otherwise be
        // blank, which reads as a mailbox with no folders rather than one still loading.
        entities.ifEmpty { return@combine EmailFolders.defaults() }
            .map { it.toDomain(unreadCount = counts[it.folderName] ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), EmailFolders.defaults())

    /**
     * The rows, with read state resolved.
     *
     * `flatMapLatest` on the folder so switching folders swaps the Realm query rather than
     * filtering a query for everything — a mailbox can hold tens of thousands of rows.
     */
    private val rows: StateFlow<List<Email>> = _folderName
        .flatMapLatest { folder ->
            if (folder.equals(EmailFolders.DRAFTS, ignoreCase = true)) {
                _drafts
            } else {
                combine(
                    repository.observeEmails(folder),
                    unreadUids,
                ) { entities, unread -> entities.map { it.resolveRead(unread) } }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    /** Every cached message, for conversation counts that span folders. */
    private val allMessages: StateFlow<List<Email>> = combine(
        repository.observeAll(),
        allUnreadUids,
    ) { entities, unread -> entities.map { it.resolveRead(unread) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    val uiState: StateFlow<EmailListUiState> = combine(
        combine(rows, allMessages, conversationView, ::Triple),
        combine(_folderName, folders, _selection, ::Triple),
        combine(_loading, _refreshing, ::Pair),
        combine(_readFilter, _attachmentFilter, ::Pair),
        combine(pendingSends, network.isOnline, ::Pair),
    ) { content, location, progress, filters, outboxAndNetwork ->
        val (queued, online) = outboxAndNetwork
        val (folderRows, all, grouped) = content
        val (folder, folderList, selection) = location
        val (loading, refreshing) = progress
        val (readFilter, attachmentsOnly) = filters

        val filtered = folderRows.filter { email ->
            val matchesRead = when (readFilter) {
                EmailReadFilter.ALL -> true
                EmailReadFilter.READ -> !email.isUnread
                EmailReadFilter.UNREAD -> email.isUnread
            }
            matchesRead && (!attachmentsOnly || email.hasAttachments)
        }

        val outbox = if (folder.equals(EmailFolders.SENT, ignoreCase = true)) queued else emptyList()

        val displayRows = outbox + if (grouped && folder != EmailFolders.DRAFTS) {
            repository.toThreads(filtered, all, folder).map { thread ->
                thread.toRowState(labels.noSubject, labels.draft, ::avatarFor)
            }
        } else {
            filtered.map { it.toRowState(labels.noSubject, labels.draft, resolveAvatar = ::avatarFor) }
        }

        EmailListUiState(
            folder = folderList.firstOrNull { it.folderName == folder }
                ?: EmailFolder(folderName = folder),
            rows = displayRows,
            loading = loading,
            refreshing = refreshing,
            selection = selection,
            conversationView = grouped,
            filterActive = readFilter != EmailReadFilter.ALL || attachmentsOnly,
            // Offline the sync short-circuits and reports success, so without saying so the
            // list looks like a mailbox with no new mail rather than one that cannot check.
            offline = !online,
            noMailbox = mailbox.activeAddress.isNullOrBlank(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), EmailListUiState())

    val drawerState: StateFlow<FolderDrawerState> = combine(
        folders,
        mailbox.selected,
        mailbox.entitled,
        mailbox.accountsMailbox,
        // The open folder has to be a source, not a read of `_folderName` inside the block:
        // as a plain read it never re-emitted, so the highlighted row stayed on the previous
        // folder until something else happened to change.
        _folderName,
    ) { folderList, scope, entitled, accountsAddress, openFolder ->
        val personal = scope == MailboxScope.PERSONAL

        FolderDrawerState(
            folders = folderList,
            selectedFolder = openFolder,
            mailboxName = if (personal) {
                currentUser.current?.displayName.orEmpty()
            } else {
                ACCOUNTS_LABEL
            },
            mailboxEmail = if (personal) {
                mailbox.personalAddress.orEmpty()
            } else {
                accountsAddress.orEmpty()
            },
            avatarInitials = if (personal) {
                currentUser.current?.initials.orEmpty()
            } else {
                ACCOUNTS_LABEL.take(1)
            },
            avatarPictureKey = currentUser.current?.profilePictureUrl.takeIf { personal },
            // The chooser only exists for somebody who actually has a second mailbox.
            canSwitchMailbox = entitled && !accountsAddress.isNullOrBlank(),
            personalEmail = mailbox.personalAddress.orEmpty(),
            accountsEmail = accountsAddress,
            scope = scope,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), FolderDrawerState())

    val mailboxCounts: StateFlow<Map<String, Int>> = badges.observeMailboxCounts(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyMap())

    init {
        realtime.start()
        observeRealtime()

        viewModelScope.launch {
            repository.syncFolders()
            refresh()
        }
    }

    // ── Reading pane ─────────────────────────────────────────────────────────

    /**
     * The mail open beside the list, on a window wide enough to show both.
     *
     * Only meaningful in the multi-pane layout — on a phone a tap navigates and this stays
     * null. In [SavedStateHandle] because the transition that matters most, folding or
     * unfolding the device, recreates the activity: the mail being read has to be the same
     * mail afterwards.
     */
    val openedEmail: StateFlow<OpenedEmail?> = combine(
        savedStateHandle.getStateFlow<String?>(KEY_OPENED_ID, null),
        savedStateHandle.getStateFlow<String?>(KEY_OPENED_FOLDER, null),
        savedStateHandle.getStateFlow<String?>(KEY_OPENED_THREAD, null),
    ) { id, folder, thread ->
        if (id == null || folder == null) null else OpenedEmail(id, folder, thread)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun openEmail(id: String, folderName: String, threadId: String?) {
        savedStateHandle[KEY_OPENED_ID] = id
        savedStateHandle[KEY_OPENED_FOLDER] = folderName
        savedStateHandle[KEY_OPENED_THREAD] = threadId
    }

    fun closeEmail() {
        savedStateHandle[KEY_OPENED_ID] = null
        savedStateHandle[KEY_OPENED_FOLDER] = null
        savedStateHandle[KEY_OPENED_THREAD] = null
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    fun openFolder(folderName: String) {
        if (_folderName.value == folderName) return

        _folderName.value = folderName
        _selection.value = EmailSelection()
        // A mail from the previous folder has no business staying open beside a new one.
        closeEmail()
        // Set before the sync starts: without it a folder that has never synced rendered
        // its empty state for the length of the request, which reads as "no mail here".
        _loading.value = true
        viewModelScope.launch { refresh() }
    }

    fun refresh() {
        // No address means no mailbox behind it — every request would come back
        // `email_credentials_not_available`. The list shows that state instead.
        if (mailbox.activeAddress.isNullOrBlank()) {
            _loading.value = false
            return
        }

        viewModelScope.launch {
            _refreshing.value = true
            val folder = _folderName.value

            val failure = if (folder.equals(EmailFolders.DRAFTS, ignoreCase = true)) {
                // No uids, so nothing to diff — the list is simply refetched.
                when (val result = repository.drafts()) {
                    is ApiResult.Success -> {
                        _drafts.value = result.data
                        null
                    }

                    is ApiResult.Failure -> result.error
                }
            } else {
                (repository.syncFolder(folder) as? ApiResult.Failure)?.error
            }

            // Losing access to the shared mailbox mid-session falls back to the personal
            // one rather than leaving the user looking at an empty folder they can no
            // longer read.
            if (failure != null && mailbox.dropSharedOnRefusal(failure)) {
                _folderName.value = EmailFolders.INBOX
                _refreshing.value = false
                refresh()
                return@launch
            }

            if (failure != null) _error.value = failure
            _refreshing.value = false
            _loading.value = false
        }
    }

    fun setReadFilter(filter: EmailReadFilter) {
        _readFilter.value = filter
    }

    fun setAttachmentFilter(enabled: Boolean) {
        _attachmentFilter.value = enabled
    }

    fun toggleSelection(row: EmailRowState) {
        val current = _selection.value
        // A tap that does nothing needs to say why. v2 raises a dialog on the 31st; here it
        // is a message, but it is never silent.
        if (current.isFull && row.id !in current) {
            _selectionLimitReached.value = true
            return
        }
        _selection.value = current.toggle(row.id)
    }

    /**
     * Selects the visible rows, up to the cap.
     *
     * "Visible" means what the filter is showing, not the whole folder — selecting rows the
     * user cannot see and then deleting them is not what the button looks like it does.
     */
    fun selectAll() {
        val visible = uiState.value.rows
        val selection = _selection.value

        if (selection.count >= minOf(visible.size, EmailSelection.LIMIT)) {
            clearSelection()
            return
        }

        _selection.value = EmailSelection(visible.take(EmailSelection.LIMIT).map { it.id }.toSet())
        if (visible.size > EmailSelection.LIMIT) _selectionLimitReached.value = true
    }

    fun consumeSelectionLimit() {
        _selectionLimitReached.value = false
    }

    /**
     * Selects exactly one row, replacing any existing selection.
     *
     * What a row's own ⋮ menu means: it acts on **that** mail. Toggling instead would add
     * it to a selection the user had already started and then delete both.
     */
    fun selectOnly(row: EmailRowState) {
        _selection.value = EmailSelection(setOf(row.id))
    }

    fun clearSelection() {
        _selection.value = EmailSelection()
    }

    /**
     * Drops a mail that has not gone out yet.
     *
     * The only thing that can be done to a queued send. It has no uid and no server-side
     * existence, so it cannot be moved, and deleting it means cancelling it.
     */
    fun cancelPending(rowId: String) {
        viewModelScope.launch { queue.cancel(rowId) }
    }

    fun switchMailbox(scope: MailboxScope) {
        if (mailbox.effectiveScope == scope) return

        mailbox.select(scope)
        // The shared mailbox keeps its own grouping preference, so it is re-read rather
        // than carried over from the personal one.
        conversationPreference.refresh()
        // Everything is scoped by mailbox, so the whole screen resets rather than trying to
        // reconcile two mailboxes' state.
        _folderName.value = EmailFolders.INBOX
        _selection.value = EmailSelection()
        _loading.value = true

        viewModelScope.launch {
            repository.syncFolders()
            refresh()
        }
    }

    /** Delete, or move to Trash — which one depends on the folder, not on the caller. */
    fun deleteSelected() {
        val folder = _folderName.value
        val ids = selectedMessageIds()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            val result = when {
                folder.equals(EmailFolders.DRAFTS, ignoreCase = true) ->
                    repository.deleteDrafts(ids).also { refresh() }

                folder.equals(EmailFolders.TRASH, ignoreCase = true) ->
                    repository.delete(folder, ids)

                else -> repository.move(ids, folder, EmailFolders.TRASH)
            }

            if (result is ApiResult.Failure) _error.value = result.error
            clearSelection()
        }
    }

    fun moveSelected(target: String) {
        val ids = selectedMessageIds()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            val result = repository.move(ids, _folderName.value, target)
            if (result is ApiResult.Failure) _error.value = result.error
            clearSelection()
        }
    }

    /**
     * Empties the open folder.
     *
     * Only Trash destroys anything. Everywhere else this **moves to Trash**, which is what
     * v2 does and what the confirmation copy promises — a "Clear Inbox" that permanently
     * deleted every message would be unrecoverable, and nothing on screen says that is what
     * is about to happen. Drafts has neither: it gets the draft endpoint, since a draft has
     * no uid and cannot be moved.
     */
    fun clearFolder() {
        val folder = _folderName.value
        val ids = rows.value.map { it.id }

        viewModelScope.launch {
            val result = when {
                folder.equals(EmailFolders.TRASH, ignoreCase = true) -> repository.emptyTrash()
                folder.equals(EmailFolders.DRAFTS, ignoreCase = true) ->
                    if (ids.isEmpty()) ApiResult.Success(Unit) else repository.deleteDrafts(ids)

                ids.isEmpty() -> ApiResult.Success(Unit)
                else -> repository.move(ids, folder, EmailFolders.TRASH)
            }

            if (result is ApiResult.Failure) _error.value = result.error
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val result = repository.createFolder(name)
            if (result is ApiResult.Failure) _error.value = result.error
        }
    }

    fun renameFolder(from: String, to: String) {
        viewModelScope.launch {
            val result = repository.renameFolder(from, to)
            if (result is ApiResult.Failure) {
                _error.value = result.error
            } else if (_folderName.value == from) {
                // Following the rename matters: the list is querying a folder name that no
                // longer exists, so it would otherwise go empty.
                _folderName.value = (result as ApiResult.Success).data.folderName
            }
        }
    }

    fun deleteFolder(name: String) {
        viewModelScope.launch {
            val result = repository.deleteFolder(name)
            if (result is ApiResult.Failure) {
                _error.value = result.error
            } else if (_folderName.value == name) {
                openFolder(EmailFolders.INBOX)
            }
        }
    }

    fun consumeError() {
        _error.value = null
    }

    // ── Realtime ─────────────────────────────────────────────────────────────

    private fun observeRealtime() {
        realtime.mailReceived
            .onEach {
                // The inbox always, plus whatever is open — new mail can be filed straight
                // into a folder by a rule, so the inbox alone is not enough.
                repository.syncFolder(EmailFolders.INBOX)
                val current = _folderName.value
                if (current != EmailFolders.INBOX) repository.syncFolder(current)
            }
            .launchIn(viewModelScope)

        realtime.folderChanged
            .onEach { refresh() }
            .launchIn(viewModelScope)

        realtime.foldersChanged
            .onEach { repository.syncFolders() }
            .launchIn(viewModelScope)

        realtime.readElsewhere
            .onEach { uid -> repository.markReadByUid(uid) }
            .launchIn(viewModelScope)

        // A deletion, handled as a deletion. v2 routes this onto its read channel, so the
        // message stays in the list and merely turns grey.
        realtime.trailMessageDeleted
            .onEach { repository.syncFolder(_folderName.value) }
            .launchIn(viewModelScope)

        realtime.draftsChanged
            .onEach {
                if (_folderName.value.equals(EmailFolders.DRAFTS, ignoreCase = true)) refresh()
            }
            .launchIn(viewModelScope)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * The messages a selection stands for.
     *
     * In conversation view one row is a whole thread, so acting on it has to act on every
     * message in it — a move that left half a conversation behind would be worse than no
     * move at all.
     */
    private fun selectedMessageIds(): List<String> {
        val selected = _selection.value
        return uiState.value.rows
            .filter { it.id in selected }
            .flatMap { it.messageIds }
            .distinct()
    }

    /**
     * Applies read state.
     *
     * Sent, Trash and Junk use the stored flag — nothing badges them, so an absent
     * notification there would otherwise mark every sent mail unread forever. Everywhere
     * else an unread notification naming this uid is what makes it unread.
     */
    private fun EmailEntity.resolveRead(unreadUids: Set<String>): Email {
        val unread = if (folderName in EmailFolders.READ_STATE_FROM_FLAG) {
            !read
        } else {
            uid.toString() in unreadUids
        }
        return toDomain(unread = unread)
    }

    /** A correspondent's photo, when they happen to be someone on this project. */
    private fun avatarFor(address: EmailAddress): Pair<String?, String?> {
        val user = directory.findByEmail(address.address) ?: return null to null
        return user.profilePictureUrl to user.profileThumbnailKey
    }

    private companion object {
        const val KEY_OPENED_ID = "opened_email_id"
        const val KEY_OPENED_FOLDER = "opened_email_folder"
        const val KEY_OPENED_THREAD = "opened_email_thread"

        const val STOP_MS = 5_000L

        /** v2's exact row copy. */
        const val ACCOUNTS_LABEL = "Accounts"
    }
}

/** The mail open in the reading pane: enough to build the detail's arguments from. */
data class OpenedEmail(val id: String, val folderName: String, val threadId: String?) {
    /**
     * A draft opens in the composer rather than the reader — there is nothing to read, and
     * the only useful thing to do with it is carry on writing.
     *
     * Derived from the folder rather than stored, so the pane and the phone's navigation
     * cannot disagree about what a row in Drafts means.
     */
    val isDraft: Boolean get() = folderName.equals(EmailFolders.DRAFTS, ignoreCase = true)
}
