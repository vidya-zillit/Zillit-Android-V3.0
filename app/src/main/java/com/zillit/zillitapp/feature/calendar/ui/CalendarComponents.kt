package com.zillit.zillitapp.feature.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.common.DateTime
import com.zillit.zillitapp.core.common.formatEventRange
import com.zillit.zillitapp.core.common.toEventTime
import com.zillit.zillitapp.core.ui.components.CountBadge
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.theme.toColorOrDefault
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Month title, step controls, Today, and the view-mode selector.
 *
 * v2's header also carries a user switcher and a settings gear; both are `GONE` there
 * because the host app owns those, so neither is rebuilt here.
 */
@Composable
fun CalendarHeader(
    title: String,
    viewMode: CalendarViewMode,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onViewModeChange: (CalendarViewMode) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Device-calendar mirroring settings.
     *
     * v2 hides its gear here because the host app owns those settings elsewhere; v3 has
     * nowhere else for them yet, so this is where they live.
     */
    onSettings: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(
                horizontal = ZillitTheme.spacing.md,
                vertical = ZillitTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        ViewModePill(viewMode = viewMode, onViewModeChange = onViewModeChange)

        StepButton(
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.calendar_previous),
            onClick = onPrevious,
        )

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = ZillitTheme.colors.brandSoft,
            modifier = Modifier.clickable(onClick = onToday),
        ) {
            Text(
                text = stringResource(R.string.today),
                style = MaterialTheme.typography.labelLarge,
                color = ZillitTheme.colors.brand,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        StepButton(
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = stringResource(R.string.calendar_next),
            onClick = onNext,
        )

        onSettings?.let {
            StepButton(
                icon = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.calendar_settings),
                onClick = it,
            )
        }
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = ZillitTheme.colors.textSecondary,
        )
    }
}

/** The Month / Week / Day selector, a pill that drops a menu — as in v2. */
@Composable
private fun ViewModePill(
    viewMode: CalendarViewMode,
    onViewModeChange: (CalendarViewMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = ZillitTheme.colors.surfaceSunken,
            modifier = Modifier.clickable { expanded = true },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = stringResource(viewMode.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = ZillitTheme.colors.textPrimary,
                )
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(R.string.calendar_view_mode),
                    tint = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CalendarViewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(mode.labelRes)) },
                    onClick = {
                        expanded = false
                        onViewModeChange(mode)
                    },
                )
            }
        }
    }
}

private val CalendarViewMode.labelRes: Int
    get() = when (this) {
        CalendarViewMode.MONTH -> R.string.calendar_view_month
        CalendarViewMode.WEEK -> R.string.calendar_view_week
        CalendarViewMode.DAY -> R.string.calendar_view_day
    }

/**
 * Sun–Sat column headings.
 *
 * Derived from [java.time.DayOfWeek] in the device locale rather than a hardcoded English
 * list, so the row translates with the rest of the app.
 */
@Composable
fun WeekdayRow(modifier: Modifier = Modifier) {
    val locale = Locale.getDefault()
    val labels = remember(locale) {
        // Sunday first, matching the grid's leading-blank calculation.
        (0..6).map { offset ->
            java.time.DayOfWeek.SUNDAY.plus(offset.toLong())
                .getDisplayName(TextStyle.SHORT, locale)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xs),
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.textTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The month or week grid.
 *
 * Laid out as rows of seven rather than a `LazyVerticalGrid` because the grid does not
 * scroll — it is pinned above the agenda, and a lazy grid nested in a scrolling column
 * fights the parent for the gesture.
 */
@Composable
fun CalendarGrid(
    cells: List<DayCell>,
    onDaySelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xs),
    ) {
        cells.chunked(DAYS_IN_WEEK).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    DayCellView(
                        cell = cell,
                        onClick = { cell.date?.let(onDaySelected) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // A short final row would otherwise stretch its cells across the width.
                repeat(DAYS_IN_WEEK - week.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DayCellView(cell: DayCell, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val date = cell.date

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            // Rounded, not circular: a circular clip would cut the corner the count sits in.
            .clip(RoundedCornerShape(12.dp))
            .then(if (date != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (date == null) return@Box

        // The count hangs off the **cell's** top-right corner, not the date's — v2 puts it
        // there, and anchoring it to the number instead covers the digits.
        Box(
            modifier = Modifier
                .size(30.dp)
                .then(
                    when {
                        // Today is filled; the selection is outlined. Both at once still
                        // reads correctly — a filled circle with a ring.
                        cell.isToday -> Modifier.background(ZillitTheme.colors.brand, CircleShape)
                        cell.isSelected ->
                            Modifier.background(ZillitTheme.colors.brandSoft, CircleShape)

                        else -> Modifier
                    },
                )
                .then(
                    if (cell.isSelected && !cell.isToday) {
                        Modifier.border(1.dp, ZillitTheme.colors.brand, CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (cell.isToday || cell.isSelected) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Normal
                },
                color = when {
                    cell.isToday -> ZillitTheme.colors.textOnBrand
                    cell.isSelected -> ZillitTheme.colors.brand
                    !cell.isInCurrentMonth -> ZillitTheme.colors.textTertiary
                    else -> ZillitTheme.colors.textPrimary
                },
            )
        }

        // Brand orange rather than the danger red every other badge uses — a busy day is
        // information, not a warning.
        if (cell.eventCount > 0) {
            CountBadge(
                count = cell.eventCount,
                containerColor = ZillitTheme.colors.brand,
                contentColor = ZillitTheme.colors.textOnBrand,
                ringColor = ZillitTheme.colors.surface,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/**
 * Events and Join Call — the two shortcuts that sit under the grid in v2.
 *
 * Events drops a menu of the three lists rather than opening one directly, and only the
 * Received row carries a count. v2 is explicit about why the other two stay clean: the
 * badge model only publishes pending and expired, so a list-size chip on Created or
 * Declined would double-count against the project total on the bottom-nav pill.
 */
@Composable
fun CalendarQuickActions(
    eventsBadgeCount: Int,
    onOpenReceived: () -> Unit,
    onOpenCreated: () -> Unit,
    onOpenDeclined: () -> Unit,
    onJoinCallClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            QuickActionButton(
                label = stringResource(R.string.calendar_events),
                icon = Icons.Outlined.ExpandMore,
                onClick = { expanded = true },
            )
            // Overhangs the corner rather than sitting inside the button, so a long label
            // never pushes it off.
            CountBadge(
                count = eventsBadgeCount,
                ringColor = ZillitTheme.colors.surface,
                modifier = Modifier.align(Alignment.TopEnd),
            )

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.calendar_received_events)) },
                    trailingIcon = { CountBadge(count = eventsBadgeCount) },
                    onClick = {
                        expanded = false
                        onOpenReceived()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.calendar_created_events)) },
                    onClick = {
                        expanded = false
                        onOpenCreated()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.calendar_declined_events)) },
                    onClick = {
                        expanded = false
                        onOpenDeclined()
                    },
                )
            }
        }

        QuickActionButton(
            label = stringResource(R.string.calendar_join_call),
            icon = Icons.Outlined.VideoCall,
            onClick = onJoinCallClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ZillitTheme.colors.surfaceSunken,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.md, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ZillitTheme.colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The big date panel that heads Day view. */
@Composable
fun DayCard(date: LocalDate, modifier: Modifier = Modifier) {
    val locale = Locale.getDefault()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = ZillitTheme.colors.brand,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale),
                style = MaterialTheme.typography.titleMedium,
                color = ZillitTheme.colors.textPrimary,
            )
            Text(
                text = "${date.month.getDisplayName(TextStyle.FULL, locale)} ${date.year}"
                    .uppercase(locale),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        if (date == LocalDate.now()) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = ZillitTheme.colors.brandSoft,
            ) {
                Text(
                    text = stringResource(R.string.calendar_today_tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.brand,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * One event in the agenda.
 *
 * Cancelled and declined events stay on the list, struck through and faded, rather than
 * disappearing — v2 does the same, and a call that vanished without a trace is worse than
 * one visibly called off.
 */
@Composable
fun AgendaEventRow(
    event: CalendarEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val struck = event.isCancelled || event.isDeclined
    val zone = remember(event.timezone) { DateTime.zoneOf(event.timezone) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (struck) FADED else 1f)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(event.color.toColorOrDefault(ZillitTheme.colors.brand)),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.displayTitle(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = ZillitTheme.colors.textPrimary,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (event.isFullDay) {
                    stringResource(R.string.calendar_all_day)
                } else {
                    formatEventRange(event.startDatetime, event.endDatetime, zone)
                },
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            if (event.locationDescription.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = ZillitTheme.colors.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = event.locationDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * One hour of the day timeline.
 *
 * Every hour renders whether or not it holds anything, so the day reads as a continuous
 * schedule rather than a list of disconnected times.
 */
@Composable
fun HourRow(
    hour: Int,
    events: List<CalendarEvent>,
    onEventClick: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = remember(hour) {
        LocalTime.of(hour, 0).format(DateTime.formatter(DateTime.PATTERN_HOUR_12))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.textTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp).padding(top = ZillitTheme.spacing.sm),
        )

        Column(
            modifier = Modifier.weight(1f).padding(vertical = ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ZillitTheme.colors.divider),
            )
            events.forEach { event -> HourEventChip(event = event, onClick = { onEventClick(event) }) }
        }
    }
}

@Composable
private fun HourEventChip(event: CalendarEvent, onClick: () -> Unit) {
    val struck = event.isCancelled || event.isDeclined
    val color = event.color.toColorOrDefault(ZillitTheme.colors.brand)
    val zone = remember(event.timezone) { DateTime.zoneOf(event.timezone) }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (struck) FADED else 1f)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .heightIn(min = 28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.displayTitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                    textDecoration = if (struck) TextDecoration.LineThrough else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (event.isFullDay) {
                        stringResource(R.string.calendar_all_day)
                    } else {
                        "${event.startDatetime.toEventTime(zone)} – " +
                            event.endDatetime.toEventTime(zone)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
    }
}

/**
 * The title as it should read: prefixed when the occurrence is cancelled or the user
 * declined it, and never blank.
 */
@Composable
private fun CalendarEvent.displayTitle(): String {
    val base = title.ifBlank { stringResource(R.string.calendar_event_untitled) }
    return when {
        isCancelled -> stringResource(R.string.calendar_event_cancelled, base)
        isDeclined -> stringResource(R.string.calendar_event_declined, base)
        else -> base
    }
}

/** v2 fades a cancelled or declined row to 60%. */
private const val FADED = 0.6f

private const val DAYS_IN_WEEK = 7
