package com.zillit.zillitapp.feature.calendar.ui.form

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.calendar.CalendarRealtime
import com.zillit.zillitapp.core.calendar.sync.CalendarSync
import com.zillit.zillitapp.core.calendar.data.CalendarFormPayload
import com.zillit.zillitapp.core.calendar.data.CalendarFormPayload.durationMinutes
import com.zillit.zillitapp.core.calendar.data.CalendarRepository
import com.zillit.zillitapp.core.calendar.data.EventFormError
import com.zillit.zillitapp.core.calendar.data.EventFormInput
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.calendar.model.CallType
import com.zillit.zillitapp.core.calendar.model.EditScope
import com.zillit.zillitapp.core.calendar.model.EventType
import com.zillit.zillitapp.core.calendar.model.Timezone
import com.zillit.zillitapp.core.common.toApiDate
import com.zillit.zillitapp.core.directory.ProjectDirectory
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

data class EventFormUiState(
    val input: EventFormInput = EventFormInput(),
    val isEditMode: Boolean = false,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    /** Which occurrence is being edited, for a scoped edit. */
    val occurrenceStartMs: Long? = null,
    val isRecurringSeries: Boolean = false,
    val timezones: List<Timezone> = emptyList(),
)

/** One-shot outcomes the screen reacts to but should not re-show on recomposition. */
sealed interface EventFormEvent {
    data class Invalid(val error: EventFormError) : EventFormEvent

    /**
     * A pick was silently corrected — the end pulled forward to keep the minimum duration,
     * or a past start nudged to now. Shown so the change is never invisible.
     */
    data class Adjusted(val reason: AdjustReason) : EventFormEvent
    data class Saved(val isEdit: Boolean) : EventFormEvent
    data class Failed(val message: String?) : EventFormEvent
}

enum class AdjustReason { END_AFTER_START, START_NOT_IN_PAST }

/**
 * Backs the create and edit form.
 *
 * Setters do more than store: picking a start that leaves less than the minimum duration
 * pushes the end out rather than waiting for validation to reject it on submit, and the
 * screen is told so it can say what happened. Silently accepting an invalid pair and
 * failing later is how a form makes a user guess.
 */
@HiltViewModel
class EventFormViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val directory: ProjectDirectory,
    private val session: SessionStore,
    private val realtime: CalendarRealtime,
    private val calendarSync: CalendarSync,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(EventFormUiState())
    val state: StateFlow<EventFormUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EventFormEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<EventFormEvent> = _events.asSharedFlow()

    private var editingEventId: String? = null

    init {
        // The date the user was looking at when they tapped +, so the form opens on it
        // rather than always on today.
        savedStateHandle.get<Long>(ARG_SELECTED_DATE)
            ?.takeIf { it > 0 }
            ?.let { millis ->
                val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                update { copy(date = date) }
            }

        savedStateHandle.get<String>(ARG_EVENT_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { id ->
                loadForEdit(id, savedStateHandle.get<Long>(ARG_OCCURRENCE)?.takeIf { it > 0 })
            }

        loadTimezones()
    }

    // ------------------------------------------------------------------ setters

    fun setTitle(value: String) = update { copy(title = value) }

    fun setDescription(value: String) = update { copy(description = value) }

    fun setDate(value: LocalDate) = update { copy(date = value) }

    fun setFullDay(value: Boolean) = update { copy(isFullDay = value) }

    /**
     * Sets the start, keeping the end at least the minimum duration later.
     *
     * The end is only pushed when the pair would otherwise be invalid, and the gap the
     * user had is preserved when it was longer than the minimum.
     */
    fun setStartTime(value: LocalTime) {
        val current = _state.value.input
        val previousGap = current.durationMinutes()
        val gap = maxOf(previousGap, CalendarFormPayload.MIN_DURATION_MINUTES)
        val newEnd = value.plusMinutes(gap.toLong())

        update { copy(startTime = value, endTime = newEnd) }

        if (previousGap < CalendarFormPayload.MIN_DURATION_MINUTES) {
            _events.tryEmit(EventFormEvent.Adjusted(AdjustReason.END_AFTER_START))
        }
    }

    /** Rejects an end that would leave less than the minimum, pushing it out instead. */
    fun setEndTime(value: LocalTime) {
        val candidate = _state.value.input.copy(endTime = value)

        if (candidate.durationMinutes() < CalendarFormPayload.MIN_DURATION_MINUTES) {
            val corrected = _state.value.input.startTime
                .plusMinutes(CalendarFormPayload.MIN_DURATION_MINUTES.toLong())
            update { copy(endTime = corrected) }
            _events.tryEmit(EventFormEvent.Adjusted(AdjustReason.END_AFTER_START))
        } else {
            update { copy(endTime = value) }
        }
    }

    fun setTimezone(value: String) = update { copy(timezone = value) }

    /**
     * Members or personal.
     *
     * Switching to personal clears the invitee list rather than keeping it hidden — a
     * personal event has no invitees, and carrying them silently would send them back the
     * moment the user switched again.
     */
    fun setEventType(value: EventType) = update {
        if (value == EventType.PERSONAL) {
            copy(type = value, inviteeIds = emptyList(), externalEmails = emptyList())
        } else {
            copy(type = value)
        }
    }

    fun setCallType(value: CallType) = update { copy(callType = value) }

    fun setLocation(description: String, lat: Double? = null, long: Double? = null) = update {
        copy(locationDescription = description, locationLat = lat, locationLong = long)
    }

    fun setColor(value: String) = update { copy(color = value) }

    fun setNotify(minutes: Int) = update { copy(notifyMinutes = minutes) }

    /** Picking a frequency fills in a sensible end date, which the user can then change. */
    fun setRecurrenceFrequency(frequency: Int) = update {
        copy(
            recurrenceFrequency = frequency,
            recurrenceEnd = if (frequency == CalendarFormPayload.FREQ_NONE) {
                null
            } else {
                recurrenceEnd ?: CalendarFormPayload.defaultRecurrenceEnd(frequency, date)
            },
            selectedDays = if (frequency == CalendarFormPayload.FREQ_WEEKLY) selectedDays else emptyList(),
        )
    }

    fun setRecurrenceEnd(value: LocalDate?) = update { copy(recurrenceEnd = value) }

    fun toggleWeekday(day: Int) = update {
        copy(selectedDays = if (day in selectedDays) selectedDays - day else selectedDays + day)
    }

    fun setInvitees(ids: List<String>) = update { copy(inviteeIds = ids) }

    fun setExternalEmails(emails: List<String>) = update { copy(externalEmails = emails) }

    fun setExcludeCreator(value: Boolean) = update { copy(excludeCreator = value) }

    // ------------------------------------------------------------------ submit

    /**
     * @param scope only meaningful when editing a recurring series; the screen asks for it
     *   first and passes what the user chose.
     */
    fun submit(scope: EditScope = EditScope.ALL) {
        val current = _state.value
        val input = current.input

        CalendarFormPayload.validate(input, isCreateMode = !current.isEditMode)?.let { error ->
            _events.tryEmit(EventFormEvent.Invalid(error))
            return
        }

        val payload = CalendarFormPayload.build(input) { userId ->
            directory.findUser(userId)?.displayName.orEmpty()
        }

        // The save echoes back over the socket; the screen that made it is about to close
        // and the calendar behind it refreshes on its own.
        realtime.markSelfAction()

        viewModelScope.launch {
            _state.value = _state.value.copy(isSubmitting = true)

            val eventId = editingEventId
            val result = if (current.isEditMode && eventId != null) {
                repository.editEvent(
                    eventId = eventId,
                    payload = CalendarFormPayload.forEdit(payload, scope, current.occurrenceStartMs),
                )
            } else {
                repository.createEvent(payload)
            }

            _state.value = _state.value.copy(isSubmitting = false)

            when (result) {
                is ApiResult.Success -> {
                    // Mirrored explicitly rather than through the realtime stream: that
                    // stream deliberately ignores this device's own echo, and the user's
                    // own events are exactly the ones the device calendar wants.
                    result.data?.effectiveMasterEventId?.let { calendarSync.onEventChanged(it) }
                    _events.tryEmit(EventFormEvent.Saved(current.isEditMode))
                }
                is ApiResult.Failure -> {
                    ZillitLog.w(TAG, "Save failed: ${result.error.message}")
                    _events.tryEmit(EventFormEvent.Failed(result.error.message))
                }
            }
        }
    }

    // ------------------------------------------------------------------ loading

    private fun loadForEdit(eventId: String, occurrenceStartMs: Long?) {
        editingEventId = eventId

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, isEditMode = true)

            // Loaded for the occurrence being edited, not the master: a date that was
            // previously edited on its own has its own title and times, and showing the
            // master's would silently revert them on save.
            when (val result = repository.event(eventId, occurrenceStartMs?.toApiDate())) {
                is ApiResult.Success -> {
                    val event = result.data
                    if (event == null) {
                        _state.value = _state.value.copy(isLoading = false)
                        return@launch
                    }
                    _state.value = _state.value.copy(
                        input = event.toFormInput(),
                        isEditMode = true,
                        isLoading = false,
                        occurrenceStartMs = occurrenceStartMs ?: event.startDatetime,
                        isRecurringSeries = event.isRecurring,
                    )
                }

                is ApiResult.Failure -> {
                    ZillitLog.w(TAG, "Load for edit failed: ${result.error.message}")
                    _state.value = _state.value.copy(isLoading = false)
                    _events.tryEmit(EventFormEvent.Failed(result.error.message))
                }
            }
        }
    }

    /**
     * The zone list for the picker.
     *
     * A failure is not surfaced: the form already has a working default — the device's
     * zone — and a missing list only costs the ability to change it.
     */
    private fun loadTimezones() {
        viewModelScope.launch {
            (repository.timezones() as? ApiResult.Success)?.let {
                _state.value = _state.value.copy(timezones = it.data)
            }
        }
    }

    private fun CalendarEvent.toFormInput(): EventFormInput {
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
        val start = Instant.ofEpochMilli(startDatetime).atZone(zone)
        val end = Instant.ofEpochMilli(endDatetime).atZone(zone)
        val me = session.activeProject.value?.userId

        return EventFormInput(
            title = title,
            description = description,
            date = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endTime = end.toLocalTime(),
            isFullDay = isFullDay,
            timezone = timezone.ifBlank { ZoneId.systemDefault().id },
            type = type,
            recurrenceFrequency = recurrence.frequency.value,
            selectedDays = recurrence.byDay,
            recurrenceEnd = recurrence.until
                ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() },
            locationDescription = locationDescription,
            locationLat = locationLat,
            locationLong = locationLong,
            callType = callType,
            color = color,
            notifyMinutes = notifyMinutes,
            // The creator is not one of their own invitees, so they are filtered out —
            // otherwise saving an edit would invite them to their own event.
            inviteeIds = invitations.filterNot { it.isExternal }
                .map { it.userId }
                .filterNot { it == me },
            externalEmails = invitations.filter { it.isExternal }.mapNotNull { it.email },
            excludeCreator = createUserExclude,
        )
    }

    private inline fun update(block: EventFormInput.() -> EventFormInput) {
        _state.value = _state.value.copy(input = _state.value.input.block())
    }

    companion object {
        const val ARG_EVENT_ID = "eventId"
        const val ARG_OCCURRENCE = "occurrenceStartMs"
        const val ARG_SELECTED_DATE = "selectedDateMs"

        private const val TAG = "EventFormViewModel"
    }
}
