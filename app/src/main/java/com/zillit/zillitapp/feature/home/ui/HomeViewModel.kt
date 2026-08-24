package com.zillit.zillitapp.feature.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.attachment.AttachmentOption
import com.zillit.zillitapp.core.attachment.AttachmentResult
import com.zillit.zillitapp.core.attachment.PickedMedia
import com.zillit.zillitapp.core.attachment.PlaybackState
import com.zillit.zillitapp.core.attachment.RecordingState
import com.zillit.zillitapp.core.storage.DownloadState
import com.zillit.zillitapp.core.badge.BadgeAxis
import com.zillit.zillitapp.core.badge.BadgeKey
import com.zillit.zillitapp.core.badge.BadgeManager
import com.zillit.zillitapp.core.badge.BadgeSection
import com.zillit.zillitapp.core.badge.BadgeTool
import com.zillit.zillitapp.core.badge.BadgeModule
import com.zillit.zillitapp.core.chat.data.ChatModule
import com.zillit.zillitapp.core.chat.data.ChatPage
import com.zillit.zillitapp.core.chat.data.ChatRepository
import com.zillit.zillitapp.core.chat.data.HomeUnitKind
import com.zillit.zillitapp.core.docdist.DocDistFolder
import com.zillit.zillitapp.core.translate.TranslationService
import com.zillit.zillitapp.core.database.entity.ChatMessageEntity
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.session.CurrentUserStore
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.storage.UploadQueue
import com.zillit.zillitapp.core.ui.chat.model.ChatFeedItem
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.core.ui.chat.ChatMessageOption
import com.zillit.zillitapp.core.ui.chat.ChatMessageOptions
import com.zillit.zillitapp.core.ui.chat.ChatSelectionMode
import com.zillit.zillitapp.core.ui.chat.ChatHistoryRequest
import com.zillit.zillitapp.core.ui.chat.ChatSearchState
import com.zillit.zillitapp.core.ui.chat.ChatSelectionState
import com.zillit.zillitapp.core.ui.chat.ReadByRequest
import com.zillit.zillitapp.core.ui.chat.ShareRequest
import com.zillit.zillitapp.core.ui.chat.ForwardProject
import com.zillit.zillitapp.core.ui.chat.ForwardTarget
import com.zillit.zillitapp.core.ui.chat.ViewableMedia
import com.zillit.zillitapp.core.ui.chat.model.ChatUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Home: the unit strip and the selected unit's thread, both from Realm.
 *
 * Nothing here fetches for display. Units, messages and the crew directory were written to
 * Realm when the project opened and are kept current by [com.zillit.zillitapp.core.chat.ChatSocketBridge];
 * this only observes them. That is what makes the thread render offline, survive a failed
 * refresh, and update the instant a socket event lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val directory: ProjectDirectory,
    private val chatRepository: ChatRepository,
    private val uploadQueue: UploadQueue,
    private val currentUser: CurrentUserStore,
    private val badgeManager: BadgeManager,
    private val calendarBadges: com.zillit.zillitapp.core.calendar.CalendarBadges,
    private val badgeSync: com.zillit.zillitapp.core.notification.BadgeSyncCoordinator,
    private val downloader: com.zillit.zillitapp.core.storage.AttachmentDownloader,
    private val mediaCache: com.zillit.zillitapp.core.storage.MediaCache,
    private val recorder: com.zillit.zillitapp.core.attachment.AudioRecorder,
    private val player: com.zillit.zillitapp.core.attachment.AudioPlayer,
    private val session: SessionStore,
    private val projectRepository: com.zillit.zillitapp.feature.project.data.ProjectRepository,
    private val drafts: com.zillit.zillitapp.core.chat.data.DraftStore,
    private val networkMonitor: com.zillit.zillitapp.core.network.NetworkMonitor,
    private val translation: com.zillit.zillitapp.core.translate.TranslationService,
    private val docDistribution: com.zillit.zillitapp.core.docdist.DocDistributionPublisher,
) : ViewModel() {

    private val selectedUnitId = MutableStateFlow<String?>(null)

    /**
     * What kind of unit is open.
     *
     * Tracked alongside the id rather than looked up from [units] on demand, because this is
     * read from a collector started in `init` — before `units` is constructed. Reading it
     * there threw a NullPointerException on the first emission and took the app down.
     */
    private val selectedUnitKind = MutableStateFlow<HomeUnitKind?>(null)

    /**
     * Whether the Home tab is actually in front of the user.
     *
     * Drives auto-read: a message that arrives while its unit is on screen has been seen,
     * so it must never raise a badge. Set false when the tab is left or the app is
     * backgrounded — then the badge is the point.
     */
    private val screenVisible = MutableStateFlow(false)

    init {
        observeAutoReadWhileOpen()
    }


    /** Set when Call Sheet needs the replace decision before the picker opens. */
    private val _callSheetPrompt = MutableStateFlow<CallSheetPrompt?>(null)
    val callSheetPrompt: StateFlow<CallSheetPrompt?> = _callSheetPrompt

    val units: StateFlow<List<ChatUnit>> =
        session.activeProject
            // flatMapLatest, not map: switching projects must tear down the previous
            // project's Realm subscription, or both feed the same strip.
            .flatMapLatest { project ->
                if (project == null) flowOf(emptyList()) else unitsWithBadges(project.projectId)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The selected unit's thread.
     *
     * Combined with the directory so a name or avatar change re-renders messages already
     * on screen — messages carry a sender id, not a name, so the author has to be resolved
     * at render time against live data.
     */
    /**
     * Where the unread divider goes: the newest message at the moment the unit was opened.
     *
     * Captured once per open and **not** updated as messages arrive, so the line stays put
     * while you read instead of chasing the bottom of the list.
     */
    private val unreadAnchor = MutableStateFlow(0L)

    /** True until the first page for the open unit has been fetched. */
    private val _isLoadingThread = MutableStateFlow(false)
    val isLoadingThread: StateFlow<Boolean> = _isLoadingThread

    val feed: StateFlow<List<ChatFeedItem>> =
        combine(session.activeProject, selectedUnitId) { project, unitId -> project to unitId }
            .flatMapLatest { (project, unitId) ->
                if (project == null || unitId.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        chatRepository.observeMessages(ChatModule.HOME, project.projectId, unitId),
                        directory.observeUsers(project.projectId),
                        unreadAnchor,
                    ) { messages, _, anchor -> messages.toFeed(anchor) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedUnit: StateFlow<ChatUnit?> =
        combine(units, selectedUnitId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Which attachment options the current unit allows. Call Sheet is documents-only. */
    val attachmentOptions: StateFlow<Set<AttachmentOption>> =
        selectedUnit
            .flatMapLatest { unit ->
                flowOf(
                    if (unit?.kind == HomeUnitKind.CALL_SHEET) {
                        AttachmentOption.CALL_SHEET
                    } else {
                        AttachmentOption.CHAT_DEFAULT
                    },
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                AttachmentOption.CHAT_DEFAULT,
            )

    private val _longPressedMessage = MutableStateFlow<ChatMessage?>(null)
    val longPressedMessage: StateFlow<ChatMessage?> = _longPressedMessage

    private val _openedMediaId = MutableStateFlow<String?>(null)
    val openedMediaId: StateFlow<String?> = _openedMediaId

    /** Live recording state, for the composer's level meter. */
    val recordingState: StateFlow<RecordingState> = recorder.state

    private var recordingTicker: Job? = null

    fun startRecording() {
        if (!recorder.start()) return
        // MediaRecorder has no progress callback, so the level is polled while it runs.
        recordingTicker = viewModelScope.launch {
            while (recorder.isRecording) {
                recorder.sample()
                delay(RECORDING_TICK_MS)
            }
        }
    }

    /** Release: the take becomes a voice note and goes through the normal upload path. */
    fun stopRecordingAndSend() {
        recordingTicker?.cancel()
        val finished = recorder.stop() ?: return
        recorder.reset()

        val project = session.activeProject.value ?: return
        val unit = selectedUnit.value ?: return

        viewModelScope.launch {
            // Wrapped as a PickedMedia so a voice note is not a special case anywhere
            // downstream — same queue, same worker, same retry.
            val media = PickedMedia(
                uri = android.net.Uri.fromFile(finished.file),
                localPath = finished.file.absolutePath,
                fileName = finished.file.name,
                mimeType = "audio/mp4",
                sizeBytes = finished.file.length(),
                durationMs = finished.durationMs,
            )
            onAttachmentPicked(AttachmentResult.Media(listOf(media)))
        }
    }

    fun cancelRecording() {
        recordingTicker?.cancel()
        recorder.cancel()
    }

    // -- voice note playback ------------------------------------------------

    val playbackState: StateFlow<PlaybackState> = player.state

    /**
     * Plays a voice note, downloading it first if it is not cached.
     *
     * Playback is from a local file only — streaming from S3 stalls mid-sentence on set
     * wifi, and a voice note is short enough that fetching it whole is quick.
     */
    fun toggleAudio(message: ChatMessage) {
        val voice = message as? ChatMessage.Voice ?: return
        val key = voice.remoteKey

        if (key.isNullOrBlank()) {
            // Still local — a note recorded on this device that has not uploaded yet.
            voice.localPath?.let { player.toggle(message.id, java.io.File(it)) }
            return
        }

        val name = key.substringAfterLast('/')
        val cached = mediaCache.cached(key, name)
        if (cached != null) {
            player.toggle(message.id, cached)
        } else {
            downloader.download(key, name, ChatModule.HOME.key)
            // Plays as soon as the download lands, so one tap is enough.
            viewModelScope.launch {
                downloader.states.collect { states ->
                    val state = states[key]
                    if (state is DownloadState.Ready) {
                        player.toggle(message.id, state.file)
                        return@collect
                    }
                }
            }
        }
    }

    fun seekAudio(fraction: Float) = player.seekTo(fraction)

    fun onMessageLongPressed(message: ChatMessage) {
        _longPressedMessage.value = message
    }

    fun dismissLongPress() {
        _longPressedMessage.value = null
        _longPressedReply.value = null
    }

    // -- reply options --------------------------------------------------------

    /** The reply whose menu is open, with the message it hangs off. */
    data class ReplyTarget(val parentServerId: String, val reply: ChatMessage)

    private val _longPressedReply = MutableStateFlow<ReplyTarget?>(null)
    val longPressedReply: StateFlow<ReplyTarget?> = _longPressedReply

    fun onReplyOptions(parentServerId: String?, reply: ChatMessage) {
        // Without the parent's server id there is no endpoint to call — every reply action
        // is addressed as `{chatId}/{commentId}`.
        val parentId = parentServerId ?: return
        _longPressedReply.value = ReplyTarget(parentId, reply)
    }

    fun optionsForReply(target: ReplyTarget): ChatMessageOptions {
        val unit = selectedUnit.value
        val reply = target.reply
        return ChatMessageOptions(
            isOwnMessage = reply.isOwn,
            // A reply's id IS its comment id once the server has one; an unsent reply
            // falls back to the synthetic "<parent>-reply-<time>" the mapper builds.
            isConfirmed = !reply.id.contains(REPLY_PENDING_MARKER),
            isTextMessage = reply is ChatMessage.Text,
            isLocationMessage = reply is ChatMessage.Location,
            isImageMessage = reply is ChatMessage.Image,
            hasAttachment = reply.hasOpenableMedia,
            hasBody = when (reply) {
                is ChatMessage.Text -> reply.body.isNotBlank()
                is ChatMessage.Image -> !reply.caption.isNullOrBlank()
                else -> false
            },
            isAdmin = currentUser.isAdmin,
            canPost = unit?.canPost == true,
            canDownload = unit?.canDownload == true,
            isWithinEditWindow = reply.createdAt.isWithinEditWindow(),
            isCallSheet = unit?.kind == HomeUnitKind.CALL_SHEET,
            isTranslationEnabled = translation.isAvailable(projectLanguage()),
            isAlreadyTranslated = isTranslated(reply.id),
        )
    }

    fun onReplyOptionSelected(option: ChatMessageOption) {
        val target = _longPressedReply.value ?: return
        _longPressedReply.value = null
        if (!requireOnline(option)) return

        when (option) {
            ChatMessageOption.Copy -> copyToClipboard(target.reply)
            ChatMessageOption.Save -> saveToDevice(target.reply)
            ChatMessageOption.Edit -> startEdit(
                EditTarget(
                    parentServerId = target.parentServerId,
                    commentId = target.reply.id,
                    body = target.reply.editableBody(),
                ),
            )

            ChatMessageOption.Delete -> _replyDeleteConfirmation.value = target
            ChatMessageOption.Translate -> translateReply(target)

            ChatMessageOption.ReadByUser -> {
                val unit = selectedUnit.value ?: return
                player.stop()
                _readByRequest.value = ReadByRequest(
                    module = ChatModule.HOME,
                    scopeId = unit.id,
                    messageId = target.parentServerId,
                    // What makes the endpoint answer about the reply rather than the message.
                    commentId = target.reply.id,
                    canPost = unit.canPost,
                )
            }

            else -> Unit
        }
    }

    private val _replyDeleteConfirmation = MutableStateFlow<ReplyTarget?>(null)
    val replyDeleteConfirmation: StateFlow<ReplyTarget?> = _replyDeleteConfirmation

    fun dismissReplyDeleteConfirmation() {
        _replyDeleteConfirmation.value = null
    }

    fun confirmReplyDelete() {
        val target = _replyDeleteConfirmation.value ?: return
        _replyDeleteConfirmation.value = null

        val project = session.activeProject.value ?: return
        val unitId = selectedUnitId.value ?: return

        viewModelScope.launch {
            chatRepository.deleteReply(
                module = ChatModule.HOME,
                project = project,
                scopeId = unitId,
                chatServerId = target.parentServerId,
                commentId = target.reply.id,
            )
        }
    }

    /**
     * A tap on an attachment.
     *
     * Images and video go to the in-app viewer, which can page through them; anything else
     * is handed to whatever app on the device knows the format. v2 makes the same split.
     */
    fun onMediaOpened(message: ChatMessage) {
        if (message is ChatMessage.Document) {
            openDocument(message)
            return
        }
        _openedMediaId.value = message.id
    }

    fun dismissMedia() {
        _openedMediaId.value = null
    }

    /**
     * The facts about a message; the menu derives itself from them.
     *
     * Stated once here rather than at each call site, which is how v2 ended up with
     * Forward hidden on Call Sheet in one screen and shown in another.
     */
    fun optionsFor(message: ChatMessage): ChatMessageOptions {
        val unit = selectedUnit.value
        return ChatMessageOptions(
            isOwnMessage = message.isOwn,
            isConfirmed = feed.value
                .filterIsInstance<ChatFeedItem.Post>()
                .firstOrNull { it.root.id == message.id }
                ?.rootServerId != null,
            isTextMessage = message is ChatMessage.Text,
            isLocationMessage = message is ChatMessage.Location,
            isImageMessage = message is ChatMessage.Image,
            hasAttachment = message.hasOpenableMedia,
            hasBody = when (message) {
                is ChatMessage.Text -> message.body.isNotBlank()
                is ChatMessage.Image -> !message.caption.isNullOrBlank()
                else -> false
            },
            isAdmin = currentUser.isAdmin,
            canPost = unit?.canPost == true,
            canDownload = unit?.canDownload == true,
            isWithinEditWindow = message.createdAt.isWithinEditWindow(),
            isCallSheet = unit?.kind == HomeUnitKind.CALL_SHEET,
            isTranslationEnabled = translation.isAvailable(projectLanguage()),
            isAlreadyTranslated = isTranslated(message.id),
        )
    }

    /** Whether Translate has already been run on this message or reply. */
    private fun isTranslated(messageId: String): Boolean = feed.value
        .filterIsInstance<ChatFeedItem.Post>()
        .any { post ->
            (post.root.id == messageId && post.root.isTranslated) ||
                post.replies.any { it.id == messageId && it.isTranslated }
        }

    /**
     * v2's `isShowEditOption`: 30 minutes from posting.
     *
     * The snackbar says "2 hrs" but `Constants.DIFFERENCE_IN_HOURS` is 30 **minutes** and
     * that is what the code compares against — the copy is stale, the behaviour is not.
     */
    private fun Long.isWithinEditWindow(): Boolean =
        this > 0 && (System.currentTimeMillis() - this) < EDIT_WINDOW_MS

    /** Every media message in the unit, so the viewer can page rather than show one. */
    fun viewableMedia(): List<ViewableMedia> =
        feed.value
            .filterIsInstance<ChatFeedItem.Post>()
            .mapNotNull { post ->
                val message = post.root
                if (!message.hasOpenableMedia) return@mapNotNull null

                ViewableMedia(
                    id = message.id,
                    remoteKey = when (message) {
                        is ChatMessage.Image -> message.remoteKey
                        is ChatMessage.Video -> message.remoteKey
                        is ChatMessage.Document -> message.remoteKey
                        else -> null
                    },
                    thumbnailKey = when (message) {
                        is ChatMessage.Image -> message.thumbnail
                        is ChatMessage.Video -> message.thumbnail
                        is ChatMessage.Document -> message.thumbnail
                        else -> null
                    },
                    localPath = when (message) {
                        is ChatMessage.Image -> message.localPath
                        is ChatMessage.Video -> message.localPath
                        is ChatMessage.Document -> message.localPath
                        else -> null
                    },
                    fileName = (message as? ChatMessage.Document)?.fileName
                        ?: message.id,
                    caption = (message as? ChatMessage.Image)?.caption,
                    // Name only — `displayName` appends the designation, which is a raw
                    // server label key here with no composable in scope to resolve it.
                    authorName = message.author.name,
                    timestamp = message.timestamp,
                    isImage = message is ChatMessage.Image,
                )
            }

    /**
     * Actions that reach the server, and are refused offline.
     *
     * v2 checks connectivity separately in front of each one and shows the same snackbar.
     * Listing them here states the rule once — everything else (Copy, Save from cache,
     * Gallery) works perfectly well with no network and must not be blocked.
     */
    private val onlineOnlyOptions = setOf(
        ChatMessageOption.Delete,
        ChatMessageOption.Forward,
        ChatMessageOption.Edit,
        ChatMessageOption.DistributeToDD,
    )

    /** Raised when an action was refused; the screen shows it as a snackbar. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages

    private fun requireOnline(option: ChatMessageOption): Boolean {
        if (option !in onlineOnlyOptions || networkMonitor.isOnline.value) return true
        _messages.tryEmit(R.string.internet_connection_lost_please_check)
        return false
    }

    fun onOptionSelected(option: ChatMessageOption) {
        val message = _longPressedMessage.value ?: return
        _longPressedMessage.value = null
        if (!requireOnline(option)) return

        when (option) {
            ChatMessageOption.Reply -> startReply(message)
            ChatMessageOption.Forward -> {
                _forwarding.value = message
                // Defaults to the project you are in, which is the common case.
                forwardProjects.value.firstOrNull { it.id == currentProjectId() }
                    ?.let(::onForwardProjectSelected)
            }
            // Both open a selection seeded with this message, so "delete this one" and
            // "delete these five" are the same flow rather than two.
            ChatMessageOption.Delete -> startSelection(ChatSelectionMode.DELETE, message)
            ChatMessageOption.Share -> startSelection(ChatSelectionMode.SHARE, message)
            ChatMessageOption.Save -> saveToDevice(message)
            ChatMessageOption.ReadByUser -> openReadBy(message)
            ChatMessageOption.Copy -> copyToClipboard(message)
            ChatMessageOption.Edit -> {
                val serverId = message.serverId() ?: return
                startEdit(EditTarget(messageServerId = serverId, body = message.editableBody()))
            }
            ChatMessageOption.Gallery -> _libraryOpen.value = true
            ChatMessageOption.Print -> printRequest(message)
            ChatMessageOption.ImageReply -> startImageReply(message)
            ChatMessageOption.Translate -> translate(message)
            ChatMessageOption.DistributeToDD -> publishToDocDistribution(message)
        }
    }

    // -- translate ------------------------------------------------------------

    private fun translate(message: ChatMessage) {
        val project = session.activeProject.value ?: return
        val unitId = selectedUnitId.value ?: return
        val original = message.editableBody()
        if (original.isBlank()) return

        viewModelScope.launch {
            val composed = translation.translateForDisplay(
                original = original,
                projectLanguage = projectLanguage(),
                isOwnMessage = message.isOwn,
            ) ?: run {
                _messages.tryEmit(R.string.translate_failed)
                return@launch
            }

            if (composed == TranslationService.ALREADY_IN_LANGUAGE) {
                _messages.tryEmit(R.string.translate_already_in_language)
                return@launch
            }

            chatRepository.applyTranslation(
                module = ChatModule.HOME,
                projectId = project.projectId,
                scopeId = unitId,
                uniqueId = message.id,
                original = original,
                translated = composed,
            )
        }
    }

    private fun translateReply(target: ReplyTarget) {
        val project = session.activeProject.value ?: return
        val unitId = selectedUnitId.value ?: return
        val original = target.reply.editableBody()
        if (original.isBlank()) return

        // The reply's parent is what the row is keyed by in Realm; the comment id picks
        // the reply inside it.
        val parentUniqueId = feed.value
            .filterIsInstance<ChatFeedItem.Post>()
            .firstOrNull { it.rootServerId == target.parentServerId }
            ?.root?.id
            ?: return

        viewModelScope.launch {
            val composed = translation.translateForDisplay(
                original = original,
                projectLanguage = projectLanguage(),
                isOwnMessage = target.reply.isOwn,
            ) ?: run {
                _messages.tryEmit(R.string.translate_failed)
                return@launch
            }

            if (composed == TranslationService.ALREADY_IN_LANGUAGE) {
                _messages.tryEmit(R.string.translate_already_in_language)
                return@launch
            }

            chatRepository.applyTranslation(
                module = ChatModule.HOME,
                projectId = project.projectId,
                scopeId = unitId,
                uniqueId = parentUniqueId,
                commentId = target.reply.id,
                original = original,
                translated = composed,
            )
        }
    }

    private fun projectLanguage(): String? =
        projectRepository.cachedProject(session.activeProject.value?.projectId)?.language

    // -- publish to document distribution -------------------------------------

    /**
     * Call Sheet's "Publish to Doc Distribution".
     *
     * Only offered on Call Sheet, because that is the unit whose documents are meant to go
     * out to the wider crew — v2 gates it on the same identifier. Confirmed first: the
     * document becomes visible to everyone with Doc Distribution access, which is a wider
     * audience than the unit.
     */
    private val _publishPrompt = MutableStateFlow<ChatMessage.Document?>(null)
    val publishPrompt: StateFlow<ChatMessage.Document?> = _publishPrompt

    private fun publishToDocDistribution(message: ChatMessage) {
        val doc = message as? ChatMessage.Document ?: run {
            _messages.tryEmit(R.string.only_media_can_be_distributed)
            return
        }
        if (!docDistribution.canPublish()) {
            _messages.tryEmit(R.string.you_dont_have_distribution_rights_on_this_unit)
            return
        }
        _publishPrompt.value = doc
    }

    fun dismissPublishPrompt() {
        _publishPrompt.value = null
    }

    fun confirmPublish() {
        val doc = _publishPrompt.value ?: return
        _publishPrompt.value = null

        viewModelScope.launch {
            val result = docDistribution.publishFromTool(
                folderName = DocDistFolder.CALL_SHEET,
                createdAt = doc.createdAt,
                fileName = doc.fileName,
                media = doc.remoteKey.orEmpty(),
                thumbnail = doc.thumbnail,
                fileSize = doc.rawSizeBytes,
                caption = null,
            )
            _messages.tryEmit(
                if (result) R.string.dd_publish_success else R.string.dd_publish_failed,
            )
        }
    }

    // -- history --------------------------------------------------------------

    private val _historyRequest = MutableStateFlow<ChatHistoryRequest?>(null)
    val historyRequest: StateFlow<ChatHistoryRequest?> = _historyRequest

    /**
     * Opens the unit's history.
     *
     * Reached from the unit strip's overflow. It matters most on Call Sheet, where every
     * replace pushes the previous sheet here, but any unit accumulates deleted messages.
     */
    fun openHistory() {
        val unit = selectedUnit.value ?: return
        player.stop()
        _historyRequest.value = ChatHistoryRequest(
            module = ChatModule.HOME,
            scopeId = unit.id,
            title = unit.name,
        )
    }

    fun dismissHistory() {
        _historyRequest.value = null
    }

    // -- library (media / docs / links) ---------------------------------------

    private val _libraryOpen = MutableStateFlow(false)
    val libraryOpen: StateFlow<Boolean> = _libraryOpen

    fun dismissLibrary() {
        _libraryOpen.value = false
    }

    /** Every message in the open thread, roots and batch members alike. */
    fun libraryMessages(): List<ChatMessage> = feed.value
        .filterIsInstance<ChatFeedItem.Post>()
        .flatMap { listOf(it.root) + it.batch }

    // -- image reply ----------------------------------------------------------

    /**
     * The image being annotated, once it is on disk.
     *
     * Image Reply is not a reply in the comment sense — v2 downloads the original, opens
     * the annotation editor on it, and posts the marked-up copy as a **new message**. That
     * is what makes it useful on set: circling the thing you are asking about.
     */
    private val _imageReplySource = MutableStateFlow<PickedMedia?>(null)
    val imageReplySource: StateFlow<PickedMedia?> = _imageReplySource

    private fun startImageReply(message: ChatMessage) {
        val key = (message as? ChatMessage.Image)?.remoteKey ?: return
        val name = key.substringAfterLast('/')

        viewModelScope.launch {
            // Cached most of the time — you have just been looking at it.
            val file = downloader.awaitFile(key, name, ChatModule.HOME.key) ?: run {
                _messages.tryEmit(R.string.something_went_wrong)
                return@launch
            }

            _imageReplySource.value = PickedMedia(
                uri = android.net.Uri.fromFile(file),
                localPath = file.absolutePath,
                fileName = name,
                mimeType = "image/*",
                sizeBytes = file.length(),
            )
        }
    }

    fun dismissImageReply() {
        _imageReplySource.value = null
    }

    /** The annotated copy goes through the ordinary upload path — it is just a photo. */
    fun onImageReplyEdited(edited: PickedMedia) {
        _imageReplySource.value = null
        onAttachmentPicked(AttachmentResult.Media(listOf(edited)))
    }

    // -- location -------------------------------------------------------------

    /** A `geo:`/maps URL for the screen to hand to a maps app. */
    private val _locationRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val locationRequests: SharedFlow<String> = _locationRequests

    fun openLocation(message: ChatMessage.Location) {
        if (!message.hasCoordinates) return
        // v2's `getLocationLink` — a maps.google.com query, which every maps app on the
        // device offers to handle, rather than a `geo:` URI only Google Maps answers.
        viewModelScope.launch {
            _locationRequests.emit(
                "https://maps.google.com/?q=${message.latitude},${message.longitude}",
            )
        }
    }

    // -- print ----------------------------------------------------------------

    /** A cached file the screen hands to Android's print service. */
    private val _printRequests = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val printRequests: SharedFlow<File> = _printRequests

    private fun printRequest(message: ChatMessage) {
        val key = message.attachmentKey() ?: return
        val name = (message as? ChatMessage.Document)?.fileName ?: key.substringAfterLast('/')
        viewModelScope.launch {
            downloader.awaitFile(key, name, ChatModule.HOME.key)?.let { _printRequests.emit(it) }
        }
    }

    // -- copy, edit -----------------------------------------------------------

    /** Emitted for the screen to put on the clipboard; a ViewModel holds no Context. */
    private val _clipboardRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val clipboardRequests: SharedFlow<String> = _clipboardRequests

    private fun copyToClipboard(message: ChatMessage) {
        val text = message.editableBody()
        if (text.isBlank()) return
        viewModelScope.launch { _clipboardRequests.emit(text) }
    }

    /**
     * What is being edited.
     *
     * One shape for both, because the composer behaves identically either way — only the
     * endpoint differs, and that is decided once on save.
     */
    data class EditTarget(
        val messageServerId: String? = null,
        val parentServerId: String? = null,
        val commentId: String? = null,
        val body: String,
    )

    private val _editing = MutableStateFlow<EditTarget?>(null)
    val editing: StateFlow<EditTarget?> = _editing

    private fun startEdit(target: EditTarget) {
        // Editing and replying both own the composer; entering one cancels the other rather
        // than leaving the send button ambiguous.
        _replyingTo.value = null
        _editing.value = target
    }

    fun cancelEdit() {
        _editing.value = null
    }

    fun saveEdit(text: String) {
        val target = _editing.value ?: return
        _editing.value = null
        if (text.isBlank()) return

        val project = session.activeProject.value ?: return
        val unitId = selectedUnitId.value ?: return

        viewModelScope.launch {
            if (target.commentId != null && target.parentServerId != null) {
                chatRepository.editReply(
                    module = ChatModule.HOME,
                    project = project,
                    scopeId = unitId,
                    chatServerId = target.parentServerId,
                    commentId = target.commentId,
                    text = text,
                )
            } else if (target.messageServerId != null) {
                chatRepository.editMessage(
                    module = ChatModule.HOME,
                    project = project,
                    scopeId = unitId,
                    chatServerId = target.messageServerId,
                    text = text,
                )
            }
        }
    }

    private fun ChatMessage.serverId(): String? = feed.value
        .filterIsInstance<ChatFeedItem.Post>()
        .firstOrNull { it.root.id == id }
        ?.rootServerId

    /** The text an edit starts from — a caption for media, the body for text. */
    private fun ChatMessage.editableBody(): String = when (this) {
        is ChatMessage.Text -> body
        is ChatMessage.Image -> caption.orEmpty()
        else -> ""
    }

    // -- read by user ---------------------------------------------------------

    private val _readByRequest = MutableStateFlow<ReadByRequest?>(null)
    val readByRequest: StateFlow<ReadByRequest?> = _readByRequest

    private fun openReadBy(message: ChatMessage) {
        val unit = selectedUnit.value ?: return
        // The endpoint keys off the server id; an unsent message has nobody to have read it.
        val serverId = feed.value
            .filterIsInstance<ChatFeedItem.Post>()
            .firstOrNull { it.root.id == message.id }
            ?.rootServerId
            ?: return

        player.stop()
        _readByRequest.value = ReadByRequest(
            module = ChatModule.HOME,
            scopeId = unit.id,
            messageId = serverId,
            canPost = unit.canPost,
        )
    }

    fun dismissReadBy() {
        _readByRequest.value = null
    }

    // -- multi-select (delete, share) -----------------------------------------

    private val _selection = MutableStateFlow<ChatSelectionState?>(null)
    val selection: StateFlow<ChatSelectionState?> = _selection

    /** Raised when Delete is confirmed on a selection; the screen shows the dialog. */
    private val _deleteConfirmation = MutableStateFlow<Set<String>?>(null)
    val deleteConfirmation: StateFlow<Set<String>?> = _deleteConfirmation

    /**
     * Files gathered for the OS chooser.
     *
     * A one-shot event, not state: an Intent must fire exactly once, and a StateFlow
     * replayed after a rotation would open the chooser a second time.
     */
    private val _shareRequests = MutableSharedFlow<ShareRequest>(extraBufferCapacity = 1)
    val shareRequests: SharedFlow<ShareRequest> = _shareRequests

    /**
     * Exposed so the screen can turn cached files into FileProvider URIs.
     *
     * The cache is not ViewModel state, but the alternative — injecting it into every chat
     * screen separately — puts the same dependency in two places for no gain.
     */
    val mediaCacheForShare get() = mediaCache

    private fun startSelection(mode: ChatSelectionMode, message: ChatMessage) {
        // Playback and a selection both own the thread's attention; v2 stops the player
        // here too rather than leaving a clip talking over a delete confirmation.
        player.stop()
        _selection.value = ChatSelectionState(mode, setOf(message.id))
    }

    fun toggleSelection(message: ChatMessage) {
        val current = _selection.value ?: return

        // You can share anyone's message, but you can only delete your own — v2 refuses
        // the selection with this exact line rather than letting it be made and failing
        // at the server.
        if (current.mode == ChatSelectionMode.DELETE &&
            !message.isOwn &&
            !currentUser.isAdmin
        ) {
            _messages.tryEmit(R.string.you_cannot_delete_other_user_message)
            return
        }

        // Emptying the selection ends it, so there is no bar offering an action over
        // nothing — v2 answers that state with a "choose at least one" snackbar instead.
        val next = current.toggle(message.id)
        _selection.value = if (next.count == 0) null else next
    }

    fun cancelSelection() {
        _selection.value = null
    }

    fun confirmSelection() {
        val current = _selection.value ?: return
        if (current.mode == ChatSelectionMode.DELETE && !networkMonitor.isOnline.value) {
            _messages.tryEmit(R.string.internet_connection_lost_please_check)
            return
        }
        when (current.mode) {
            ChatSelectionMode.DELETE -> _deleteConfirmation.value = current.selectedIds
            ChatSelectionMode.SHARE -> shareSelected(current.selectedIds)
        }
    }

    fun dismissDeleteConfirmation() {
        _deleteConfirmation.value = null
    }

    fun confirmDelete() {
        val ids = _deleteConfirmation.value ?: return
        _deleteConfirmation.value = null
        _selection.value = null

        val project = session.activeProject.value ?: return
        val unitId = selectedUnitId.value ?: return

        // Deleting takes server ids; an unconfirmed message has none and there is nothing
        // on the server to remove.
        val serverIds = feed.value
            .filterIsInstance<ChatFeedItem.Post>()
            .filter { it.root.id in ids }
            .mapNotNull { it.rootServerId }
        if (serverIds.isEmpty()) return

        viewModelScope.launch {
            chatRepository.deleteMessages(ChatModule.HOME, project, unitId, serverIds)
        }
    }

    /**
     * Pulls every selected attachment into the cache, then hands the lot to the chooser.
     *
     * Text and captions travel as one block of text, attachments as file URIs — the same
     * split v2 makes, because `ACTION_SEND_MULTIPLE` carries streams and a single extra
     * text, not one payload per item.
     */
    private fun shareSelected(ids: Set<String>) {
        val messages = feed.value
            .filterIsInstance<ChatFeedItem.Post>()
            .filter { it.root.id in ids }
            .map { it.root }
        _selection.value = null
        if (messages.isEmpty()) return

        viewModelScope.launch {
            val texts = mutableListOf<String>()
            val files = mutableListOf<File>()

            messages.forEach { message ->
                when (message) {
                    is ChatMessage.Text -> message.body.takeIf { it.isNotBlank() }
                        ?.let(texts::add)

                    // No coordinates are kept on the message, so a pin shares as the place
                    // it names rather than as a maps link.
                    is ChatMessage.Location -> texts.add(
                        listOf(message.placeName, message.address)
                            .filter { it.isNotBlank() }
                            .joinToString("\n"),
                    )

                    else -> {
                        (message as? ChatMessage.Image)?.caption
                            ?.takeIf { it.isNotBlank() }?.let(texts::add)
                        val key = message.attachmentKey() ?: return@forEach
                        val name = (message as? ChatMessage.Document)?.fileName
                            ?: key.substringAfterLast('/')
                        // Already cached most of the time — a shared file has usually
                        // just been looked at.
                        downloader.awaitFile(key, name, ChatModule.HOME.key)
                            ?.let(files::add)
                    }
                }
            }

            if (texts.isEmpty() && files.isEmpty()) return@launch
            _shareRequests.emit(ShareRequest(files = files, text = texts.joinToString("\n\n")))
        }
    }

    /** Pulls the file into the cache; the viewer and any external app read it from there. */
    private fun saveToDevice(message: ChatMessage) {
        val key = message.attachmentKey() ?: return
        val name = (message as? ChatMessage.Document)?.fileName ?: key.substringAfterLast('/')

        viewModelScope.launch {
            // A Call Sheet PDF is saved in its watermarked form, never the raw file.
            val effectiveKey = watermarkedKeyIfCallSheet(message) ?: key
            downloader.download(effectiveKey, name, ChatModule.HOME.key)
        }
    }

    /**
     * The watermarked object key when this is a Call Sheet PDF, else null.
     *
     * Only PDFs and only in Call Sheet, matching v2 — the stamping service handles PDFs,
     * and asking for a watermark on a photo just fails.
     */
    private suspend fun watermarkedKeyIfCallSheet(message: ChatMessage): String? {
        if (selectedUnit.value?.kind != HomeUnitKind.CALL_SHEET) return null
        val doc = message as? ChatMessage.Document ?: return null
        if (!doc.fileName.endsWith(".pdf", ignoreCase = true)) return null
        val serverId = message.serverId() ?: return null
        return chatRepository.watermarkedKey(ChatModule.HOME, serverId)
    }

    /**
     * Opens a document outside the app.
     *
     * Images and video open in the in-app viewer; a PDF or a spreadsheet does not, because
     * rendering every office format is not something a chat client should own. The file is
     * cached first so the external app gets a real, readable file.
     */
    fun openDocument(message: ChatMessage.Document) {
        val key = message.remoteKey ?: return
        viewModelScope.launch {
            val effectiveKey = watermarkedKeyIfCallSheet(message) ?: key
            val file = downloader.awaitFile(effectiveKey, message.fileName, ChatModule.HOME.key)
                ?: run {
                    _messages.tryEmit(R.string.something_went_wrong)
                    return@launch
                }
            _openExternally.emit(file)
        }
    }

    private val _openExternally = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val openExternally: SharedFlow<File> = _openExternally

    /** Share from the media viewer — one file, straight to the chooser. */
    fun share(item: ViewableMedia) {
        val key = item.remoteKey ?: return
        viewModelScope.launch {
            val file = downloader.awaitFile(key, item.fileName, ChatModule.HOME.key) ?: return@launch
            _shareRequests.emit(
                ShareRequest(files = listOf(file), text = item.caption.orEmpty()),
            )
        }
    }

    /** Called by the screen on resume/pause and when the tab is entered or left. */
    fun onScreenVisibilityChanged(visible: Boolean) {
        screenVisible.value = visible
    }

    /**
     * Keeps the open unit read while the user is looking at it.
     *
     * Marking read once on open is not enough: messages keep arriving, and each one raises
     * a badge on a thread the user is already reading. This watches the unit's own badge
     * and clears it whenever it becomes non-zero **while the screen is visible**, so the
     * count only ever appears for units the user is not in.
     *
     * Driven off the badge rather than off message arrivals because that is the thing we
     * actually want to be zero — including notifications that arrive by push, which never
     * pass through the chat socket at all.
     */
    private fun observeAutoReadWhileOpen() {
        viewModelScope.launch {
            combine(
                screenVisible,
                selectedUnitId,
                selectedUnitKind,
                session.activeProject,
            ) { visible, unitId, kind, project ->
                // The calendar is left out for the same reason as [onUnitSelected]: having
                // it open is not an answer to the invitations inside it.
                val isCalendar = kind == HomeUnitKind.CALENDAR

                if (visible && unitId != null && project != null && !isCalendar) {
                    BadgeKey.homeUnit(project.projectId, unitId)
                } else {
                    null
                }
            }
                .flatMapLatest { key ->
                    if (key == null) flowOf(null) else badgeManager.observeUnder(key).map { it to key }
                }
                .collect { pair ->
                    val (count, key) = pair ?: return@collect
                    if (count > 0) badgeSync.markRead(key)
                }
        }
    }

    fun onUnitSelected(unit: ChatUnit) {
        if (selectedUnitId.value == unit.id) return
        selectedUnitId.value = unit.id
        selectedUnitKind.value = unit.kind
        // Editing and replying belong to the thread you were in, not the one you opened.
        _editing.value = null
        _replyingTo.value = null
        _selection.value = null
        loadDraft(unit.id)
        captureUnreadAnchor(unit.id)
        refreshThread(unit.id)
        // Opening a unit is what marks it read, matching v2.
        //
        // Through the coordinator, not `badgeManager.clear`: clearing the counter alone
        // leaves the stored notifications unread, so the next `recomputeBadges` — which
        // runs on every sync — puts the badge straight back, and the user's other devices
        // never learn the unit was read.
        //
        // **Except the calendar.** v2 guards both of its read paths with
        // `if (identifier != CALENDER_IDENTIFIER)`, and skipping is the only thing that
        // works: the read is echoed to the server as a read of the whole unit, so even a
        // locally-filtered read comes back from the server having cleared the invitations
        // anyway. An invitation is answered by accepting or declining it, not by looking
        // at the calendar.
        if (unit.kind == HomeUnitKind.CALENDAR) return

        session.activeProject.value?.projectId?.let { projectId ->
            badgeSync.markRead(BadgeKey.homeUnit(projectId, unit.id))
        }
    }

    /** Called once the strip has units, so the first one opens without a tap. */
    fun selectFirstIfNeeded() {
        if (selectedUnitId.value != null) return
        units.value.firstOrNull()?.let(::onUnitSelected)
    }

    /**
     * Freezes the read watermark for the unit about to be shown.
     *
     * Read from what is already in Realm rather than from the server: the badge count is
     * per-unit and the newest **stored** message is exactly what the previous visit left
     * behind. A unit opened for the first time has no watermark, so no divider is drawn.
     */
    private fun captureUnreadAnchor(unitId: String) {
        val projectId = session.activeProject.value?.projectId ?: return
        val hadUnread = units.value.firstOrNull { it.id == unitId }?.badgeCount ?: 0
        unreadAnchor.value = if (hadUnread <= 0) {
            0L
        } else {
            chatRepository.lastReadWatermark(ChatModule.HOME, projectId, unitId, hadUnread)
        }
    }

    fun refresh() {
        selectedUnitId.value?.let(::refreshThread)
    }

    /**
     * Refetches the crew when a message referenced someone we do not know.
     *
     * Debounced through the same single refresh the socket bridge uses, so opening a
     * thread full of unknown senders costs one request.
     */
    private fun resolveUnknownAuthors() {
        val missing = com.zillit.zillitapp.core.directory.UserLookup.drainUnresolved()
        if (missing.isEmpty()) return
        val projectId = session.activeProject.value?.projectId ?: return
        viewModelScope.launch { directory.refreshUsers(projectId) }
    }

    private fun refreshThread(unitId: String) {
        val projectId = session.activeProject.value?.projectId ?: return
        _isLoadingThread.value = true
        viewModelScope.launch {
            chatRepository.fetchPage(ChatModule.HOME, projectId, unitId, ChatPage.NEXT)
            _isLoadingThread.value = false
            // After the page has been mapped, any sender it could not name is known.
            resolveUnknownAuthors()
        }
    }

    /** Older page, for scrolling back through history. */
    fun loadOlder() {
        val projectId = session.activeProject.value?.projectId ?: return
        val unitId = selectedUnitId.value ?: return
        viewModelScope.launch {
            chatRepository.fetchPage(ChatModule.HOME, projectId, unitId, ChatPage.PREVIOUS)
        }
    }

    // -- in-thread search -----------------------------------------------------

    private val _search = MutableStateFlow<ChatSearchState?>(null)
    val search: StateFlow<ChatSearchState?> = _search

    fun openSearch() {
        // Searching and selecting both take over the same chrome; opening one ends the
        // other rather than stacking two modes on the thread.
        _selection.value = null
        _search.value = ChatSearchState()
    }

    fun closeSearch() {
        _search.value = null
    }

    /**
     * Matches as you type, over the messages already loaded.
     *
     * Local, not a server search: the thread is in Realm and matching a substring over it
     * is instant, whereas v2's endpoint-free approach searched only the adapter's current
     * page anyway. Paging further back widens the search naturally.
     */
    fun onSearchQueryChanged(query: String) {
        val matches = if (query.isBlank()) {
            emptyList()
        } else {
            feed.value
                .filterIsInstance<ChatFeedItem.Post>()
                .filter { it.root.matches(query) }
                .map { it.id }
        }

        _search.value = ChatSearchState(
            query = query,
            matchIds = matches,
            // Lands on the newest match, because that is where the thread already is.
            currentIndex = if (matches.isEmpty()) -1 else matches.lastIndex,
        )
    }

    fun searchNext() {
        _search.value = _search.value?.next()
    }

    fun searchPrevious() {
        _search.value = _search.value?.previous()
    }

    private fun ChatMessage.matches(query: String): Boolean {
        val needle = query.trim()
        return when (this) {
            is ChatMessage.Text -> body.contains(needle, ignoreCase = true)
            is ChatMessage.Image -> caption.orEmpty().contains(needle, ignoreCase = true)
            is ChatMessage.Document -> fileName.contains(needle, ignoreCase = true)
            else -> false
        } || author.name.contains(needle, ignoreCase = true)
    }

    // -- draft ----------------------------------------------------------------

    /** The saved draft for the open unit; null until one has been loaded. */
    private val _draft = MutableStateFlow<String?>(null)
    val draft: StateFlow<String?> = _draft

    /**
     * Remembers what is typed, per unit.
     *
     * Written on every keystroke rather than on unit switch: the app can be killed at any
     * moment on a low-memory device, and "it was there when I came back" has to survive
     * that, not just an orderly navigation.
     */
    fun onDraftChanged(text: String) {
        val project = session.activeProject.value ?: return
        val unitId = selectedUnitId.value ?: return
        viewModelScope.launch { drafts.set(project.projectId, unitId, text) }
    }

    private fun loadDraft(unitId: String) {
        val project = session.activeProject.value ?: return
        viewModelScope.launch { _draft.value = drafts.get(project.projectId, unitId) }
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        val project = session.activeProject.value ?: return
        val unitId = selectedUnitId.value ?: return
        viewModelScope.launch { drafts.clear(project.projectId, unitId) }

        // One send path for both: a reply is the same queued row with a parent id, so it
        // inherits offline queueing, retry and project scoping without a second branch.
        val replyTo = _replyingTo.value?.let { target ->
            feed.value
                .filterIsInstance<ChatFeedItem.Post>()
                .firstOrNull { it.root.id == target.id }
                ?.rootServerId
        }
        _replyingTo.value = null

        viewModelScope.launch {
            chatRepository.enqueueText(
                module = ChatModule.HOME,
                project = project,
                scopeId = unitId,
                senderId = currentUser.userId ?: project.userId,
                text = text,
                replyToServerId = replyTo,
            )
        }
    }

    /**
     * The message being replied to.
     *
     * Reply targets the composer rather than a box inside the bubble: a reply often wants
     * an attachment or an emoji, and duplicating those controls per message would be both
     * cluttered and a second place for them to drift.
     */
    private val _replyingTo = MutableStateFlow<ChatMessage?>(null)
    val replyingTo: StateFlow<ChatMessage?> = _replyingTo

    /** Started from the bubble's Reply link or the long-press menu — same state either way. */
    fun startReply(parentServerId: String) {
        _replyingTo.value = feed.value
            .filterIsInstance<ChatFeedItem.Post>()
            .firstOrNull { it.rootServerId == parentServerId }
            ?.root
    }

    fun startReply(message: ChatMessage) {
        _replyingTo.value = message
    }

    fun cancelReply() {
        _replyingTo.value = null
    }

    // -- forward ------------------------------------------------------------

    private val _forwarding = MutableStateFlow<ChatMessage?>(null)
    val forwarding: StateFlow<ChatMessage?> = _forwarding

    /** Projects a message can be forwarded into — every project the user belongs to. */
    val forwardProjects: StateFlow<List<ForwardProject>> =
        projectRepository.observeProjects()
            .map { projects ->
                projects.map { ForwardProject(it.id, it.userId, it.name) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _forwardProject = MutableStateFlow<ForwardProject?>(null)
    val forwardProject: StateFlow<ForwardProject?> = _forwardProject

    /** Null while the chosen project's destinations are being fetched. */
    private val _forwardTargets = MutableStateFlow<List<ForwardTarget>?>(null)
    val forwardTargets: StateFlow<List<ForwardTarget>?> = _forwardTargets

    fun currentProjectId(): String? = session.activeProject.value?.projectId

    /**
     * Loads the chosen project's destinations.
     *
     * Fetched with **that project's** context: another project's units are only in Realm
     * if it has been opened, and querying without the override silently returns the active
     * project's list instead.
     */
    fun onForwardProjectSelected(project: ForwardProject) {
        _forwardProject.value = project
        _forwardTargets.value = null

        // Call Sheet holds documents, so a photo, a clip or a voice note has nowhere to
        // land there — the same test v2 applies while building the forward list.
        val message = _forwarding.value
        val callSheetAllowed = message !is ChatMessage.Image &&
            message !is ChatMessage.Video &&
            message !is ChatMessage.Voice

        viewModelScope.launch {
            val target = SessionStore.ActiveProject(project.id, project.userId)
            directory.refreshUnits(project.id, project = target)

            _forwardTargets.value = directory.observeUnits(project.id).first()
                // The source unit is excluded only within the source project — forwarding
                // into the thread the message already sits in is never the intent.
                .filterNot { project.id == currentProjectId() && it.unitId == selectedUnitId.value }
                .filter { unit ->
                    when (HomeUnitKind.from(unit.identifier)) {
                        // Calendar is a module, not a thread; there is nothing to post into.
                        HomeUnitKind.CALENDAR -> false
                        HomeUnitKind.CALL_SHEET -> callSheetAllowed
                        HomeUnitKind.BULLETIN -> true
                    }
                }
                .map {
                    ForwardTarget(
                        id = it.unitId,
                        name = it.unitName,
                        canPost = it.postingAccess,
                        isCallSheet = HomeUnitKind.from(it.identifier) == HomeUnitKind.CALL_SHEET,
                    )
                }
        }
    }

    fun dismissForward() {
        _forwarding.value = null
        _forwardProject.value = null
        _forwardTargets.value = null
    }

    /** A forward held back by Call Sheet's replace decision. */
    private data class PendingForward(
        val message: ChatMessage,
        val project: ForwardProject,
        val targetUnitIds: List<String>,
    )

    private var pendingForward: PendingForward? = null

    /**
     * Forwards to the selected unit.
     *
     * Text is re-sent as a new message; an attachment is re-posted against the **same**
     * object key, so nothing is uploaded twice. Landing in a Call Sheet asks the same
     * continuation-or-new question an upload does, because the effect is the same.
     */
    fun forwardTo(projectId: String, targetUnitIds: List<String>) {
        val message = _forwarding.value ?: return
        val chosen = _forwardProject.value
            ?: forwardProjects.value.firstOrNull { it.id == projectId }
            ?: return

        val intoCallSheet = _forwardTargets.value
            ?.any { it.id in targetUnitIds && it.isCallSheet } == true

        dismissForward()

        if (intoCallSheet) {
            // Asked unconditionally, as v2 does on the forward path: the target project's
            // messages may not be in Realm at all, so "is there anything to replace?"
            // cannot be answered locally.
            pendingForward = PendingForward(message, chosen, targetUnitIds)
            _callSheetPrompt.value = CallSheetPrompt.ContinuationOrNew
            return
        }

        performForward(message, chosen, targetUnitIds, replacePrevious = null)
    }

    private fun performForward(
        message: ChatMessage,
        chosen: ForwardProject,
        targetUnitIds: List<String>,
        replacePrevious: Boolean?,
    ) {
        // The TARGET project's context, not the active one — the post must be signed with
        // the project it lands in.
        val project = SessionStore.ActiveProject(chosen.id, chosen.userId)
        val senderId = chosen.userId
        val remoteKey = message.attachmentKey()

        viewModelScope.launch {
            targetUnitIds.forEach { unitId ->
                if (remoteKey.isNullOrBlank()) {
                    chatRepository.enqueueText(
                        module = ChatModule.HOME,
                        project = project,
                        scopeId = unitId,
                        senderId = senderId,
                        text = (message as? ChatMessage.Text)?.body.orEmpty(),
                    )
                } else {
                    uploadQueue.enqueueForward(
                        projectId = project.projectId,
                        userId = senderId,
                        module = ChatModule.HOME.key,
                        targetScopeId = unitId,
                        remoteKey = remoteKey,
                        thumbnailKey = message.thumbnailKey().orEmpty(),
                        fileName = (message as? ChatMessage.Document)?.fileName
                            ?: remoteKey.substringAfterLast('/'),
                        mimeType = remoteKey.mimeFromExtension(),
                        sizeBytes = 0,
                        durationMs = 0,
                        width = 0,
                        height = 0,
                        caption = (message as? ChatMessage.Image)?.caption.orEmpty(),
                        replacePreviousChats = replacePrevious,
                    )
                }
            }
        }
    }

    private fun ChatMessage.attachmentKey(): String? = when (this) {
        is ChatMessage.Image -> remoteKey
        is ChatMessage.Video -> remoteKey
        is ChatMessage.Voice -> remoteKey
        is ChatMessage.Document -> remoteKey
        else -> null
    }

    private fun ChatMessage.thumbnailKey(): String? = when (this) {
        is ChatMessage.Image -> thumbnail
        is ChatMessage.Video -> thumbnail
        is ChatMessage.Document -> thumbnail
        else -> null
    }

    /**
     * Mime from the object key's extension.
     *
     * The forwarded message carries no local file to inspect, and the type only has to be
     * good enough for the backend's category mapping.
     */
    private fun String.mimeFromExtension(): String = when (substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    /**
     * A failed row, and how many of its batch are also waiting.
     *
     * Non-null raises the "this file / all N" prompt v2 shows; a lone failure retries
     * straight away without asking, because there is no choice to make.
     */
    data class RetryPrompt(val messageId: String, val siblingIds: List<String>)

    private val _retryPrompt = MutableStateFlow<RetryPrompt?>(null)
    val retryPrompt: StateFlow<RetryPrompt?> = _retryPrompt

    fun retry(message: ChatMessage) {
        if (!networkMonitor.isOnline.value) {
            _messages.tryEmit(R.string.internet_connection_lost_please_check)
            return
        }

        val siblings = uploadQueue.pendingSiblings(message.id)
        if (siblings.size > 1) {
            _retryPrompt.value = RetryPrompt(message.id, siblings)
            return
        }
        retryOne(message.id)
    }

    fun dismissRetryPrompt() {
        _retryPrompt.value = null
    }

    fun retryThisFile() {
        val prompt = _retryPrompt.value ?: return
        _retryPrompt.value = null
        retryOne(prompt.messageId)
    }

    fun retryWholeBatch() {
        val prompt = _retryPrompt.value ?: return
        _retryPrompt.value = null
        viewModelScope.launch { uploadQueue.retryAll(prompt.siblingIds) }
    }

    private fun retryOne(messageId: String) {
        val project = session.activeProject.value ?: return
        val unitId = selectedUnitId.value ?: return
        viewModelScope.launch {
            chatRepository.retry(ChatModule.HOME, project, unitId, messageId)
        }
    }

    /**
     * Call Sheet's replace decision, before the picker opens.
     *
     * @return true when the picker may open immediately. False means a confirmation is
     *   showing and the picker will be opened from its result instead.
     */
    fun onAttachRequested(): Boolean {
        val unit = selectedUnit.value ?: return true
        if (unit.kind != HomeUnitKind.CALL_SHEET) return true

        val project = session.activeProject.value ?: return true
        val hasExisting = chatRepository.hasAnyMessage(ChatModule.HOME, project.projectId, unit.id)

        // Only asks when there is something a new upload would replace — the first ever
        // call sheet needs no confirmation.
        if (!hasExisting) return true

        _callSheetPrompt.value = CallSheetPrompt.ContinuationOrNew
        return false
    }

    fun onCallSheetContinuation() = onCallSheetDecision(replacePrevious = false)

    fun onCallSheetNew() {
        _callSheetPrompt.value = CallSheetPrompt.ConfirmReplace
    }

    fun onCallSheetReplaceConfirmed() = onCallSheetDecision(replacePrevious = true)

    /**
     * The same two dialogs guard two different actions, so the answer is routed by which
     * one asked: a held-back forward runs now, otherwise the picker opens.
     */
    private fun onCallSheetDecision(replacePrevious: Boolean) {
        val pending = pendingForward
        if (pending != null) {
            pendingForward = null
            _callSheetPrompt.value = null
            performForward(pending.message, pending.project, pending.targetUnitIds, replacePrevious)
            return
        }
        _callSheetPrompt.value = CallSheetPrompt.OpenPicker(replacePrevious)
    }

    fun dismissCallSheetPrompt() {
        pendingForward = null
        _callSheetPrompt.value = null
    }

    /** Files picked — queued for upload, then posted. Both survive the app closing. */
    fun onAttachmentPicked(result: AttachmentResult, replacePrevious: Boolean? = null) {
        _callSheetPrompt.value = null

        val media = (result as? AttachmentResult.Media)?.items.orEmpty()
        if (media.isEmpty()) return

        val project = session.activeProject.value ?: return
        val unit = selectedUnit.value ?: return

        viewModelScope.launch {
            val senderId = currentUser.userId ?: project.userId

            uploadQueue.enqueue(
                projectId = project.projectId,
                userId = senderId,
                module = ChatModule.HOME.key,
                scopeId = unit.id,
                folder = HOME_FOLDER,
                media = media,
                replacePreviousChats = replacePrevious,
                // v2 auto-distributes call sheets to Document Distribution.
                isDistributeAutomatic = unit.kind == HomeUnitKind.CALL_SHEET,
                moduleName = unit.name,
                onRowsQueued = { ids ->
                    // Mirrored into the thread straight away so each file appears with a
                    // progress bar instead of only showing up once the upload finishes.
                    // One stamp for the batch, so the files render as a single post.
                    val batchStamp = System.currentTimeMillis()
                    viewModelScope.launch {
                        ids.forEachIndexed { index, id ->
                            val item = media.getOrNull(index) ?: return@forEachIndexed
                            chatRepository.createPendingAttachmentRow(
                                module = ChatModule.HOME,
                                projectId = project.projectId,
                                scopeId = unit.id,
                                uniqueId = id,
                                senderId = senderId,
                                caption = item.caption,
                                messageType = item.chatMessageType,
                                localPath = item.localPath,
                                fileName = item.fileName,
                                sizeBytes = item.sizeBytes,
                                durationMs = item.durationMs,
                                messageGroup = batchStamp,
                            )
                        }
                    }
                },
            )
        }
    }

    private fun unitsWithBadges(projectId: String): Flow<List<ChatUnit>> =
        combine(
            directory.observeUnits(projectId),
            calendarBadges.counts,
            badgeManager.observeGrouped(
                prefix = BadgeKey.section(session.activeProject.value?.projectId.orEmpty(), BadgeSection.HOME),
                by = BadgeAxis.UNIT,
                // Calendar invitations sit under Home but are not a unit's unread — v2
                // filters them out of the strip for the same reason. They are counted by
                // the calendar's own pending/expired pill and cleared by answering them,
                // not by opening the unit they arrived in.
                excludeTool = BadgeTool.CALENDAR,
            ),
        ) { units, calendar, badges ->
            units
                // view_access false means the unit exists but this user may not read it.
                // Filtered here so the rule lives in one place regardless of the caller.
                .filter { it.viewAccess }
                .map { unit ->
                    ChatUnit(
                        id = unit.unitId,
                        // A server label KEY; resolved at render by the strip, so a
                        // language change needs no reload.
                        name = unit.unitName,
                        // The Calendar chip counts invitations, not unread messages —
                        // v2 special-cases the same unit (`identifier == CALENDER_IDENTIFIER`
                        // → `calendarPending + calendarExpired`). Everything else counts what
                        // is unread inside it.
                        badgeCount = when (HomeUnitKind.from(unit.identifier)) {
                            HomeUnitKind.CALENDAR -> calendar.total
                            else -> badges[unit.unitId] ?: 0
                        },
                        canPost = unit.postingAccess,
                        canDownload = unit.downloadAccess,
                        isPrivate = unit.privateUnit,
                        kind = HomeUnitKind.from(unit.identifier),
                    )
                }
        }

    override fun onCleared() {
        super.onCleared()
        // A recording left running would hold the mic after the screen is gone.
        recordingTicker?.cancel()
        if (recorder.isRecording) recorder.cancel()
        player.stop()
    }

    private companion object {
        /** Marks the synthetic id [ChatFeedMapper] gives a reply with no server id yet. */
        const val REPLY_PENDING_MARKER = "-reply-"

        /** v2's `Constants.DIFFERENCE_IN_HOURS` — 30 minutes, despite the name. */
        const val EDIT_WINDOW_MS = 30 * 60 * 1000L

        /** Storage folder segment, matching v2's `HOME_FOLDER`. */
        const val HOME_FOLDER = "home"

        /** Fast enough for a level meter to look live. */
        const val RECORDING_TICK_MS = 100L
    }
}

/** Where the Call Sheet replace confirmation has got to. */
sealed interface CallSheetPrompt {
    /** Step one: continuation of the existing call sheet, or a new one? */
    data object ContinuationOrNew : CallSheetPrompt

    /** Step two: replacing sends everything currently posted to History. */
    data object ConfirmReplace : CallSheetPrompt

    /** Decision made — open the picker with this replace flag. */
    data class OpenPicker(val replacePrevious: Boolean) : CallSheetPrompt
}
