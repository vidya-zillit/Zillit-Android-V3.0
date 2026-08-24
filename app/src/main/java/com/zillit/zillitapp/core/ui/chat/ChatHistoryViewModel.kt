package com.zillit.zillitapp.core.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.chat.data.ChatHistoryRepository
import com.zillit.zillitapp.core.chat.data.ChatModule
import com.zillit.zillitapp.core.database.entity.ChatMessageEntity
import com.zillit.zillitapp.core.storage.AttachmentDownloader
import com.zillit.zillitapp.core.ui.chat.model.ChatFeedItem
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.feature.home.ui.toHistoryFeed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Backs [ChatHistoryScreen] for every surface.
 *
 * Holds its page in memory rather than Realm — see [ChatHistoryRepository] for why — so the
 * live thread's cache is untouched by opening history.
 */
@HiltViewModel
class ChatHistoryViewModel @Inject constructor(
    private val repository: ChatHistoryRepository,
    private val downloader: AttachmentDownloader,
    /** Exposed so the screen can turn a cached file into a FileProvider URI. */
    val mediaCache: com.zillit.zillitapp.core.storage.MediaCache,
) : ViewModel() {

    private val rows = mutableListOf<ChatMessageEntity>()

    private val _feed = MutableStateFlow<List<ChatFeedItem>>(emptyList())
    val feed: StateFlow<List<ChatFeedItem>> = _feed

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _longPressed = MutableStateFlow<ChatMessage?>(null)
    val longPressed: StateFlow<ChatMessage?> = _longPressed

    private val _printRequests = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val printRequests: SharedFlow<File> = _printRequests

    /** A cached file to hand to the OS viewer. History is read-only, so opening is all it does. */
    private val _openRequests = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val openRequests: SharedFlow<File> = _openRequests

    private var request: ChatHistoryRequest? = null

    /** True once a page came back short, so paging stops asking. */
    private var exhausted = false

    /** Zero-indexed, as v2's `CURRENT_PAGE` is. */
    private var page = 0

    fun load(request: ChatHistoryRequest) {
        if (this.request?.scopeId == request.scopeId &&
            this.request?.episode == request.episode
        ) {
            return
        }

        this.request = request
        rows.clear()
        exhausted = false
        page = 0
        _feed.value = emptyList()
        fetch(before = 0)
    }

    fun loadOlder() {
        if (exhausted || _isLoading.value) return
        page += 1
        // Paged by `archived` — when the message was superseded — which the repository
        // parks in `updated`. v2's `getLastMessageTime()` reads the same field; `created`
        // is when it was originally posted and does not order the history.
        fetch(before = rows.minOfOrNull { it.updated } ?: return)
    }

    private fun fetch(before: Long) {
        val request = request ?: return
        _isLoading.value = true

        viewModelScope.launch {
            val fetched = repository.page(
                module = request.module,
                scopeId = request.scopeId,
                before = before,
                page = page,
                episode = request.episode,
            )

            if (fetched.isEmpty()) exhausted = true

            // De-duplicated by server id: a page boundary can repeat the cursor row.
            val known = rows.mapTo(mutableSetOf()) { it.serverId }
            rows += fetched.filter { it.serverId !in known }
            // Oldest first, by when each was superseded — the order the thread reads in.
            rows.sortBy { it.updated }

            _feed.value = rows.toHistoryFeed()
            _isLoading.value = false
        }
    }

    fun onLongPressed(message: ChatMessage) {
        _longPressed.value = message
    }

    fun dismissLongPress() {
        _longPressed.value = null
    }

    /**
     * The reduced menu history offers.
     *
     * Everything that changes a message or continues a conversation is off: these messages
     * were replaced or deleted, so replying to, forwarding, editing or deleting them again
     * is meaningless. Save and Print remain because the **file** is still real — which is
     * usually why someone opened history at all.
     */
    fun optionsFor(): ChatMessageOptions = ChatMessageOptions(
        isOwnMessage = false,
        isConfirmed = true,
        isTextMessage = false,
        isLocationMessage = false,
        isImageMessage = false,
        hasAttachment = true,
        hasBody = false,
        isAdmin = false,
        canPost = false,
        canDownload = true,
        isWithinEditWindow = false,
        isCallSheet = false,
        isTranslationEnabled = false,
        isAlreadyTranslated = false,
    )

    fun onOptionSelected(option: ChatMessageOption) {
        val message = _longPressed.value ?: return
        _longPressed.value = null

        when (option) {
            ChatMessageOption.Save -> withCachedFile(message) { /* cached is saved */ }
            ChatMessageOption.Print -> withCachedFile(message) { _printRequests.tryEmit(it) }
            else -> Unit
        }
    }

    /**
     * Tapping an attachment opens it.
     *
     * The whole reason to come here is usually to read the call sheet that got replaced,
     * so the file is downloaded and handed to whatever app can display it.
     */
    fun onMediaOpened(message: ChatMessage) {
        withCachedFile(message) { _openRequests.tryEmit(it) }
    }

    private fun withCachedFile(message: ChatMessage, onReady: (File) -> Unit) {
        val key = when (message) {
            is ChatMessage.Image -> message.remoteKey
            is ChatMessage.Video -> message.remoteKey
            is ChatMessage.Document -> message.remoteKey
            is ChatMessage.Voice -> message.remoteKey
            else -> null
        } ?: return

        val name = (message as? ChatMessage.Document)?.fileName ?: key.substringAfterLast('/')
        val module = request?.module ?: ChatModule.HOME

        viewModelScope.launch {
            downloader.awaitFile(key, name, module.key)?.let(onReady)
        }
    }
}
