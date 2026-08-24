package com.zillit.zillitapp.feature.calendar.ui.lists

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.CalendarBadgeCounts
import com.zillit.zillitapp.core.calendar.CalendarBadges
import com.zillit.zillitapp.core.calendar.CalendarRealtime
import com.zillit.zillitapp.core.calendar.sync.CalendarSync
import com.zillit.zillitapp.core.calendar.data.CalendarRepository
import com.zillit.zillitapp.core.calendar.data.Page
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.calendar.model.EditScope
import com.zillit.zillitapp.core.calendar.model.InvitationStatus
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.feature.calendar.ui.EventCardOptions
import com.zillit.zillitapp.feature.calendar.ui.OccurrencesState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Which of the four event lists this is.
 *
 * One enum rather than four screens: the lists differ in the call they make and the tabs
 * they offer, and in nothing else. v2 built them as four fragments with four ViewModels and
 * four adapters, which is why search behaved differently on each one.
 */
enum class EventListKind {
    RECEIVED,
    CREATED,
    DECLINED,
    JOIN_CALL,
    ;

    @get:StringRes
    val titleRes: Int
        get() = when (this) {
            RECEIVED -> R.string.calendar_received_events
            CREATED -> R.string.calendar_created_events
            DECLINED -> R.string.calendar_declined_events
            JOIN_CALL -> R.string.calendar_join_call_events
        }

    @get:StringRes
    val emptyRes: Int
        get() = when (this) {
            RECEIVED -> R.string.calendar_no_invitations
            JOIN_CALL -> R.string.calendar_no_join_calls
            else -> R.string.calendar_no_events
        }

    /** Tabs, in order. Empty for a list that has none. */
    val tabs: List<EventListTab>
        get() = when (this) {
            RECEIVED -> listOf(
                EventListTab(R.string.calendar_tab_pending, InvitationStatus.PENDING.apiValue),
                EventListTab(R.string.calendar_tab_accepted, InvitationStatus.ACCEPTED.apiValue),
                EventListTab(R.string.calendar_tab_expired, InvitationStatus.EXPIRED.apiValue),
            )

            CREATED -> listOf(
                EventListTab(R.string.calendar_tab_members, FILTER_MEMBERS),
                EventListTab(R.string.calendar_tab_personal, FILTER_PERSONAL),
                EventListTab(R.string.calendar_tab_expired, FILTER_EXPIRED),
            )

            // Two different questions: invitations *I* declined, and my events that
            // someone else declined. They come from separate endpoints.
            DECLINED -> listOf(
                EventListTab(R.string.calendar_tab_received, InvitationStatus.REJECTED.apiValue),
                EventListTab(R.string.calendar_tab_created, SCOPE_ACTIVE),
            )

            JOIN_CALL -> emptyList()
        }

    /** Whether the endpoint takes a search term, or the screen has to filter locally. */
    val searchesServerSide: Boolean get() = this != JOIN_CALL

    /** Join Call is always today's, so a date filter would have nothing to do. */
    val supportsDateFilter: Boolean get() = this != JOIN_CALL

    companion object {
        const val FILTER_MEMBERS = "members"
        const val FILTER_PERSONAL = "personal"
        const val FILTER_EXPIRED = "expired"
        const val SCOPE_ACTIVE = "active"
    }
}

/** One tab: what it is called, and the value it sends. */
data class EventListTab(@StringRes val labelRes: Int, val value: String)

/** One row. [status] is carried separately where the invitation knows it, not the event. */
data class EventListItem(
    val event: CalendarEvent,
    val status: InvitationStatus? = null,
    val invitationId: String? = null,
)

data class EventListUiState(
    val isLoading: Boolean = false,
    val isPaging: Boolean = false,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Backs all four event lists.
 *
 * Which list it is comes from the navigation argument, so there is one ViewModel, one
 * paging implementation and one search debounce rather than four of each.
 */
@HiltViewModel
class EventListViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val calendarBadges: CalendarBadges,
    private val realtime: CalendarRealtime,
    private val calendarSync: CalendarSync,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Counts for the Pending and Expired tabs. */
    val badges: StateFlow<CalendarBadgeCounts> = calendarBadges.counts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), CalendarBadgeCounts())

    val kind: EventListKind = savedStateHandle.get<String>(ARG_KIND)
        ?.let { runCatching { EventListKind.valueOf(it) }.getOrNull() }
        ?: EventListKind.RECEIVED

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _items = MutableStateFlow<List<EventListItem>>(emptyList())
    val items: StateFlow<List<EventListItem>> = _items.asStateFlow()

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val _dateFilter = MutableStateFlow<Long?>(null)
    val dateFilter: StateFlow<Long?> = _dateFilter.asStateFlow()

    private val _uiState = MutableStateFlow(EventListUiState())
    val uiState: StateFlow<EventListUiState> = _uiState.asStateFlow()

    /** Expanded cards, by event id. Absent means collapsed. */
    private val _occurrences = MutableStateFlow<Map<String, OccurrencesState>>(emptyMap())
    val occurrences: StateFlow<Map<String, OccurrencesState>> = _occurrences.asStateFlow()

    /** Survives a collapse so re-expanding is instant; dropped when the list reloads. */
    private val cachedOccurrences = mutableMapOf<String, OccurrencesState.Loaded>()

    /** The unfiltered set, kept so Join Call can search without refetching. */
    private var allItems: List<EventListItem> = emptyList()

    private var cursor: String? = null
    private var loadJob: Job? = null

    /**
     * Events deleted here in this session.
     *
     * The next page fetch can still return them: the backend takes a moment to propagate a
     * delete, and box-schedule mirrors lag further. Without this the row a user just
     * deleted reappears on the very next refresh, which reads as the delete having failed.
     * Cleared when they change context, so a legitimate row is never suppressed forever.
     */
    private val deletedIds = mutableSetOf<String>()

    /** What the card should offer on this list and tab. */
    val cardOptions: EventCardOptions
        get() = when (kind) {
            EventListKind.RECEIVED -> EventCardOptions(
                showStatus = true,
                // Nothing to accept or reject once the invitation has expired.
                showInviteeActions = !isExpiredTab,
            )

            EventListKind.CREATED -> EventCardOptions(
                showCreatorActions = true,
                // A personal event has no invitees, so the count would always read zero.
                showInviteeCount = currentTabValue != EventListKind.FILTER_PERSONAL,
            )

            EventListKind.DECLINED -> EventCardOptions(
                showStatus = true,
                showInviteeCount = true,
                // Only on the Created tab: the Received tab lists invitations *this* user
                // declined, where "who declined" is a question with one obvious answer.
                showDecliners = _selectedTab.value == TAB_DECLINED_CREATED,
            )

            EventListKind.JOIN_CALL -> EventCardOptions()
        }

    private val isExpiredTab: Boolean
        get() = kind == EventListKind.RECEIVED &&
            currentTabValue == InvitationStatus.EXPIRED.apiValue

    private val currentTabValue: String
        get() = kind.tabs.getOrNull(_selectedTab.value)?.value.orEmpty()

    init {
        load(reset = true)
        // Covers arriving on the Expired tab directly, not only switching to it.
        markExpiredReadIfOpened()

        // Someone else's change can move an event between these lists — an edit that adds
        // an invitee, a delete that empties one — so the page is re-read rather than
        // patched in place.
        viewModelScope.launch {
            realtime.changes.collect { refresh() }
        }
    }

    fun selectTab(index: Int) {
        if (_selectedTab.value == index) return
        _selectedTab.value = index
        markExpiredReadIfOpened()
        // A new tab is a new context: filters reset, and a row hidden by a local delete
        // is allowed to reappear if this tab's data legitimately holds it.
        deletedIds.clear()
        _search.value = ""
        _dateFilter.value = null
        load(reset = true)
    }

    /**
     * Opening the Expired tab reads those invitations.
     *
     * There is nothing else to do with an invitation to an event that already happened, so
     * a count that only cleared on some other action would never clear at all.
     */
    private fun markExpiredReadIfOpened() {
        if (!isExpiredTab) return
        viewModelScope.launch { calendarBadges.onExpiredTabOpened() }
    }

    fun onSearchChanged(query: String) {
        _search.value = query
        if (kind.searchesServerSide) {
            load(reset = true)
        } else {
            // Join Call has the whole day already; filtering locally avoids a round trip
            // per keystroke.
            _items.value = allItems.filterBySearch(query)
        }
    }

    fun setDateFilter(date: Long?) {
        _dateFilter.value = date
        load(reset = true)
    }

    fun clearFilters() {
        _search.value = ""
        _dateFilter.value = null
        load(reset = true)
    }

    fun refresh() = load(reset = true)

    fun loadMore() {
        if (_uiState.value.hasMore && !_uiState.value.isPaging) load(reset = false)
    }

    /**
     * Expands or collapses a recurring card's list of dates.
     *
     * Loaded on first expand and then kept: the fetch is a window expansion plus a status
     * read, and re-running both every time a card is toggled would make the control feel
     * broken. Cleared whenever the list itself reloads, so the dates cannot outlive the
     * events they belong to.
     */
    fun toggleOccurrences(event: CalendarEvent) {
        // Keyed by master + start: a row's own id is blank for a virtual occurrence.
        val id = event.expansionKey()
        if (_occurrences.value[id] != null) {
            _occurrences.value = _occurrences.value - id
            return
        }

        cachedOccurrences[id]?.let {
            _occurrences.value = _occurrences.value + (id to it)
            return
        }

        _occurrences.value = _occurrences.value + (id to OccurrencesState.Loading)

        viewModelScope.launch {
            val dates = (repository.occurrences(event) as? ApiResult.Success)?.data.orEmpty()

            // Statuses are the caller's own answers per date. A failure leaves the dates
            // listed without chips rather than failing the expansion.
            val statuses = (
                repository.occurrenceStatuses(event.effectiveMasterEventId)
                    as? ApiResult.Success
                )?.data

            val loaded = OccurrencesState.Loaded(dates, statuses)
            cachedOccurrences[id] = loaded
            // Only if the card is still expanded — the user may have collapsed it while
            // the two requests were in flight.
            if (_occurrences.value[id] is OccurrencesState.Loading) {
                _occurrences.value = _occurrences.value + (id to loaded)
            }
        }
    }

    /**
     * Deletes an event, or one occurrence of it, and drops the row on success.
     *
     * The row is removed locally rather than waiting for a refetch, and its id is
     * remembered — the next page can still return it while the backend propagates the
     * delete, and a row that came back would read as the delete having failed.
     */
    fun delete(event: CalendarEvent, scope: EditScope) {
        realtime.markSelfAction()

        viewModelScope.launch {
            val result = repository.delete(
                eventId = event.effectiveMasterEventId,
                scope = scope,
                occurrenceDate = event.startDatetime.takeIf { scope != EditScope.ALL },
            )

            when (result) {
                is ApiResult.Success -> {
                    if (scope == EditScope.ALL) {
                        calendarSync.onEventDeleted(event.effectiveMasterEventId)
                        onDeleted(event.id)
                    } else {
                        // The series survives; the mirror needs the reshaped version.
                        calendarSync.onEventChanged(event.effectiveMasterEventId)
                        refresh()
                    }
                }

                is ApiResult.Failure ->
                    ZillitLog.w(TAG, "Delete failed: ${result.error.message}")
            }
        }
    }

    /** Drops a row locally after a successful delete, without waiting for a refetch. */
    fun onDeleted(eventId: String) {
        deletedIds += eventId
        allItems = allItems.filterNot { it.event.id == eventId }
        _items.value = _items.value.filterNot { it.event.id == eventId }
    }

    private fun load(reset: Boolean) {
        if (reset) {
            loadJob?.cancel()
            cursor = null
            // Dates belong to the events they were expanded from; keeping them across a
            // reload would leave a card showing occurrences of a stale series.
            _occurrences.value = emptyMap()
            cachedOccurrences.clear()
        }

        _uiState.value = _uiState.value.copy(
            isLoading = reset,
            isPaging = !reset,
            errorMessage = null,
        )

        loadJob = viewModelScope.launch {
            val query = _search.value.takeIf { it.isNotBlank() && kind.searchesServerSide }
            val date = _dateFilter.value

            when (kind) {
                EventListKind.RECEIVED -> repository
                    .invitations(currentTabValue, cursor, search = query, date = date)
                    .handlePage(reset) { page ->
                        page.items.map {
                            EventListItem(it.event, it.status, it.invitationId)
                        }
                    }

                EventListKind.CREATED -> repository
                    .createdEvents(currentTabValue, cursor, search = query, date = date)
                    .handlePage(reset) { page -> page.items.map { EventListItem(it) } }

                EventListKind.DECLINED -> if (_selectedTab.value == 0) {
                    // Received: invitations this user turned down.
                    repository.invitations(currentTabValue, cursor, search = query, date = date)
                        .handlePage(reset) { page ->
                            page.items.map { EventListItem(it.event, it.status, it.invitationId) }
                        }
                } else {
                    // Created: this user's events that someone else turned down.
                    repository.declinedCreatedEvents(currentTabValue, cursor, search = query, date = date)
                        .handlePage(reset) { page -> page.items.map { EventListItem(it) } }
                }

                EventListKind.JOIN_CALL -> loadJoinCall()
            }
        }
    }

    private suspend fun loadJoinCall() {
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        when (val result = repository.joinCallEvents(today)) {
            is ApiResult.Success -> {
                allItems = result.data.map { EventListItem(it) }.filterNot { it.isDeleted() }
                _items.value = allItems.filterBySearch(_search.value)
                _uiState.value = EventListUiState()
            }

            is ApiResult.Failure -> fail(result)
        }
    }

    private inline fun <T> ApiResult<Page<T>>.handlePage(
        reset: Boolean,
        transform: (Page<T>) -> List<EventListItem>,
    ) {
        when (this) {
            is ApiResult.Success -> {
                val fresh = transform(data).filterNot { it.isDeleted() }
                allItems = if (reset) fresh else allItems + fresh
                _items.value = allItems
                cursor = data.cursor
                _uiState.value = EventListUiState(hasMore = data.hasMore)
            }

            is ApiResult.Failure -> fail(this)
        }
    }

    private fun fail(result: ApiResult.Failure) {
        ZillitLog.w(TAG, "Event list ($kind) failed: ${result.error.message}")
        // The list that is already on screen stays: a failed page should not empty a
        // screen the user is reading.
        _uiState.value = EventListUiState(errorMessage = result.error.message)
    }

    private fun EventListItem.isDeleted(): Boolean = event.id in deletedIds

    /** Stable per row, even when the server sends no `_id`. */
    private fun CalendarEvent.expansionKey(): String =
        "$effectiveMasterEventId:$startDatetime"

    private fun List<EventListItem>.filterBySearch(query: String): List<EventListItem> =
        if (query.isBlank()) this else filter { it.event.title.contains(query, ignoreCase = true) }

    companion object {
        private const val STOP_TIMEOUT = 5_000L

        /** Index of Declined → Created. */
        private const val TAB_DECLINED_CREATED = 1

        /** Navigation argument name — must match the route's property. */
        const val ARG_KIND = "kind"

        private const val TAG = "EventListViewModel"
    }
}
