package com.zillit.zillitapp.feature.calendar.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.calendar.CalendarBadges
import com.zillit.zillitapp.core.calendar.CalendarRealtime
import com.zillit.zillitapp.core.calendar.sync.CalendarSync
import com.zillit.zillitapp.core.calendar.data.CalendarRepository
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.calendar.model.EditScope
import com.zillit.zillitapp.core.calendar.model.Invitation
import com.zillit.zillitapp.core.calendar.model.InvitationStatus
import com.zillit.zillitapp.core.calendar.model.InvitationStatusResolver
import com.zillit.zillitapp.core.calendar.model.OccurrenceStatuses
import com.zillit.zillitapp.core.common.toApiDate
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.labels.resolveLabel
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EventDetailState(
    val event: CalendarEvent? = null,
    val isLoading: Boolean = true,
    val isActing: Boolean = false,
    val error: String? = null,
    val isCreator: Boolean = false,
    val isAdmin: Boolean = false,
    /** My own invitation, if I am an invitee at all. */
    val myInvitation: Invitation? = null,
    /** My status for **this occurrence**, which can differ from the series'. */
    val resolvedStatus: InvitationStatus? = null,
    /** Every invitee, each with their status resolved for this occurrence. */
    val invitees: List<Invitation> = emptyList(),
    /**
     * Who made the event, already resolved to "Name (Designation)".
     *
     * Resolved here rather than in the sheet because the directory is a repository, and a
     * composable that reaches into one cannot be previewed or tested.
     */
    val creatorName: String = "",
) {
    /** Editing and deleting are the creator's, or an admin's. */
    val canManage: Boolean
        get() = (isCreator || isAdmin) &&
            event != null &&
            !event.isCancelled &&
            !event.isSeriesExpired() &&
            !event.isFromBoxSchedule
}

/** What the sheet shows after an action finishes. */
sealed interface EventActionResult {
    data class Accepted(val scope: EditScope) : EventActionResult
    data class Declined(val scope: EditScope) : EventActionResult
    data class Deleted(val scope: EditScope) : EventActionResult
    data class Failed(val message: String?) : EventActionResult
}

/**
 * Backs the event detail sheet.
 *
 * The load is two requests, not one: the event itself, plus — only when it recurs — the
 * per-occurrence statuses. Without the second, a user who declined *this Tuesday* while
 * accepting the series sees "Accepted" on the Tuesday they turned down.
 */
@HiltViewModel
class EventDetailViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val directory: ProjectDirectory,
    private val labelRepository: com.zillit.zillitapp.core.labels.LabelRepository,
    private val session: SessionStore,
    private val calendarBadges: CalendarBadges,
    private val realtime: CalendarRealtime,
    private val calendarSync: CalendarSync,
) : ViewModel() {

    private val _state = MutableStateFlow(EventDetailState())
    val state: StateFlow<EventDetailState> = _state.asStateFlow()

    private val _results = MutableSharedFlow<EventActionResult>(extraBufferCapacity = 1)
    val results: SharedFlow<EventActionResult> = _results.asSharedFlow()

    /** Emitted after any successful action, so the list behind the sheet can refresh. */
    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    private var occurrenceStatuses: OccurrenceStatuses? = null
    private var occurrenceStart: Long? = null

    /**
     * The id this sheet was opened with.
     *
     * Kept rather than re-read off the loaded event: an expanded occurrence comes back
     * with no `_id` at all, so reloading from the response would request an empty id.
     */
    private var loadedEventId: String = ""

    /**
     * @param occurrenceStartMs which occurrence is being viewed. The list passes the row's
     *   own start, so a series opened from its third occurrence answers for that one.
     */
    fun load(eventId: String, occurrenceStartMs: Long? = null) {
        occurrenceStart = occurrenceStartMs
        loadedEventId = eventId

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            when (val result = repository.event(eventId, occurrenceStartMs?.toApiDate())) {
                is ApiResult.Success -> {
                    val event = result.data
                    if (event == null) {
                        _state.value = _state.value.copy(isLoading = false, error = null)
                        return@launch
                    }

                    // Only recurring events have per-occurrence answers. A failure here is
                    // not fatal — the series-level status is still a usable answer, just a
                    // less precise one — so it is logged rather than surfaced.
                    occurrenceStatuses = if (event.isRecurring) {
                        when (val statuses =
                            repository.occurrenceStatuses(event.effectiveMasterEventId)) {
                            is ApiResult.Success -> statuses.data
                            is ApiResult.Failure -> {
                                ZillitLog.w(TAG, "Occurrence statuses failed: ${statuses.error.message}")
                                null
                            }
                        }
                    } else {
                        null
                    }

                    applyEvent(event)
                }

                is ApiResult.Failure -> _state.value = _state.value.copy(
                    isLoading = false,
                    error = result.error.message,
                )
            }
        }
    }

    fun accept(scope: EditScope) = act(
        scope = scope,
        onSuccess = EventActionResult.Accepted(scope),
    ) { eventId, occurrence -> repository.accept(eventId, scope, occurrence) }

    fun decline(scope: EditScope, reason: String) = act(
        scope = scope,
        onSuccess = EventActionResult.Declined(scope),
    ) { eventId, occurrence -> repository.reject(eventId, scope, occurrence, reason) }

    /**
     * Deletes the event, or one occurrence of it.
     *
     * Addressed by the **master** id plus the occurrence date, like every other
     * per-occurrence call — an expanded occurrence has no row of its own to delete.
     */
    fun delete(scope: EditScope) {
        val event = _state.value.event ?: return

        // Suppresses the echo of this very action; the sheet refreshes itself below.
        realtime.markSelfAction()

        viewModelScope.launch {
            _state.value = _state.value.copy(isActing = true)

            val result = repository.delete(
                eventId = event.effectiveMasterEventId,
                scope = scope,
                occurrenceDate = occurrenceStart.takeIf { scope != EditScope.ALL },
            )

            _state.value = _state.value.copy(isActing = false)

            when (result) {
                is ApiResult.Success -> {
                    // Whole series gone means the mirrored row goes; one occurrence
                    // cancelled is an edit to the series as the device calendar sees it.
                    if (scope == EditScope.ALL) {
                        calendarSync.onEventDeleted(event.effectiveMasterEventId)
                    } else {
                        calendarSync.onEventChanged(event.effectiveMasterEventId)
                    }
                    _results.tryEmit(EventActionResult.Deleted(scope))
                    _changed.tryEmit(Unit)
                }

                is ApiResult.Failure -> {
                    ZillitLog.w(TAG, "Delete failed: ${result.error.message}")
                    _results.tryEmit(EventActionResult.Failed(result.error.message))
                }
            }
        }
    }

    private fun act(
        scope: EditScope,
        onSuccess: EventActionResult,
        request: suspend (eventId: String, occurrence: Long?) -> ApiResult<Unit>,
    ) {
        val event = _state.value.event ?: return

        realtime.markSelfAction()

        viewModelScope.launch {
            _state.value = _state.value.copy(isActing = true)

            // Always the master id: an occurrence has no row of its own to address, and
            // answering by its id either fails or silently answers for the series.
            val target = event.effectiveMasterEventId
            val occurrence = occurrenceStart.takeIf { scope != EditScope.ALL }

            when (val result = request(target, occurrence)) {
                is ApiResult.Success -> {
                    // Answering the invitation is reading it: the notification that raised
                    // the badge was asking for exactly this decision.
                    calendarBadges.onInvitationActioned(target)

                    // Reload before clearing the spinner, so the sheet never shows the
                    // old status for a frame after the action succeeded.
                    reload()
                    _state.value = _state.value.copy(isActing = false)
                    _results.tryEmit(onSuccess)
                    _changed.tryEmit(Unit)
                }

                is ApiResult.Failure -> {
                    _state.value = _state.value.copy(isActing = false)
                    _results.tryEmit(EventActionResult.Failed(result.error.message))
                }
            }
        }
    }

    private suspend fun reload() {
        when (val result = repository.event(loadedEventId, occurrenceStart?.toApiDate())) {
            is ApiResult.Success -> {
                val fresh = result.data ?: return
                if (fresh.isRecurring) {
                    // Re-read, not reused: the answer just given is exactly what changed.
                    (repository.occurrenceStatuses(fresh.effectiveMasterEventId)
                        as? ApiResult.Success)?.let { occurrenceStatuses = it.data }
                }
                applyEvent(fresh)
            }

            is ApiResult.Failure ->
                ZillitLog.w(TAG, "Reload after action failed: ${result.error.message}")
        }
    }

    private fun applyEvent(event: CalendarEvent) {
        val userId = session.activeProject.value?.userId
        val myInvitation = event.invitations.firstOrNull { it.userId == userId }
        val occurrence = occurrenceStart ?: event.startDatetime

        val resolved = InvitationStatusResolver.resolve(
            invitation = myInvitation,
            occurrenceDate = occurrence,
            occurrenceStatuses = occurrenceStatuses,
            isRecurring = event.isRecurring,
        )

        // Each attendee resolved through their **own** overrides only. Passing the
        // occurrence-statuses payload here would paint my answers onto everyone else.
        val invitees = if (event.isRecurring) {
            event.invitations.map { invitation ->
                val status = InvitationStatusResolver.resolveForInvitation(invitation, occurrence)
                if (status == invitation.status) invitation else invitation.copy(status = status)
            }
        } else {
            event.invitations
        }

        _state.value = EventDetailState(
            event = event,
            isLoading = false,
            isCreator = event.creatorId == userId,
            isAdmin = directory.findUser(userId.orEmpty())?.isAdmin == true,
            myInvitation = myInvitation,
            resolvedStatus = resolved,
            invitees = invitees.map { it.withResolvedName() },
            creatorName = creatorLabel(event.creatorId),
        )
    }

    /**
     * Fills in a name the server did not echo.
     *
     * External invitees have no directory entry, so they fall back to their email — which
     * is the only thing that identifies them.
     */
    /**
     * "Name (Designation)" — the same shape every other screen uses for a person.
     *
     * The designation is a server label key, so it is resolved through the dictionary; an
     * unresolved key would render as `assistant_to_producer_label` in front of the user.
     */
    private fun creatorLabel(creatorId: String?): String {
        val user = creatorId?.takeIf { it.isNotBlank() }?.let(directory::findUser) ?: return ""
        val designation = user.designationName?.takeIf { it.isNotBlank() }?.let { labelRepository.labels.value.resolveLabel(it) }
        return if (designation.isNullOrBlank()) user.displayName else "${user.displayName} ($designation)"
    }

    private fun Invitation.withResolvedName(): Invitation {
        if (name.isNotBlank()) return this
        val resolved = directory.findUser(userId)?.displayName
        return copy(name = resolved?.takeIf { it.isNotBlank() } ?: email.orEmpty())
    }

    private companion object {
        const val TAG = "EventDetailViewModel"
    }
}
