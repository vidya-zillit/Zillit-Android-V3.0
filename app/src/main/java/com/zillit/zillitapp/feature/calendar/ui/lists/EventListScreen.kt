package com.zillit.zillitapp.feature.calendar.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.common.DateTime
import com.zillit.zillitapp.core.common.formatIn
import com.zillit.zillitapp.core.ui.components.CountBadge
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.calendar.ui.EventCard
import com.zillit.zillitapp.feature.calendar.ui.DeleteEventPrompt
import com.zillit.zillitapp.feature.calendar.ui.EventCardCallbacks
import com.zillit.zillitapp.feature.calendar.ui.OccurrencesState
import com.zillit.zillitapp.feature.calendar.ui.detail.EventDetailSheet
import com.zillit.zillitapp.feature.calendar.ui.detail.InviteeListSheet
import java.time.LocalDate
import java.time.ZoneId

/**
 * Received, Created, Declined and Join Call — one screen, four configurations.
 *
 * They share a search field, a date filter, cursor paging and the event card. The only
 * differences are the tabs and which request runs, and both live in [EventListKind].
 *
 * Only Received carries tab counts; opening its Expired tab marks those invitations read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventListRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EventListViewModel = hiltViewModel(),
    onEventClick: (CalendarEvent) -> Unit = {},
    onEdit: (CalendarEvent) -> Unit = {},
    onDelete: (CalendarEvent) -> Unit = {},
    onAccept: (CalendarEvent) -> Unit = {},
    onReject: (CalendarEvent) -> Unit = {},
    onJoinCall: (CalendarEvent) -> Unit = {},
    onSeeInvitees: (CalendarEvent) -> Unit = {},
) {
    val kind = viewModel.kind
    val items by viewModel.items.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val dateFilter by viewModel.dateFilter.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val badges by viewModel.badges.collectAsStateWithLifecycle()
    val occurrences by viewModel.occurrences.collectAsStateWithLifecycle()

    // Tapping a card opens the detail sheet in place rather than navigating: the sheet is
    // the same everywhere, and a push would lose the list's scroll position and paging.
    var openedEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    // The event whose invitees are being listed, plus which occurrence — the sheet fetches
    // the full list itself, since an event response caps its embedded invitations.
    var inviteesOf by remember { mutableStateOf<CalendarEvent?>(null) }
    var deleting by remember { mutableStateOf<CalendarEvent?>(null) }

    openedEvent?.let { event ->
        EventDetailSheet(
            eventId = event.effectiveMasterEventId,
            occurrenceStartMs = event.startDatetime,
            onDismiss = { openedEvent = null },
            onEdit = onEdit,
            onDelete = { deleting = it },
            onJoinCall = onJoinCall,
            onSeeInvitees = { inviteesOf = it },
            // Answering an invitation moves it between tabs, so the list is re-read.
            onChanged = viewModel::refresh,
        )
    }

    deleting?.let { event ->
        DeleteEventPrompt(
            event = event,
            onConfirm = { scope ->
                deleting = null
                viewModel.delete(event, scope)
            },
            onDismiss = { deleting = null },
        )
    }

    inviteesOf?.let { event ->
        InviteeListSheet(
            eventId = event.effectiveMasterEventId,
            occurrenceStartMs = event.startDatetime,
            onDismiss = { inviteesOf = null },
        )
    }

    Scaffold(
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitTopBar(
                title = stringResource(kind.titleRes),
                onBackClick = onBack,
                onHelpClick = null,
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (kind.tabs.isNotEmpty()) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = ZillitTheme.colors.surface,
                    contentColor = ZillitTheme.colors.brand,
                    edgePadding = ZillitTheme.spacing.md,
                ) {
                    kind.tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = index == selectedTab,
                            onClick = { viewModel.selectTab(index) },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement =
                                        Arrangement.spacedBy(ZillitTheme.spacing.xs),
                                ) {
                                    Text(stringResource(tab.labelRes))
                                    CountBadge(count = badgeFor(kind, tab, badges.pending, badges.expired))
                                }
                            },
                        )
                    }
                }
            }

            SearchAndDateFilter(
                search = search,
                onSearchChanged = viewModel::onSearchChanged,
                dateFilter = dateFilter.takeIf { kind.supportsDateFilter },
                onDateFilterChanged = viewModel::setDateFilter,
                showDateFilter = kind.supportsDateFilter,
            )

            val listState = rememberLazyListState()

            // Page when the end comes into view, rather than on a scroll threshold: the
            // rows vary in height, so a fixed offset would fire early on short lists and
            // late on tall ones.
            LaunchedEffect(listState, items.size) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                    .collect { last ->
                        if (last != null && last >= items.lastIndex - PAGE_TRIGGER_DISTANCE) {
                            viewModel.loadMore()
                        }
                    }
            }

            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f),
            ) {
                if (items.isEmpty() && !uiState.isLoading) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            EmptyState(
                                icon = Icons.Outlined.EventBusy,
                                title = stringResource(kind.emptyRes),
                                modifier = Modifier.fillParentMaxSize(),
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(ZillitTheme.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    ) {
                        items(items, key = { "${it.event.id}:${it.event.startDatetime}" }) { item ->
                            EventCard(
                                event = item.event,
                                status = item.status,
                                options = viewModel.cardOptions,
                                occurrences = occurrences[
                                    "${item.event.effectiveMasterEventId}:${item.event.startDatetime}",
                                ]
                                    ?: OccurrencesState.Collapsed,
                                onToggleOccurrences = {
                                    viewModel.toggleOccurrences(item.event)
                                },
                                onOccurrenceClick = { openedEvent = it },
                                showDecliners = viewModel.cardOptions.showDecliners,
                                callbacks = EventCardCallbacks(
                                    // Both the card body and See Invitees open a sheet in
                                    // place; the outer callbacks stay for hosts that want
                                    // to route somewhere else instead.
                                    onClick = { openedEvent = it },
                                    onEdit = onEdit,
                                    onDelete = { deleting = it },
                                    onAccept = onAccept,
                                    onReject = onReject,
                                    onJoinCall = onJoinCall,
                                    onSeeInvitees = { inviteesOf = it },
                                ),
                            )
                        }

                        if (uiState.isPaging) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(ZillitTheme.spacing.md),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = ZillitTheme.colors.brand,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Only Pending and Expired carry counts.
 *
 * Accepted deliberately has none — it is a confirmation list, not an unread one, and a
 * number there is noise. Created and Declined have none because the badge model does not
 * publish counters for them, and a list-size chip would double-count against the project
 * total on the bottom bar.
 */
private fun badgeFor(
    kind: EventListKind,
    tab: EventListTab,
    pending: Int,
    expired: Int,
): Int = if (kind != EventListKind.RECEIVED) {
    0
} else {
    when (tab.labelRes) {
        R.string.calendar_tab_pending -> pending
        R.string.calendar_tab_expired -> expired
        else -> 0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchAndDateFilter(
    search: String,
    onSearchChanged: (String) -> Unit,
    dateFilter: Long?,
    onDateFilterChanged: (Long?) -> Unit,
    showDateFilter: Boolean,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChanged,
            placeholder = { Text(stringResource(R.string.calendar_search_hint)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = ZillitTheme.colors.textTertiary,
                )
            },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { onSearchChanged("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.action_close),
                            tint = ZillitTheme.colors.textTertiary,
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (showDateFilter) {
            val today = remember { LocalDate.now().startOfDayMs() }

            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                FilterChip(
                    selected = dateFilter == today,
                    onClick = {
                        // A second tap clears it, so the chip is its own undo.
                        onDateFilterChanged(if (dateFilter == today) null else today)
                    },
                    label = { Text(stringResource(R.string.calendar_filter_today)) },
                )

                FilterChip(
                    selected = dateFilter != null && dateFilter != today,
                    onClick = { pickerOpen = true },
                    label = {
                        Text(
                            text = dateFilter
                                ?.takeIf { it != today }
                                ?.formatIn(ZoneId.systemDefault(), DateTime.PATTERN_DATE)
                                ?: stringResource(R.string.calendar_filter_select_date),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.CalendarToday,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )

                if (dateFilter != null) {
                    TextButton(onClick = { onDateFilterChanged(null) }) {
                        Text(stringResource(R.string.calendar_filter_clear))
                    }
                }
            }

            if (pickerOpen) {
                val state = rememberDatePickerState(initialSelectedDateMillis = dateFilter)
                DatePickerDialog(
                    onDismissRequest = { pickerOpen = false },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pickerOpen = false
                                // The picker answers in UTC; the filter means a local day,
                                // so it is rebuilt from the calendar date rather than
                                // passed through.
                                onDateFilterChanged(state.selectedDateMillis?.toLocalStartOfDay())
                            },
                        ) { Text(stringResource(R.string.action_done)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pickerOpen = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                ) {
                    DatePicker(state = state)
                }
            }
        }
    }
}

private fun LocalDate.startOfDayMs(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * The date picker returns midnight **UTC** for the day the user tapped. Sending that as-is
 * asks the server for the wrong day for anyone far enough east or west of Greenwich.
 */
private fun Long.toLocalStartOfDay(): Long =
    java.time.Instant.ofEpochMilli(this)
        .atZone(java.time.ZoneOffset.UTC)
        .toLocalDate()
        .startOfDayMs()

/** How close to the end of the list triggers the next page. */
private const val PAGE_TRIGGER_DISTANCE = 3
