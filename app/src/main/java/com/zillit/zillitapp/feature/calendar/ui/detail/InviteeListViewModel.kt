package com.zillit.zillitapp.feature.calendar.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.calendar.data.CalendarRepository
import com.zillit.zillitapp.core.calendar.model.Invitation
import com.zillit.zillitapp.core.calendar.model.InvitationStatusResolver
import com.zillit.zillitapp.core.common.toApiDate
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InviteeListState(
    val invitees: List<Invitation> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * The full invitee list for one event.
 *
 * Fetched rather than read off the event: an event response caps its embedded invitations,
 * so a 40-person call arrives with a count and a handful of names. This is the endpoint
 * that returns all of them.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class InviteeListViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val directory: ProjectDirectory,
) : ViewModel() {

    private val _state = MutableStateFlow(InviteeListState())
    val state: StateFlow<InviteeListState> = _state.asStateFlow()

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private var eventId: String = ""
    private var occurrenceStartMs: Long? = null

    init {
        // Debounced so typing a name is one request at the end, not one per keystroke.
        // `drop(1)` skips the initial empty value, which `load` already fetches.
        viewModelScope.launch {
            _search
                .drop(1)
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { fetch(it) }
        }
    }

    fun load(eventId: String, occurrenceStartMs: Long?) {
        this.eventId = eventId
        this.occurrenceStartMs = occurrenceStartMs
        fetch(_search.value)
    }

    fun onSearchChanged(query: String) {
        _search.value = query
    }

    private fun fetch(query: String) {
        if (eventId.isBlank()) return

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)

            when (
                val result = repository.eventInvitations(
                    eventId = eventId,
                    search = query,
                    occurrenceDate = occurrenceStartMs?.toApiDate(),
                )
            ) {
                is ApiResult.Success -> {
                    val occurrence = occurrenceStartMs
                    _state.value = InviteeListState(
                        invitees = result.data.map { it.resolved(occurrence) },
                        isLoading = false,
                    )
                }

                is ApiResult.Failure -> {
                    ZillitLog.w(TAG, "Invitee list failed: ${result.error.message}")
                    // Whatever is already listed stays; a failed search should not empty
                    // a list the user is reading.
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
        }
    }

    /**
     * Each person's status for this occurrence, resolved through **their own** overrides,
     * and their name filled in from the directory when the server did not echo one.
     */
    private fun Invitation.resolved(occurrence: Long?): Invitation {
        val status = occurrence
            ?.let { InvitationStatusResolver.resolveForInvitation(this, it) }
            ?: status

        val resolvedName = name.ifBlank {
            directory.findUser(userId)?.displayName?.takeIf { it.isNotBlank() }
                ?: email.orEmpty()
        }

        return copy(status = status, name = resolvedName)
    }

    private companion object {
        const val TAG = "InviteeListViewModel"
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
