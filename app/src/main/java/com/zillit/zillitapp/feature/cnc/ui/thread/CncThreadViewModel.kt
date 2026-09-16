package com.zillit.zillitapp.feature.cnc.ui.thread

import androidx.lifecycle.SavedStateHandle
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.attachment.AttachmentResult
import com.zillit.zillitapp.core.attachment.AudioPlayer
import com.zillit.zillitapp.core.attachment.PlaybackState
import com.zillit.zillitapp.core.storage.AttachmentDownloader
import com.zillit.zillitapp.core.storage.MediaCache
import com.zillit.zillitapp.core.storage.MediaLocations
import com.zillit.zillitapp.core.ui.chat.ChatSearchState
import com.zillit.zillitapp.core.ui.chat.ChatSelectionMode
import com.zillit.zillitapp.core.ui.chat.ChatSelectionState
import com.zillit.zillitapp.core.ui.chat.model.ChatFeedItem
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import java.io.File
import com.zillit.zillitapp.core.badge.BadgeKey
import com.zillit.zillitapp.core.badge.BadgeManager
import com.zillit.zillitapp.core.badge.BadgeSection
import com.zillit.zillitapp.core.notification.BadgeSyncCoordinator
import com.zillit.zillitapp.core.attachment.PickedMedia
import com.zillit.zillitapp.core.database.entity.PendingUploadEntity
import com.zillit.zillitapp.core.storage.UploadQueue
import com.zillit.zillitapp.core.common.toShortDateTimeLabel
import com.zillit.zillitapp.core.database.entity.CncConversationEntity
import com.zillit.zillitapp.core.database.entity.CncMessageEntity
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.presence.Presence
import com.zillit.zillitapp.core.presence.PresenceRepository
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.cnc.data.ChatSurface
import com.zillit.zillitapp.feature.cnc.data.CncRepository
import com.zillit.zillitapp.feature.cnc.data.TypingController
import com.zillit.zillitapp.feature.cnc.data.TypingTarget
import com.zillit.zillitapp.feature.cnc.ui.list.ConversationKind
import com.zillit.zillitapp.feature.cnc.ui.list.initials
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.zillit.zillitapp.core.ui.chat.ViewableMedia
import com.zillit.zillitapp.core.ui.chat.chatLibraryMessages
import com.zillit.zillitapp.core.ui.chat.viewableMedia
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.zillit.zillitapp.core.ui.chat.ShareRequest
import com.zillit.zillitapp.core.ui.chat.ReadByPerson
import com.zillit.zillitapp.core.ui.chat.ReadByUiState
import com.zillit.zillitapp.feature.cnc.data.ReadByDto
import com.zillit.zillitapp.core.ui.chat.model.ChatMention
import com.zillit.zillitapp.core.ui.chat.attachmentFileName
import com.zillit.zillitapp.core.ui.chat.attachmentKey
import com.zillit.zillitapp.core.ui.chat.editableBody
import com.zillit.zillitapp.core.ui.chat.SaveRequest
import com.zillit.zillitapp.core.ui.chat.mimeTypeGuess
import com.zillit.zillitapp.R

/**
 * One C&C conversation, from storage.
 *
 * Reads Realm and never the socket: `CncRealtime` is what keeps Realm current, and it runs
 * whether or not this screen exists. That separation is what makes a message that arrives
 * while the user is on another tab still land, still move the badge, and still be there
 * when they open the thread.
 *
 * ### Arguments, not a route
 * Which conversation to show arrives as [CncThreadArgs] through an assisted factory rather
 * than being read off the navigation route. The same screen is hosted two ways: as a
 * destination of its own on a phone, and as the right-hand pane beside the conversation
 * list on a window wide enough for both, where there is no route to read from — the list
 * simply picks a conversation. One constructor serves both, and the destination builds its
 * args from the back-stack entry.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CncThreadViewModel.Factory::class)
class CncThreadViewModel @AssistedInject constructor(
    @Assisted private val args: CncThreadArgs,
    private val repository: CncRepository,
    private val directory: ProjectDirectory,
    private val presence: PresenceRepository,
    private val typing: TypingController,
    private val session: SessionStore,
    private val uploadQueue: UploadQueue,
    private val badgeManager: BadgeManager,
    private val badgeSync: BadgeSyncCoordinator,
    private val player: AudioPlayer,
    private val mediaCache: MediaCache,
    private val downloader: AttachmentDownloader,
    private val mediaLocations: MediaLocations,
) : ViewModel() {

    /**
     * Which socket chat this thread belongs to.
     *
     * Read from the route rather than fixed, so the Budget tool can open a thread through
     * this same view model when it is built. Everything below already works for both
     * surfaces; this is the one place that was pinned to C&C.
     */
    private val surface: ChatSurface =
        ChatSurface.entries.firstOrNull { it.key == args.surface } ?: ChatSurface.CNC

    private val conversationId: String = args.conversationId
    private val isGroup: Boolean = args.isGroup

    /** Budget threads are scoped to a department and a document; C&C has neither. */
    private val departmentId: String? = args.departmentId
    private val budgetDocumentId: String? = args.budgetDocumentId

    @AssistedFactory
    interface Factory {
        fun create(args: CncThreadArgs): CncThreadViewModel
    }

    private val projectId: String get() = session.activeProject.value?.projectId.orEmpty()
    private val currentUserId: String get() = session.activeProject.value?.userId.orEmpty()

    private val draft = MutableStateFlow<String?>(null)
    private val replyingTo = MutableStateFlow<CncMessageEntity?>(null)
    private val editing = MutableStateFlow<CncMessageEntity?>(null)
    private val loading = MutableStateFlow(true)

    /** "Read by 11", per message. Groups only — a one-to-one has the tick for this. */
    private val readCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** The row this thread belongs to, for the header. */
    private val conversation: StateFlow<CncConversationEntity?> =
        repository.observeConversation(surface, conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val presenceState: StateFlow<Presence> = conversation
        .flatMapLatest { row ->
            val device = row?.deviceId.orEmpty()
            // A group never shows presence, and neither does a person whose device the
            // directory has not seen — an absent dot beats a wrong one.
            if (isGroup || device.isBlank()) flowOf(Presence.Unknown)
            else presence.observe(device, projectId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Presence.Unknown)

    // ── Uploads in flight ────────────────────────────────────────────────────

    /**
     * The queue rows for files still going up in this conversation, keyed by message id.
     *
     * The queue names its rows with the same ids as the optimistic messages, so this is a
     * lookup rather than a join. Without it a file being uploaded showed the plain "sending"
     * clock for as long as it took — no percentage, no way to stop it, and no way to tell a
     * slow upload from a failed one.
     */
    private val uploads: StateFlow<Map<String, PendingUploadEntity>> =
        session.activeProject
            .flatMapLatest { project ->
                if (project == null) flowOf(emptyList()) else uploadQueue.observePending(project.projectId)
            }
            .map { rows -> rows.filter { it.scopeId == conversationId }.associateBy { it.id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    val state: StateFlow<CncThreadUiState> = combine(
        repository.observeThread(surface, conversationId),
        conversation,
        presenceState,
        typing.typing,
        combine(draft, replyingTo, editing, loading, combine(readCounts, uploads) { c, u -> c to u }) { d, r, e, l, (counts, up) ->
            Composer(d, r, e, l, counts, up)
        },
    ) { messages, row, online, typingUsers, composer ->
        build(messages, row, online, typingUsers.map { it.id }, composer)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CncThreadUiState(
            id = conversationId,
            kind = if (isGroup) ConversationKind.GROUP else ConversationKind.DIRECT,
            title = "",
            initials = "",
            isLoading = true,
        ),
    )

    init {
        // Tells the realtime layer to acknowledge arriving messages as read rather than
        // merely delivered, for as long as this thread is on screen.
        repository.onThreadOpened(conversationId)

        viewModelScope.launch {
            // Every page, not just the first. One page and a stop reads as "the chat did
            // not load" rather than "there is more further back".
            repository.loadHistoryPages(
                surface = surface,
                conversationId = conversationId,
                isGroup = isGroup,
                departmentId = departmentId,
                budgetDocumentId = budgetDocumentId,
            )
            loading.value = false
            // Opening a thread is reading it. Emitted after the history lands so the
            // watermark names the newest message rather than whatever was cached.
            repository.markThreadRead(surface, conversationId, isGroup)
            if (isGroup) readCounts.value = repository.readCounts(surface, conversationId)
        }
        observeAutoReadWhileOpen()
    }

    /**
     * Keeps this conversation read for as long as it is open.
     *
     * Clearing once on open is not enough: messages keep arriving, and each one raises a
     * badge on a thread the user is already looking at. Watching the badge itself rather
     * than message arrivals also covers pushes, which never pass through the chat socket.
     *
     * The same approach the Home unit uses, for the same reason.
     */
    private fun observeAutoReadWhileOpen() {
        val project = session.activeProject.value ?: return
        val key = BadgeKey(
            projectId = project.projectId,
            section = BadgeSection.CNC,
            unit = conversationId,
        )

        viewModelScope.launch {
            badgeManager.observeUnder(key).collect { count ->
                if (count <= 0) return@collect
                badgeSync.markRead(key)
                // The server also has to be told, or the count returns on the next sync.
                repository.markThreadRead(surface, conversationId, isGroup)
            }
        }
    }

    override fun onCleared() {
        repository.onThreadClosed(conversationId)
        player.stop()
        // Leaving mid-word must not leave this user typing on every other device for the
        // next four seconds.
        typing.stop(typingTarget())
        typing.clear()
        super.onCleared()
    }

    // ── Composer ─────────────────────────────────────────────────────────────

    fun onDraftChanged(text: String) {
        draft.value = text
        if (text.isBlank()) typing.stop(typingTarget()) else typing.onTyping(typingTarget())
    }

    fun send(text: String, mentions: List<ChatMention> = emptyList()) {
        if (text.isBlank()) return
        val reply = replyingTo.value
        replyingTo.value = null
        draft.value = ""
        typing.stop(typingTarget())

        viewModelScope.launch {
            repository.sendText(
                surface = surface,
                conversationId = conversationId,
                isGroup = isGroup,
                body = text,
                mentions = mentions.map { it.userId to it.name },
                replyTo = reply,
                receiverDeviceId = conversation.value?.deviceId?.takeIf { it.isNotBlank() },
                departmentId = departmentId,
                budgetDocumentId = budgetDocumentId,
            )
        }
    }

    /** Files, a pin, or a contact — whatever the picker returned. */
    fun onAttachmentPicked(result: AttachmentResult) {
        when (result) {
            is AttachmentResult.Location -> viewModelScope.launch {
                repository.sendLocation(
                    surface = surface,
                    conversationId = conversationId,
                    isGroup = isGroup,
                    latitude = result.latitude,
                    longitude = result.longitude,
                    address = result.address,
                    receiverDeviceId = conversation.value?.deviceId?.takeIf { it.isNotBlank() },
                    departmentId = departmentId,
                    budgetDocumentId = budgetDocumentId,
                )
            }

            is AttachmentResult.Contact -> viewModelScope.launch {
                repository.sendContact(
                    surface = surface,
                    conversationId = conversationId,
                    isGroup = isGroup,
                    name = result.name,
                    phone = result.phone,
                    receiverDeviceId = conversation.value?.deviceId?.takeIf { it.isNotBlank() },
                )
            }

            is AttachmentResult.Media -> sendFiles(result.items)

            AttachmentResult.Cancelled -> Unit
        }
    }

    /**
     * Queue picked files and put them in the thread straight away.
     *
     * The same queue the unit chat uses, so a C&C file gets the identical behaviour: it
     * survives the app being killed, waits for a connection, uploads several at a time and
     * retries on its own. The optimistic rows are written first, sharing the queue rows'
     * ids, which is what lets the worker turn each one into a real message when its bytes
     * land.
     */
    private fun sendFiles(items: List<PickedMedia>) {
        if (items.isEmpty()) return
        val project = session.activeProject.value ?: return

        viewModelScope.launch {
            // One stamp for the batch, so files picked together render as a single post
            // rather than one bubble each.
            val batch = System.currentTimeMillis()

            uploadQueue.enqueue(
                projectId = project.projectId,
                userId = project.userId,
                module = surface.key,
                scopeId = conversationId,
                folder = CHAT_FOLDER,
                media = items,
                deliveryKind = PendingUploadEntity.DELIVERY_SOCKET,
                isGroup = isGroup,
                receiverDeviceId = conversation.value?.deviceId.orEmpty(),
                onRowsQueued = { ids ->
                    viewModelScope.launch {
                        ids.forEachIndexed { index, id ->
                            val item = items.getOrNull(index) ?: return@forEachIndexed
                            repository.addPendingAttachment(
                                surface = surface,
                                uniqueId = id,
                                conversationId = conversationId,
                                isGroup = isGroup,
                                localPath = item.localPath,
                                fileName = item.fileName,
                                caption = item.caption,
                                messageType = item.mimeType.toChatMessageType(),
                                sizeBytes = item.sizeBytes,
                                durationMs = item.durationMs,
                                messageGroup = batch,
                            )
                        }
                    }
                },
            )
        }
    }

    /** MIME type → the server's message type. Matches what the unit chat sends. */
    private fun String.toChatMessageType(): String = when {
        startsWith("image/") -> "image"
        startsWith("video/") -> "video"
        startsWith("audio/") -> "audio"
        else -> "document"
    }

    fun startReply(serverId: String) {
        viewModelScope.launch {
            replyingTo.value = repository.messageByServerId(surface, serverId)
        }
    }

    fun cancelReply() { replyingTo.value = null }

    /**
     * Edit a message.
     *
     * Takes the feed's message rather than the stored row: the screen only ever has the
     * former, and looking the row up here keeps that translation in one place.
     */
    fun startEdit(message: ChatMessage) {
        editing.value = repository.messageByUniqueId(surface, message.id)
    }

    fun cancelEdit() { editing.value = null }

    /**
     * Messages for the screen to show.
     *
     * Every write on this surface used to discard whether it worked. An edit the server
     * refused closed the composer and put the old text back, a delete that failed left the
     * messages where they were, and neither said anything — so the only way to tell a
     * refusal from a slow connection was to try again and see. A string resource rather
     * than finished text, because the view model has no locale.
     */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun saveEdit(text: String) {
        val target = editing.value ?: return
        editing.value = null
        viewModelScope.launch {
            if (!repository.edit(surface, target, text)) {
                _messages.emit(R.string.something_went_wrong)
            }
        }
    }

    /**
     * Called when the thread is scrolled to its newest message.
     *
     * Opening is not the only moment a conversation becomes read: a long thread opens part
     * way up, and everything below stays unread until the user actually gets there. This is
     * also when a group's read counts are worth refetching, since that is when they are on
     * screen.
     */
    fun onReachedBottom() {
        viewModelScope.launch {
            repository.markThreadRead(surface, conversationId, isGroup)
            if (isGroup) readCounts.value = repository.readCounts(surface, conversationId)
        }
    }

    fun loadOlder() {
        val oldest = state.value.feed.firstOrNull()
        viewModelScope.launch {
            repository.loadHistory(
                surface = surface,
                conversationId = conversationId,
                isGroup = isGroup,
                before = oldest?.let { 0L },
                departmentId = departmentId,
                budgetDocumentId = budgetDocumentId,
            )
        }
    }

    // ── Multi-select: delete, share, forward ─────────────────────────────────

    private val _selection = MutableStateFlow<ChatSelectionState?>(null)
    val selection: StateFlow<ChatSelectionState?> = _selection.asStateFlow()

    /** Raised when Delete is confirmed; the screen shows the dialog. */
    private val _deleteConfirmation = MutableStateFlow<Set<String>?>(null)
    val deleteConfirmation: StateFlow<Set<String>?> = _deleteConfirmation.asStateFlow()

    fun startSelection(mode: ChatSelectionMode, message: ChatMessage) {
        // Playback and a selection both own the thread's attention; leaving a clip talking
        // over a delete confirmation is what v2 does and it is disorienting.
        player.stop()
        _search.value = null
        _selection.value = ChatSelectionState(mode, setOf(message.id))
    }

    fun toggleSelection(message: ChatMessage) {
        val current = _selection.value ?: return

        // Anyone's message can be shared; only your own can be deleted. Refused while
        // selecting rather than accepted and rejected by the server later.
        if (current.mode == ChatSelectionMode.DELETE && !message.isOwn) {
            return
        }

        val next = current.toggle(message.id)
        _selection.value = if (next.count == 0) null else next
    }

    fun cancelSelection() { _selection.value = null }

    fun confirmSelection() {
        val current = _selection.value ?: return
        when (current.mode) {
            ChatSelectionMode.DELETE -> _deleteConfirmation.value = current.selectedIds
            // Sharing hands files to the OS chooser, which the screen owns — it needs a
            // Context and a FileProvider, neither of which belongs in a view model.
            ChatSelectionMode.SHARE -> _selection.value = null
        }
    }

    fun dismissDeleteConfirmation() { _deleteConfirmation.value = null }

    fun confirmDelete() {
        val ids = _deleteConfirmation.value ?: return
        _deleteConfirmation.value = null
        _selection.value = null

        viewModelScope.launch {
            // Deleting addresses server ids. A message still pending has none, and there is
            // nothing on the server to remove.
            val targets = ids.mapNotNull { repository.messageByUniqueId(surface, it) }
                .filter { it.serverId.isNotBlank() }
            if (targets.isNotEmpty() && !repository.delete(surface, targets)) {
                _messages.emit(R.string.something_went_wrong)
            }
        }
    }

    // ── Audio playback ───────────────────────────────────────────────────────

    val playback: StateFlow<PlaybackState> = player.state

    /**
     * Play or pause a voice note.
     *
     * Three cases, in order: a note recorded here and not yet uploaded plays from its local
     * file; one already cached plays from the cache; anything else downloads first, because
     * the player takes a file rather than a key.
     */
    fun toggleAudio(message: ChatMessage) {
        val voice = message as? ChatMessage.Voice ?: return

        val key = voice.remoteKey
        if (key.isNullOrBlank()) {
            voice.localPath?.let { player.toggle(message.id, File(it)) }
            return
        }

        val name = key.substringAfterLast('/')
        val cached = mediaCache.cached(key, name)
        if (cached != null) {
            player.toggle(message.id, cached)
            return
        }

        viewModelScope.launch {
            downloader.download(key, name, CHAT_FOLDER)
            mediaCache.cached(key, name)?.let { player.toggle(message.id, it) }
        }
    }

    fun seekAudio(fraction: Float) = player.seekTo(fraction)

    // ── In-thread search ─────────────────────────────────────────────────────

    private val _search = MutableStateFlow<ChatSearchState?>(null)
    val search: StateFlow<ChatSearchState?> = _search.asStateFlow()

    fun openSearch() {
        // Search and selection take over the same chrome; opening one ends the other.
        _selection.value = null
        _search.value = ChatSearchState()
    }

    fun closeSearch() { _search.value = null }

    /**
     * Matches as you type, over what is loaded.
     *
     * Local rather than a server search: the thread is in Realm, substring matching over it
     * is instant, and paging further back widens the search on its own.
     */
    fun onSearchQueryChanged(query: String) {
        val matches = if (query.isBlank()) {
            emptyList()
        } else {
            state.value.feed
                .filterIsInstance<ChatFeedItem.Post>()
                .filter { it.root.matches(query) }
                .map { it.id }
        }

        _search.value = ChatSearchState(
            query = query,
            matchIds = matches,
            // Lands on the newest match, because that is where the thread already sits.
            currentIndex = if (matches.isEmpty()) -1 else matches.lastIndex,
        )
    }

    fun searchNext() { _search.value = _search.value?.next() }

    fun searchPrevious() { _search.value = _search.value?.previous() }

    private fun ChatMessage.matches(query: String): Boolean {
        val needle = query.trim()
        return when (this) {
            is ChatMessage.Text -> body.contains(needle, ignoreCase = true)
            is ChatMessage.Image -> caption.orEmpty().contains(needle, ignoreCase = true)
            is ChatMessage.Document -> fileName.contains(needle, ignoreCase = true)
            is ChatMessage.Location -> address.contains(needle, ignoreCase = true) ||
                placeName.contains(needle, ignoreCase = true)
            else -> false
        }
    }

    // ── Jumping to a quoted message ──────────────────────────────────────────

    /**
     * Where the thread should scroll to, set by tapping a quotation.
     *
     * A socket chat's reply is an ordinary message carrying a quote, so the message it
     * answers can be any distance up the thread — which is what makes the jump worth having
     * and is why the quotation is a control rather than decoration.
     */
    private val _jumpTo = MutableStateFlow<String?>(null)
    val jumpTo: StateFlow<String?> = _jumpTo.asStateFlow()

    /**
     * Go to the message a quotation names.
     *
     * Only within what is loaded. Paging back to find an older original would move the
     * thread under the reader on a tap that promised a short trip; the quotation already
     * carries the text, so nothing is hidden by staying put.
     */
    fun jumpToQuoted(parentServerId: String) {
        val target = state.value.feed
            .filterIsInstance<ChatFeedItem.Post>()
            .firstOrNull { it.rootServerId == parentServerId }

        if (target == null) {
            // Further back than the thread has loaded. Saying so beats a tap that does
            // nothing, which reads as a broken quotation rather than an absent one.
            viewModelScope.launch { _messages.emit(R.string.cnc_quoted_not_loaded) }
            return
        }
        _jumpTo.value = target.id
    }

    fun onJumpHandled() { _jumpTo.value = null }


    // ── Media and files in this conversation ─────────────────────────────────

    /**
     * Every attachment in the thread, newest first.
     *
     * Derived from what is stored rather than fetched: the messages are already here, and a
     * second endpoint returning the same files would only disagree with them.
     */
    val attachments: StateFlow<List<ChatMessage>> = state
        .map { current ->
            current.feed.chatLibraryMessages().filter { it.hasOpenableMedia }.reversed()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    // ── Media ────────────────────────────────────────────────────────────────

    /**
     * The attachment the full-screen viewer is showing, if any.
     *
     * Held as an id rather than the message: the feed is rebuilt on every change — a read
     * receipt, a reaction, a new message arriving — and a held copy would freeze the
     * caption and the tick at whatever they were when the viewer opened.
     */
    private val _openedMediaId = MutableStateFlow<String?>(null)
    val openedMediaId: StateFlow<String?> = _openedMediaId.asStateFlow()

    /** Whether the Media / Docs / Links gallery is open. */
    private val _libraryOpen = MutableStateFlow(false)
    val libraryOpen: StateFlow<Boolean> = _libraryOpen.asStateFlow()

    /** A file to hand to whatever app on the device knows the format. */
    private val _openExternally = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val openExternally: SharedFlow<File> = _openExternally.asSharedFlow()

    /** One file, straight to the OS chooser, from the viewer's share button. */
    private val _shareRequests = MutableSharedFlow<ShareRequest>(extraBufferCapacity = 1)
    val shareRequests: SharedFlow<ShareRequest> = _shareRequests.asSharedFlow()

    /**
     * A tap on an attachment.
     *
     * Images and video go to the in-app viewer, which can page through the thread; anything
     * else is handed to the device. v2 makes the same split, and so does the unit chat.
     */
    fun onMediaOpened(message: ChatMessage) {
        if (message is ChatMessage.Document) {
            openDocument(message)
            return
        }
        _openedMediaId.value = message.id
    }

    fun dismissMedia() { _openedMediaId.value = null }

    /**
     * The cache the share chooser reads from.
     *
     * Exposed because building a chooser Intent needs a Context and a `FileProvider` URI,
     * which is the screen's job; the cache is where the file it names already lives.
     */
    val mediaCacheForShare get() = mediaCache

    fun openLibrary() { _libraryOpen.value = true }

    fun dismissLibrary() { _libraryOpen.value = false }

    /** Everything the viewer can page through, newest last, as the thread reads. */
    fun viewableMedia(): List<ViewableMedia> = state.value.feed.viewableMedia()

    /** Every message in the thread, for the gallery to group by kind. */
    fun libraryMessages(): List<ChatMessage> = state.value.feed.chatLibraryMessages()

    /**
     * Downloads a document if it is not already local, then hands it over.
     *
     * Awaited rather than fired: opening a chooser on a file that has not arrived shows an
     * app picker and then an error, which reads as the document being broken.
     */
    fun openDocument(message: ChatMessage.Document) {
        val key = message.remoteKey ?: return
        viewModelScope.launch {
            // A download that never arrives is the commonest reason a tap on a document
            // appears to do nothing. The unit chat says so; this did not.
            val file = downloader.awaitFile(key, message.fileName, CHAT_FOLDER)
                ?: return@launch _messages.emit(R.string.something_went_wrong)
            _openExternally.emit(file)
        }
    }

    fun share(item: ViewableMedia) {
        val key = item.remoteKey ?: return
        viewModelScope.launch {
            val file = downloader.awaitFile(key, item.fileName, CHAT_FOLDER)
                ?: return@launch _messages.emit(R.string.something_went_wrong)
            _shareRequests.emit(ShareRequest(files = listOf(file), text = item.caption.orEmpty()))
        }
    }

    // ── Who has read a message ───────────────────────────────────────────────

    /** Non-null while the reader list is open. */
    private val _readBy = MutableStateFlow<ReadByUiState?>(null)
    val readBy: StateFlow<ReadByUiState?> = _readBy.asStateFlow()

    /**
     * The names behind "Read by 11", which the count alone cannot give.
     *
     * Built into the state the shared read-by screen already takes, so C&C renders the same
     * list as every other chat rather than a second one that looks almost like it. Names
     * come from the local directory: the endpoint answers with ids and read times only.
     */
    fun openReadBy(serverId: String) {
        viewModelScope.launch {
            _readBy.value = ReadByUiState(isLoading = true)

            val result = repository.readers(surface, serverId)
            val people = directory.usersOnce(projectId).associateBy { it.userId }

            fun rows(entries: List<ReadByDto>, isRead: Boolean) = entries.mapNotNull { entry ->
                val id = entry.userId ?: return@mapNotNull null
                val person = people[id]
                ReadByPerson(
                    userId = id,
                    name = person?.fullName?.takeIf(String::isNotBlank) ?: id,
                    role = person?.designationName,
                    time = entry.readTime?.takeIf { it > 0 }?.toShortDateTimeLabel().orEmpty(),
                    isRead = isRead,
                    // Delivered is a separate fact from read: a person can hold a message
                    // for hours without opening it, and the list says which.
                    isDelivered = isRead || (entry.delivered ?: 0) > 0,
                )
            }

            _readBy.value = ReadByUiState(
                isLoading = false,
                read = rows(result.read, isRead = true),
                unread = rows(result.unread, isRead = false),
                // Notify has no endpoint on this module; offering it would be a dead button.
                canNotify = false,
            )
        }
    }

    fun dismissReadBy() { _readBy.value = null }

    // ── Location and blocking ────────────────────────────────────────────────

    /** A maps URL for the screen to hand to whatever maps app is installed. */
    private val _locationRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val locationRequests: SharedFlow<String> = _locationRequests.asSharedFlow()

    /**
     * Open a pin somebody shared.
     *
     * A `maps.google.com` query rather than a `geo:` URI, because every maps app on the
     * device offers to handle the first and only one answers the second. The unit chat does
     * the same; this surface was simply never wired to it, so tapping a pin did nothing.
     */
    fun openLocation(message: ChatMessage.Location) {
        if (!message.hasCoordinates) return
        viewModelScope.launch {
            _locationRequests.emit(
                "https://maps.google.com/?q=${message.latitude},${message.longitude}",
            )
        }
    }

    /** Lift a block from the banner, without a trip through the details screen. */
    fun unblock() {
        viewModelScope.launch {
            if (!repository.toggleBlock(surface, conversationId, block = false)) {
                _messages.emit(R.string.something_went_wrong)
            }
        }
    }

    // ── Message actions ──────────────────────────────────────────────────────

    /**
     * Text the screen should put on the clipboard.
     *
     * Emitted rather than written: a `ClipboardManager` is a Context service and the view
     * model must not hold one. Same split as the share chooser.
     */
    private val _clipboardRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val clipboardRequests: SharedFlow<String> = _clipboardRequests.asSharedFlow()

    fun copyToClipboard(message: ChatMessage) {
        val text = message.editableBody()
        if (text.isBlank()) return
        viewModelScope.launch { _clipboardRequests.emit(text) }
    }

    /**
     * Pull a file down so it is on the device.
     *
     * Fire and forget: the download reports itself through the same state the bubble reads,
     * so the progress appears where the user is already looking.
     */
    private val _saveRequests = MutableSharedFlow<SaveRequest>(extraBufferCapacity = 1)
    val saveRequests: SharedFlow<SaveRequest> = _saveRequests.asSharedFlow()

    fun saveToDevice(message: ChatMessage) {
        val key = message.attachmentKey() ?: return
        val name = message.attachmentFileName()

        viewModelScope.launch {
            // Downloaded first, then copied out: saving is a copy from a file that is
            // already here, so a Save with no connection fails as a download rather than
            // part-way through writing to the user's storage.
            val file = downloader.awaitFile(key, name, CHAT_FOLDER)
                ?: return@launch _messages.emit(R.string.something_went_wrong)
            _saveRequests.emit(
                SaveRequest(file = file, fileName = name, mimeType = message.mimeTypeGuess()),
            )
        }
    }

    /**
     * Try a stalled message again.
     *
     * Two different failures wear the same bubble: a file whose upload gave up, and a text
     * message the server never acknowledged. The first is the queue's to retry, the second
     * the repository's, and the user should not have to know which they are looking at.
     */
    fun onRetry(message: ChatMessage) {
        viewModelScope.launch {
            if (uploads.value.containsKey(message.id)) {
                uploadQueue.retry(message.id)
            } else {
                repository.retryPending(surface, message.id)
            }
        }
    }

    /** Abandon an upload and take its optimistic row out of the thread with it. */
    fun onCancelUpload(message: ChatMessage) {
        viewModelScope.launch {
            uploadQueue.cancel(message.id)
            repository.discardPending(surface, message.id)
        }
    }

    // ── Reactions ────────────────────────────────────────────────────────────

    /**
     * Add [emoji] to a message, or take it back when it is already yours.
     *
     * The server has one reaction per person per message and clears it with an empty
     * string, so a toggle is the whole interaction: there is no separate remove. Sending
     * the same emoji twice would otherwise read as a no-op to the user and a rewrite to
     * the server.
     */
    fun onReact(message: ChatMessage, emoji: String) {
        val already = message.reactions.any { it.isMine && it.emoji == emoji }

        viewModelScope.launch {
            val entity = repository.messageByUniqueId(surface, message.id) ?: return@launch
            repository.react(surface, entity, if (already) "" else emoji)
        }
    }

    // ── Building the state ───────────────────────────────────────────────────

    private fun build(
        messages: List<CncMessageEntity>,
        row: CncConversationEntity?,
        online: Presence,
        typingIds: List<String>,
        composer: Composer,
    ): CncThreadUiState {
        val people = directory.usersOnce(projectId).associateBy { it.userId }
        val members = row?.members?.associateBy { it.userId }.orEmpty()

        fun nameOf(id: String): String =
            people[id]?.fullName?.takeIf { it.isNotBlank() }
                ?: members[id]?.name?.takeIf { it.isNotBlank() }
                ?: ""

        val title = row?.name?.takeIf { it.isNotBlank() } ?: nameOf(conversationId)

        // Each stored file says which bucket and region it is in, and after a restart this
        // is the only place that knowledge comes back — history is read from Realm, not
        // re-fetched. Without it a file that lives outside the project's own region is
        // requested from the project's bucket and comes back `NoSuchKey`.
        messages.forEach { message ->
            message.attachments.forEach { file ->
                mediaLocations.remember(
                    media = file.media,
                    thumbnail = file.thumbnail,
                    bucket = file.bucket,
                    region = file.region,
                )
            }
        }

        /**
         * One stored row as the display message the composer and the reply strip show.
         *
         * Routed through the same mapper the feed uses rather than read off the entity: the
         * author, the timestamp and a reply's quoted text are all assembled there, and a
         * second assembly here would drift from the first.
         */
        fun CncMessageEntity.asDisplayMessage(): ChatMessage? =
            messages.firstOrNull { it.uniqueId == uniqueId }
                ?.let { listOf(it) }
                ?.toCncFeed(currentUserId, ::nameOf, isGroup = isGroup)
                ?.filterIsInstance<ChatFeedItem.Post>()
                ?.firstOrNull()
                ?.root

        return CncThreadUiState(
            id = conversationId,
            kind = if (isGroup) ConversationKind.GROUP else ConversationKind.DIRECT,
            title = title,
            initials = title.initials(),
            pictureKey = row?.pictureKey?.takeIf { it.isNotBlank() },
            thumbnailKey = row?.thumbnailKey?.takeIf { it.isNotBlank() },
            subtitle = subtitleFor(row, online),
            online = online.online,
            feed = messages.toCncFeed(
                currentUserId = currentUserId,
                nameOf = ::nameOf,
                designationOf = { people[it]?.designationName.orEmpty() },
                pictureOf = { people[it]?.profilePictureUrl to people[it]?.profileThumbnailKey },
                isGroup = isGroup,
                uploadStateOf = { id ->
                    composer.uploads[id]?.let { row ->
                        UploadProgress(
                            fraction = row.progress,
                            hasFailed = row.status == PendingUploadEntity.STATUS_FAILED,
                            isFinished = row.status >= PendingUploadEntity.STATUS_UPLOADED,
                        )
                    }
                },
            ),
            mentionCandidates = row?.members.orEmpty()
                .filter { it.enabled && it.userId != currentUserId }
                .map { member ->
                    ChatMention(
                        userId = member.userId,
                        name = people[member.userId]?.fullName?.takeIf { it.isNotBlank() }
                            ?: member.name,
                    )
                }
                .filter { it.name.isNotBlank() }
                .sortedBy { it.name.lowercase() },
            isLoading = composer.loading && messages.isEmpty(),
            typing = typingIds.map(::nameOf).filter { it.isNotBlank() },
            readCounts = composer.readCounts,
            draft = composer.draft,
            replyingTo = composer.replyingTo?.asDisplayMessage(),
            // The message being edited, which is what puts the composer into edit mode.
            // Tracked inside this view model since Edit was built, but never published on
            // the state — so the option was offered, the message was selected, and the
            // composer stayed an ordinary composer with nothing to type the change into.
            editing = composer.editing?.asDisplayMessage(),
            block = when {
                row?.blockedByMe == true -> ThreadBlock.BLOCKED_BY_ME
                row?.blockedMe == true -> ThreadBlock.BLOCKED_ME
                isGroup && row?.enabled == false -> ThreadBlock.REMOVED_FROM_GROUP
                else -> null
            },
        )
    }

    /**
     * The line under the name.
     *
     * A group says how many people are in it. A person says whether they are here now, and
     * otherwise when they last were — and says nothing at all when the server has never
     * recorded a last-seen, rather than claiming 1 Jan 1970.
     */
    private fun subtitleFor(row: CncConversationEntity?, online: Presence): String = when {
        isGroup -> row?.members?.count { it.enabled }?.takeIf { it > 0 }?.let { "$it" }.orEmpty()
        online.online -> ONLINE
        online.hasLastSeen -> online.lastSeen.toShortDateTimeLabel()
        else -> row?.designation.orEmpty()
    }

    private fun typingTarget() = TypingTarget(
        surface = surface,
        conversationId = conversationId,
        isGroup = isGroup,
        // Budget is two tools, not one: a department thread and the project-level one send
        // different `chat_tool` values, and the receiving side filters on it.
        chatTool = surface.chatTool(departmentId),
        departmentId = departmentId,
        budgetDocumentId = budgetDocumentId,
    )

    /** The composer's own state in one emission — `combine` takes at most five sources. */
    private data class Composer(
        val draft: String?,
        val replyingTo: CncMessageEntity?,
        val editing: CncMessageEntity?,
        val loading: Boolean,
        val readCounts: Map<String, Int>,
        /** Queue rows for files still going up, keyed by the message they belong to. */
        val uploads: Map<String, PendingUploadEntity> = emptyMap(),
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val ONLINE = "Online"

        /** Storage folder segment for C&C files, matching v2's. */
        const val CHAT_FOLDER = "chat"
    }
}

/**
 * Which conversation a thread shows.
 *
 * Mirrors `Route.CncThread` field for field, so the destination hands its back-stack entry
 * straight over and a pane can build one from a list row with no route in sight.
 */
data class CncThreadArgs(
    val conversationId: String,
    val isGroup: Boolean = false,
    /** Which socket surface — C&C, or a Budget document's thread. */
    val surface: String? = null,
    val departmentId: String? = null,
    val budgetDocumentId: String? = null,
)
