package com.zillit.zillitapp.feature.notification.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.notification.AppNotification
import com.zillit.zillitapp.core.badge.BadgeKey
import com.zillit.zillitapp.core.notification.BadgeSyncCoordinator
import com.zillit.zillitapp.core.notification.NotificationDto
import com.zillit.zillitapp.core.notification.NotificationFeedRepository
import com.zillit.zillitapp.core.notification.NotificationRepository
import com.zillit.zillitapp.core.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the notification list is showing. */
data class NotificationUiState(
    val isLoading: Boolean = false,
    val notifications: List<AppNotification> = emptyList(),
)

@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val repository: NotificationFeedRepository,
    private val localStore: NotificationRepository,
    private val badgeSync: BadgeSyncCoordinator,
    private val session: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationUiState(isLoading = true))
    val state: StateFlow<NotificationUiState> = _state

    /** Raised so the screen can confirm before a delete actually happens. */
    private val _pendingDelete = MutableStateFlow<AppNotification?>(null)
    val pendingDelete: StateFlow<AppNotification?> = _pendingDelete

    private val _pendingDeleteAll = MutableStateFlow(false)
    val pendingDeleteAll: StateFlow<Boolean> = _pendingDeleteAll

    private val rows = mutableListOf<NotificationDto>()

    /** True once a page came back empty, so scrolling stops asking. */
    private var exhausted = false

    init {
        refresh()

        // Opening the list is what marks it read — the same rule the chat units use, and
        // the same one v2 applies here. Goes through the coordinator so the local store,
        // the badge and the server all agree; clearing the badge alone would be undone by
        // the next recompute.
        //
        // Once, on open. It used to run after **every** fetch, which meant each page of
        // older notifications re-read the whole project and emitted another read.
        markProjectRead()

        // Realtime. A notification arriving while this screen is open lands in the list
        // without the user pulling to refresh. `refresh` marks read on the way through,
        // which is right — they are looking straight at it.
        viewModelScope.launch {
            repository.incoming().collect { refresh() }
        }
    }

    fun refresh() {
        rows.clear()
        exhausted = false
        _state.value = NotificationUiState(isLoading = true)
        fetch(before = 0)
    }

    fun loadOlder() {
        if (exhausted || _state.value.isLoading) return
        fetch(before = rows.minOfOrNull { it.created ?: 0 } ?: return)
    }

    private fun fetch(before: Long) {
        _state.value = _state.value.copy(isLoading = true)

        viewModelScope.launch {
            val page = repository.page(before)
            if (page.isEmpty()) exhausted = true

            // De-duplicated by id: a cursor page can repeat its boundary row.
            val known = rows.mapNotNullTo(mutableSetOf()) { it.id }
            rows += page.filter { it.id !in known }

            _state.value = NotificationUiState(isLoading = false, notifications = rows.toUi())
        }
    }

    fun askDelete(notification: AppNotification) {
        _pendingDelete.value = notification
    }

    fun dismissDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val target = _pendingDelete.value ?: return
        _pendingDelete.value = null

        // Read before the row goes: some notifications are addressed by their reference
        // rather than their own id, and removing first would lose it.
        val dto = rows.firstOrNull { it.id == target.id }

        // Removed from the list first so the row disappears on the same frame; the refetch
        // on failure puts it back rather than leaving a lie on screen.
        rows.removeAll { it.id == target.id }
        _state.value = _state.value.copy(notifications = rows.toUi())

        viewModelScope.launch {
            if (repository.delete(target.id, dto?.referenceId)) {
                // A deleted notification can no longer be unread, so the local row goes
                // too — otherwise recomputeBadges keeps counting something the user can
                // never open.
                dto?.uuid?.takeIf { it.isNotBlank() }?.let { localStore.remove(it) }
            } else {
                refresh()
            }
        }
    }

    fun askDeleteAll() {
        if (_state.value.notifications.isEmpty()) return
        _pendingDeleteAll.value = true
    }

    fun dismissDeleteAll() {
        _pendingDeleteAll.value = false
    }

    fun confirmDeleteAll() {
        _pendingDeleteAll.value = false

        rows.clear()
        _state.value = _state.value.copy(notifications = emptyList())

        viewModelScope.launch {
            if (repository.deleteAll()) {
                markProjectRead()
            } else {
                // The socket was down or the server refused. Putting the list back is the
                // honest outcome — an empty screen would claim a delete that never landed.
                refresh()
            }
        }
    }

    /**
     * Opening the list is what marks it read — the same rule the chat units use, and the
     * one v2 applies here. The prefix is the whole project, which is what the Zillit logo
     * badge counts.
     */
    private fun markProjectRead() {
        session.activeProject.value?.projectId?.let { badgeSync.markRead(BadgeKey.project(it)) }
    }

    private fun List<NotificationDto>.toUi(): List<AppNotification> =
        sortedByDescending { it.created ?: 0 }
            .mapNotNull { dto ->
                val id = dto.id ?: return@mapNotNull null
                val (title, body) = repository.readableText(dto)
                AppNotification(
                    id = id,
                    title = title,
                    body = body,
                    createdAt = dto.created ?: 0,
                    isRead = dto.messageRead == true,
                )
            }
}