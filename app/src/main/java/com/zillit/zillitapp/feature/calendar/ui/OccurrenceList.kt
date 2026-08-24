package com.zillit.zillitapp.feature.calendar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.calendar.model.InvitationStatus
import com.zillit.zillitapp.core.calendar.model.InvitationStatusResolver
import com.zillit.zillitapp.core.calendar.model.OccurrenceStatuses
import com.zillit.zillitapp.core.common.DateTime
import com.zillit.zillitapp.core.common.formatEventRange
import com.zillit.zillitapp.core.common.formatIn
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/** What the expandable section of a recurring card is currently showing. */
sealed interface OccurrencesState {
    data object Collapsed : OccurrencesState
    data object Loading : OccurrencesState
    data class Loaded(
        val occurrences: List<CalendarEvent>,
        val statuses: OccurrenceStatuses?,
    ) : OccurrencesState
}

/**
 * The dates a recurring series actually falls on, listed under its card.
 *
 * A series' card shows one row for the whole thing, which is right until you need to know
 * whether *this* Thursday is still on. Each occurrence carries its own state — one can be
 * cancelled, moved, or declined while the rest stand — so the list shows each one's status
 * separately rather than the series'.
 *
 * Every status here is resolved for that occurrence's own date, so a user who declined a
 * single date sees it marked declined while the rest stay accepted.
 */
@Composable
fun OccurrenceSection(
    state: OccurrencesState,
    onToggle: () -> Unit,
    onOccurrenceClick: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                if (state is OccurrencesState.Collapsed) {
                    R.string.calendar_view_occurrences
                } else {
                    R.string.calendar_hide_occurrences
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            color = ZillitTheme.colors.brand,
            modifier = Modifier
                .clickable(onClick = onToggle)
                .padding(vertical = ZillitTheme.spacing.sm),
        )

        when (state) {
            OccurrencesState.Collapsed -> Unit

            OccurrencesState.Loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(ZillitTheme.spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = ZillitTheme.colors.brand,
                    modifier = Modifier.size(20.dp),
                )
            }

            is OccurrencesState.Loaded -> {
                if (state.occurrences.isEmpty()) {
                    Text(
                        text = stringResource(R.string.calendar_no_events),
                        style = MaterialTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textTertiary,
                        modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        state.occurrences.forEach { occurrence ->
                            OccurrenceRow(
                                occurrence = occurrence,
                                statuses = state.statuses,
                                onClick = { onOccurrenceClick(occurrence) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OccurrenceRow(
    occurrence: CalendarEvent,
    statuses: OccurrenceStatuses?,
    onClick: () -> Unit,
) {
    val cancelled = occurrence.isCancelled
    val struck = cancelled || occurrence.isDeclined
    val expired = occurrence.endDatetime < System.currentTimeMillis()
    val zone = remember(occurrence.timezone) { DateTime.zoneOf(occurrence.timezone) }

    // Resolved from the caller-scoped payload, which is the only thing that knows what was
    // answered for this one date.
    val status = remember(occurrence, statuses) {
        statuses?.let {
            InvitationStatusResolver.resolve(
                invitation = null,
                occurrenceDate = occurrence.startDatetime,
                occurrenceStatuses = it,
                isRecurring = true,
            )
        }
    }

    HorizontalDivider(color = ZillitTheme.colors.divider)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (struck) FADED_OCCURRENCE else 1f)
            .padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (occurrence.isFullDay) {
                    occurrence.startDatetime.formatIn(zone, DateTime.PATTERN_WEEKDAY_DATE)
                } else {
                    formatEventRange(occurrence.startDatetime, occurrence.endDatetime, zone)
                },
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textPrimary,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A title that no longer matches the series means this date was edited on its
            // own — worth saying, because the card above still shows the series' title.
            if (!occurrence.isVirtual && occurrence.title.isNotBlank()) {
                Text(
                    text = occurrence.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Cancelled first, then expired, then whatever was answered — one chip only, so a
        // cancelled date never also advertises that you accepted it.
        val chip: Pair<Int, androidx.compose.ui.graphics.Color>? = when {
            cancelled -> R.string.calendar_cancelled to ZillitTheme.colors.danger
            expired -> R.string.calendar_expired to ZillitTheme.colors.textTertiary
            status != null -> status.chip()
            else -> null
        }

        chip?.let { (labelRes, color) ->
            Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.14f)) {
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun InvitationStatus.chip(): Pair<Int, androidx.compose.ui.graphics.Color> = when (this) {
    InvitationStatus.PENDING -> R.string.calendar_status_pending to ZillitTheme.colors.warning
    InvitationStatus.ACCEPTED -> R.string.calendar_status_accepted to ZillitTheme.colors.success
    InvitationStatus.REJECTED -> R.string.calendar_status_rejected to ZillitTheme.colors.danger
    InvitationStatus.EXPIRED -> R.string.calendar_expired to ZillitTheme.colors.textTertiary
}

private const val FADED_OCCURRENCE = 0.6f
