package com.zillit.zillitapp.feature.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.common.DateTime
import com.zillit.zillitapp.core.ui.chat.ChatUnitStrip
import com.zillit.zillitapp.core.ui.chat.model.ChatUnit
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.calendar.ui.detail.EventDetailSheet
import java.time.LocalDate
import java.time.LocalTime

/**
 * The calendar unit.
 *
 * It sits in the Home strip beside the chat units but is **not a chat**: there is no
 * composer, no emoji, no attachment, no send and no voice note anywhere in this file. The
 * strip above it is the same [ChatUnitStrip] every Home unit uses, so switching from
 * Bulletin to Calendar swaps only what is under it.
 */
@Composable
fun CalendarRoute(
    units: List<ChatUnit>,
    selectedUnitId: String?,
    onUnitSelected: (ChatUnit) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = hiltViewModel(),
    onCreateEvent: (LocalDate) -> Unit = {},
    onOpenReceived: () -> Unit = {},
    onOpenCreated: () -> Unit = {},
    onOpenDeclined: () -> Unit = {},
    onOpenJoinCall: () -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val cells by viewModel.gridCells.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val dayEvents by viewModel.selectedDayEvents.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val badges by viewModel.badges.collectAsStateWithLifecycle()

    // Anything that changes an event happens on another screen — the form, the received
    // and created lists — and the window is fetched, not observed, so coming back is the
    // only moment the grid can learn about it. The first resume is skipped: the ViewModel
    // has just loaded, and refetching immediately would be the same window twice.
    var loadedOnce by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (loadedOnce) viewModel.refresh() else loadedOnce = true
    }

    // Which event's detail is open, and which occurrence of it. Held here rather than in
    // the ViewModel because it is pure navigation state — closing the sheet should not
    // survive a process death and reopen itself.
    var openedEvent by remember { mutableStateOf<CalendarEvent?>(null) }

    openedEvent?.let { event ->
        EventDetailSheet(
            eventId = event.effectiveMasterEventId,
            occurrenceStartMs = event.startDatetime,
            onDismiss = { openedEvent = null },
            // An accept or decline changes which events the window returns, so the grid
            // and agenda are re-read rather than left showing the pre-action state.
            onChanged = viewModel::refresh,
        )
    }

    CalendarScreen(
        units = units,
        selectedUnitId = selectedUnitId,
        onUnitSelected = onUnitSelected,
        title = title,
        viewMode = viewMode,
        cells = cells,
        selectedDate = selectedDate,
        dayEvents = dayEvents,
        isLoading = uiState.isLoading,
        onViewModeChange = viewModel::setViewMode,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onToday = viewModel::goToToday,
        onDaySelected = viewModel::selectDate,
        onRefresh = viewModel::refresh,
        onEventClick = { openedEvent = it },
        onCreateEvent = { onCreateEvent(selectedDate) },
        onOpenReceived = onOpenReceived,
        onOpenCreated = onOpenCreated,
        onOpenDeclined = onOpenDeclined,
        onOpenJoinCall = onOpenJoinCall,
        eventsBadgeCount = badges.total,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    units: List<ChatUnit>,
    selectedUnitId: String?,
    onUnitSelected: (ChatUnit) -> Unit,
    title: String,
    viewMode: CalendarViewMode,
    cells: List<DayCell>,
    selectedDate: LocalDate,
    dayEvents: List<CalendarEvent>,
    isLoading: Boolean,
    onViewModeChange: (CalendarViewMode) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onDaySelected: (LocalDate) -> Unit,
    onRefresh: () -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onCreateEvent: () -> Unit,
    onOpenReceived: () -> Unit,
    onOpenCreated: () -> Unit,
    onOpenDeclined: () -> Unit,
    onOpenJoinCall: () -> Unit,
    modifier: Modifier = Modifier,
    /** Pending + expired invitations. */
    eventsBadgeCount: Int = 0,
    onOpenSettings: (() -> Unit)? = null,
) {
    val isDayView = viewMode == CalendarViewMode.DAY

    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (units.isNotEmpty()) {
                ChatUnitStrip(
                    units = units,
                    selectedUnitId = selectedUnitId,
                    onUnitSelected = onUnitSelected,
                )
            }

            CalendarHeader(
                title = title,
                viewMode = viewMode,
                onPrevious = onPrevious,
                onNext = onNext,
                onToday = onToday,
                onViewModeChange = onViewModeChange,
                onSettings = onOpenSettings,
            )

            // The grid is the month/week surface; Day view replaces it with the date panel
            // and gives the whole area below to the timeline.
            if (isDayView) {
                DayCard(date = selectedDate)
            } else {
                WeekdayRow()
                CalendarGrid(cells = cells, onDaySelected = onDaySelected)
            }

            CalendarQuickActions(
                eventsBadgeCount = eventsBadgeCount,
                onOpenReceived = onOpenReceived,
                onOpenCreated = onOpenCreated,
                onOpenDeclined = onOpenDeclined,
                onJoinCallClick = onOpenJoinCall,
            )

            HorizontalDivider(color = ZillitTheme.colors.divider)

            if (!isDayView) {
                AgendaHeader(date = selectedDate)
            }

            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = isLoading,
                onRefresh = onRefresh,
                state = pullState,
                modifier = Modifier.weight(1f),
            ) {
                when {
                    isDayView -> HourlyTimeline(
                        selectedDate = selectedDate,
                        events = dayEvents,
                        onEventClick = onEventClick,
                    )

                    // Inside a LazyColumn, not bare: PullToRefreshBox only sees the pull
                    // gesture from a scrollable child, so an empty day would otherwise be
                    // the one state you cannot refresh out of.
                    dayEvents.isEmpty() -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            EmptyState(
                                icon = Icons.Outlined.EventBusy,
                                title = stringResource(R.string.calendar_no_events_today),
                                modifier = Modifier.fillParentMaxSize(),
                            )
                        }
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Clears the FAB, so the last event of the day can be read and
                        // tapped instead of sitting under it.
                        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
                    ) {
                        items(dayEvents, key = { it.agendaKey() }) { event ->
                            AgendaEventRow(event = event, onClick = { onEventClick(event) })
                            HorizontalDivider(color = ZillitTheme.colors.divider)
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onCreateEvent,
            containerColor = ZillitTheme.colors.brand,
            contentColor = ZillitTheme.colors.textOnBrand,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(ZillitTheme.spacing.lg),
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.calendar_new_event),
            )
        }
    }
}

/** `Today's Events`, `Tomorrow's Events`, or the date itself — v2's three cases. */
@Composable
private fun AgendaHeader(date: LocalDate, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val text = when (date) {
        today -> stringResource(R.string.calendar_agenda_today)
        today.plusDays(1) -> stringResource(R.string.calendar_agenda_tomorrow)
        else -> remember(date) {
            date.format(DateTime.formatter(PATTERN_AGENDA_DATE))
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = ZillitTheme.colors.textPrimary,
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
    )
}

/**
 * Day view: 24 rows, one per hour, with each event dropped into the hour it starts.
 *
 * Opens near the current hour rather than at midnight — a working day starts at 8, and
 * landing on 00:00 means scrolling past a third of the list every time. On any day but
 * today it opens at the top, since "now" means nothing there.
 */
@Composable
private fun HourlyTimeline(
    selectedDate: LocalDate,
    events: List<CalendarEvent>,
    onEventClick: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    val buckets = remember(events) {
        val byHour = List(HOURS_PER_DAY) { mutableListOf<CalendarEvent>() }
        events.forEach { event ->
            val zone = DateTime.zoneOf(event.timezone)
            val hour = java.time.Instant.ofEpochMilli(event.startDatetime)
                .atZone(zone).hour.coerceIn(0, HOURS_PER_DAY - 1)
            byHour[hour] += event
        }
        byHour
    }

    LaunchedEffect(selectedDate) {
        if (selectedDate == LocalDate.now()) {
            // One hour of context above the current one, so "now" is not flush against
            // the top edge.
            listState.scrollToItem((LocalTime.now().hour - 1).coerceAtLeast(0))
        } else {
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        items(HOURS_PER_DAY) { hour ->
            HourRow(
                hour = hour,
                events = buckets[hour],
                onEventClick = onEventClick,
            )
        }
    }
}

/**
 * A stable list key.
 *
 * An expanded occurrence of a recurring series shares its id with every other occurrence,
 * so the id alone would collide inside one day for a series that repeats more than once —
 * and duplicate keys crash a `LazyColumn` rather than merely rendering oddly.
 */
private fun CalendarEvent.agendaKey(): String = "$id:$startDatetime"

private const val HOURS_PER_DAY = 24

/** Enough room under the last row for the FAB not to cover it. */
private val FAB_CLEARANCE = 88.dp

/** `23 April` — the agenda heading for any day that is not today or tomorrow. */
private const val PATTERN_AGENDA_DATE = "d MMMM"
