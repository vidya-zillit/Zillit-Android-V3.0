package com.zillit.zillitapp.feature.calendar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.calendar.CalendarBadgeCounts
import com.zillit.zillitapp.core.calendar.CalendarBadges
import com.zillit.zillitapp.core.calendar.CalendarChange
import com.zillit.zillitapp.core.calendar.CalendarRealtime
import com.zillit.zillitapp.core.calendar.data.CalendarRepository
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.common.DateTime
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** Which span the grid shows. Matches v2's `ViewMode`. */
enum class CalendarViewMode { MONTH, WEEK, DAY }

/**
 * One cell of the grid.
 *
 * [date] is null for the padding cells before the 1st and after the last — they hold the
 * columns in place and must render as blanks rather than as neighbouring dates, which is
 * what v2 does.
 */
data class DayCell(
    val date: LocalDate?,
    val isToday: Boolean = false,
    val isSelected: Boolean = false,
    val isInCurrentMonth: Boolean = true,
    val eventCount: Int = 0,
)

data class CalendarUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val session: SessionStore,
    private val realtime: CalendarRealtime,
    calendarBadges: CalendarBadges,
) : ViewModel() {

    /** Pending + expired invitations, shown on the Events quick action. */
    val badges: StateFlow<CalendarBadgeCounts> = calendarBadges.counts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), CalendarBadgeCounts())

    // Header formatters. Locale-aware, so month and weekday names follow the app language
    // rather than v2's hardcoded Locale.US.
    private val monthYear = DateTime.formatter(DateTime.PATTERN_MONTH_YEAR)
    private val shortMonthYear = DateTime.formatter(PATTERN_SHORT_MONTH_YEAR)
    private val dayMonth = DateTime.formatter(PATTERN_DAY_MONTH)
    private val dayMonthYear = DateTime.formatter(PATTERN_DAY_MONTH_YEAR)
    private val fullDay = DateTime.formatter(PATTERN_FULL_DAY)

    private val _currentMonth = MutableStateFlow(YearMonth.now())
    val currentMonth: StateFlow<YearMonth> = _currentMonth

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    private val _viewMode = MutableStateFlow(CalendarViewMode.MONTH)
    val viewMode: StateFlow<CalendarViewMode> = _viewMode

    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState

    /**
     * The grid, rebuilt whenever anything it depends on moves.
     *
     * Derived rather than stored so the selected day, the month and the event counts can
     * never disagree with each other.
     */
    val gridCells: StateFlow<List<DayCell>> = combine(
        _currentMonth,
        _selectedDate,
        _events,
        _viewMode,
    ) { month, selected, events, mode ->
        val counts = events.groupingBy { it.startDatetime.toLocalDate() }.eachCount()
        when (mode) {
            CalendarViewMode.MONTH -> monthCells(month, selected, counts)
            CalendarViewMode.WEEK -> weekCells(selected, counts)
            // Day mode shows the timeline instead of a grid.
            CalendarViewMode.DAY -> emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /** The selected day's events, oldest first — what the agenda lists. */
    val selectedDayEvents: StateFlow<List<CalendarEvent>> =
        combine(_selectedDate, _events) { date, events ->
            events.filter { it.startDatetime.toLocalDate() == date }
                .sortedBy { it.startDatetime }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /**
     * The header title, which names whatever span is on screen: `April 2026` in month
     * view, `20 - 26 Apr 2026` in week view, `Thu, 23 Apr 2026` in day view.
     *
     * A week that straddles a month or a year widens the label rather than truncating it,
     * so the range is never ambiguous.
     */
    val title: StateFlow<String> = combine(
        _currentMonth,
        _selectedDate,
        _viewMode,
    ) { month, date, mode ->
        when (mode) {
            CalendarViewMode.MONTH -> month.atDay(1).format(monthYear)

            CalendarViewMode.WEEK -> {
                val start = startOfWeek(date)
                val end = start.plusDays(6)
                when {
                    start.year == end.year && start.month == end.month ->
                        "${start.dayOfMonth} - ${end.dayOfMonth} ${end.format(shortMonthYear)}"

                    start.year == end.year ->
                        "${start.format(dayMonth)} - ${end.format(dayMonthYear)}"

                    else -> "${start.format(dayMonthYear)} - ${end.format(dayMonthYear)}"
                }
            }

            CalendarViewMode.DAY -> date.format(fullDay)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), "")

    init {
        load()

        observeRealtime()

        // Per-project isolation: the calendar is scoped to the open project, so switching
        // projects has to drop what is on screen rather than leave one production's
        // schedule showing under another's name until the fetch returns.
        viewModelScope.launch {
            session.activeProject
                .map { it?.projectId }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    _events.value = emptyList()
                    load()
                }
        }
    }

    /**
     * Reacts to someone else creating, changing or deleting an event.
     *
     * The change arrives with the event already fetched by id, so it is merged into the
     * window straight away — the window refetch that follows can miss a just-created event
     * entirely, because the events index is eventually consistent. The refetch still runs,
     * for the day counts and for anything the single event does not cover.
     */
    private fun observeRealtime() {
        viewModelScope.launch {
            realtime.changes.collect { change ->
                when (change.action) {
                    CalendarChange.Action.DELETED ->
                        _events.value = _events.value.filterNot { it.id == change.eventId }

                    else -> change.event?.let { merge(it) }
                }
                load()
            }
        }
    }

    /** Replaces the event if the window already holds it, otherwise adds it. */
    private fun merge(event: CalendarEvent) {
        val existing = _events.value.indexOfFirst {
            it.id == event.id && it.startDatetime == event.startDatetime
        }

        _events.value = if (existing >= 0) {
            _events.value.toMutableList().apply { this[existing] = event }
        } else {
            (_events.value + event).sortedBy { it.startDatetime }
        }
    }

    fun setViewMode(mode: CalendarViewMode) {
        if (_viewMode.value == mode) return
        _viewMode.value = mode
        // Re-anchor the month on the selected day so the title and the fetched range agree
        // — switching to Week while the month is elsewhere would otherwise label the week
        // of a date the grid is not showing.
        _currentMonth.value = YearMonth.from(_selectedDate.value)
        load()
    }

    fun previous() = navigate(-1)

    fun next() = navigate(1)

    fun goToToday() {
        val today = LocalDate.now()
        _selectedDate.value = today
        _currentMonth.value = YearMonth.from(today)
        load()
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date

        // Tapping a padding cell means the neighbouring month, so follow it rather than
        // leaving the grid showing a month the selection is not in.
        val month = YearMonth.from(date)
        if (month != _currentMonth.value) {
            _currentMonth.value = month
            load()
        }
    }

    fun refresh() = load()

    /**
     * Steps the view by one unit of whatever is on screen.
     *
     * Month mode moves the month; week and day modes move the **selected date**, because
     * that is what the user is looking at — stepping the month there would jump the
     * selection out of view.
     */
    private fun navigate(direction: Int) {
        when (_viewMode.value) {
            CalendarViewMode.MONTH -> {
                _currentMonth.value = _currentMonth.value.plusMonths(direction.toLong())
            }

            CalendarViewMode.WEEK -> {
                val moved = _selectedDate.value.plusWeeks(direction.toLong())
                _selectedDate.value = moved
                _currentMonth.value = YearMonth.from(moved)
            }

            CalendarViewMode.DAY -> {
                val moved = _selectedDate.value.plusDays(direction.toLong())
                _selectedDate.value = moved
                _currentMonth.value = YearMonth.from(moved)
            }
        }
        load()
    }

    /**
     * Fetches exactly the window the current view shows.
     *
     * The month range is the month itself, not padded: the grid's leading and trailing
     * cells are blanks rather than neighbouring dates, so events outside the month have
     * nowhere to render and fetching them would be wasted.
     */
    private fun load() {
        val (start, end) = when (_viewMode.value) {
            CalendarViewMode.MONTH -> {
                val month = _currentMonth.value
                month.atDay(1) to month.atEndOfMonth()
            }

            CalendarViewMode.WEEK -> {
                val weekStart = startOfWeek(_selectedDate.value)
                weekStart to weekStart.plusDays(6)
            }

            CalendarViewMode.DAY -> _selectedDate.value to _selectedDate.value
        }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        viewModelScope.launch {
            when (val result = repository.events(start.startOfDayMs(), end.endOfDayMs())) {
                is ApiResult.Success -> {
                    _events.value = result.data
                    _uiState.value = CalendarUiState(isLoading = false)
                }

                is ApiResult.Failure -> {
                    // The previous window stays on screen: a failed refresh should not
                    // blank a calendar the user is reading.
                    _uiState.value = CalendarUiState(
                        isLoading = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    private fun monthCells(
        month: YearMonth,
        selected: LocalDate,
        counts: Map<LocalDate, Int>,
    ): List<DayCell> {
        val first = month.atDay(1)
        // Sunday-first, as the header row is laid out.
        val leadingBlanks = first.dayOfWeek.value % 7
        val today = LocalDate.now()

        val cells = mutableListOf<DayCell>()
        repeat(leadingBlanks) { cells += DayCell(date = null) }

        (1..month.lengthOfMonth()).forEach { day ->
            val date = month.atDay(day)
            cells += DayCell(
                date = date,
                isToday = date == today,
                isSelected = date == selected,
                eventCount = counts[date] ?: 0,
            )
        }

        // Pad to whole weeks so the grid does not change height month to month, which
        // would shift everything below it.
        while (cells.size % DAYS_IN_WEEK != 0) cells += DayCell(date = null)
        return cells
    }

    private fun weekCells(selected: LocalDate, counts: Map<LocalDate, Int>): List<DayCell> {
        val start = startOfWeek(selected)
        val today = LocalDate.now()
        return (0 until DAYS_IN_WEEK).map { offset ->
            val date = start.plusDays(offset.toLong())
            DayCell(
                date = date,
                isToday = date == today,
                isSelected = date == selected,
                isInCurrentMonth = YearMonth.from(date) == YearMonth.from(selected),
                eventCount = counts[date] ?: 0,
            )
        }
    }

    private fun startOfWeek(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value % 7).toLong())

    private fun Long.toLocalDate(): LocalDate =
        Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

    private fun LocalDate.startOfDayMs(): Long =
        atStartOfDay(zone).toInstant().toEpochMilli()

    private fun LocalDate.endOfDayMs(): Long =
        plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    /**
     * The device's zone.
     *
     * Grid placement is a local-time question — which square a user sees an event in — even
     * though each event also carries the zone it was scheduled in for display.
     */
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private companion object {
        const val STOP_TIMEOUT = 5_000L
        const val DAYS_IN_WEEK = 7

        // Header-only shapes; they stay here rather than in DateTime because nothing
        // outside this header formats a date this way.
        const val PATTERN_SHORT_MONTH_YEAR = "MMM yyyy"
        const val PATTERN_DAY_MONTH = "d MMM"
        const val PATTERN_DAY_MONTH_YEAR = "d MMM yyyy"
        const val PATTERN_FULL_DAY = "EEE, d MMM yyyy"
    }
}
