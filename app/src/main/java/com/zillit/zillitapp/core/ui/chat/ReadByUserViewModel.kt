package com.zillit.zillitapp.core.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.chat.data.ChatModule
import com.zillit.zillitapp.core.chat.data.ReadByEntryDto
import com.zillit.zillitapp.core.chat.data.ReadByRepository
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.directory.ProjectUser
import com.zillit.zillitapp.core.session.CurrentUserStore
import com.zillit.zillitapp.core.common.toDayAtTimeLabel
import com.zillit.zillitapp.core.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What to ask about. Enough for any chat surface — the module supplies the endpoint. */
data class ReadByRequest(
    val module: ChatModule,
    /** Unit or group id — the scope Notify sends into. */
    val scopeId: String,
    val messageId: String,
    /** Set to ask about a reply rather than the message. */
    val commentId: String? = null,
    /** Whether the viewer may post here; Notify is hidden otherwise, as in v2. */
    val canPost: Boolean = false,
)

/**
 * Backs [ReadByUserScreen] for every chat.
 *
 * Names are resolved from the local directory, not from the response: the endpoint returns
 * bare user ids, and the crew list is already in Realm. That also means a renamed user
 * shows correctly here without the read-by endpoint knowing anything about it.
 */
@HiltViewModel
class ReadByUserViewModel @Inject constructor(
    private val repository: ReadByRepository,
    private val socketBridge: com.zillit.zillitapp.core.chat.ChatSocketBridge,
    private val directory: ProjectDirectory,
    private val session: SessionStore,
    private val currentUser: CurrentUserStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ReadByUiState())
    val state: StateFlow<ReadByUiState> = _state

    private var request: ReadByRequest? = null

    init {
        // Someone reading the message while this screen is open moves them from one list
        // to the other; without this the page is a snapshot that quietly goes stale.
        viewModelScope.launch {
            socketBridge.readByUpdates.collect { messageId ->
                request?.takeIf { it.messageId == messageId }?.let { load(it) }
            }
        }
    }

    fun load(request: ReadByRequest) {
        this.request = request
        _state.value = ReadByUiState(isLoading = true, canNotify = request.canPost)

        viewModelScope.launch {
            val projectId = session.activeProject.value?.projectId
            val users = if (projectId == null) {
                emptyList()
            } else {
                directory.observeUsers(projectId).first()
            }.associateBy { it.userId }

            val data = repository.fetch(request.module, request.messageId, request.commentId)
            // Yourself is always "read" and says nothing useful, so v2 drops you from both
            // lists and this does the same.
            val self = currentUser.userId

            _state.value = ReadByUiState(
                isLoading = false,
                // Most recent first: on a big unit the useful question is who has seen it
                // since you last looked.
                read = data?.readBy.orEmpty()
                    .filter { it.userId != self }
                    .sortedByDescending { it.readTime ?: 0 }
                    .map { it.toPerson(users, isRead = true) },
                unread = data?.unreadBy.orEmpty()
                    .filter { it.userId != self }
                    .sortedByDescending { it.delivered ?: 0 }
                    .map { it.toPerson(users, isRead = false) },
                canNotify = request.canPost,
            )
        }
    }

    fun notifyUnread() {
        val request = request ?: return
        viewModelScope.launch {
            repository.notifyUnread(
                module = request.module,
                scopeId = request.scopeId,
                messageId = request.messageId,
                commentId = request.commentId,
            )
        }
    }

    /**
     * Formats the time but not the label.
     *
     * "Read"/"Not Delivered" stay [ReadByPerson] flags for the screen to render through
     * `stringResource` — a ViewModel that builds the visible sentence cannot be
     * translated, and every string in v3 has to be.
     */
    private fun ReadByEntryDto.toPerson(
        users: Map<String, ProjectUser>,
        isRead: Boolean,
    ): ReadByPerson {
        val user = users[userId]
        val stamp = if (isRead) readTime ?: 0 else delivered ?: 0

        return ReadByPerson(
            userId = userId.orEmpty(),
            name = user?.displayName?.takeIf { it.isNotBlank() } ?: userId.orEmpty(),
            role = user?.designationName?.takeIf { it.isNotBlank() },
            time = stamp.toDayAtTimeLabel(),
            isRead = isRead,
            // 0 means it never reached the device — a different state from "delivered but
            // unread", and the crew chases the two differently.
            isDelivered = (delivered ?: 0) != 0L,
        )
    }

}
