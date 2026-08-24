package com.zillit.zillitapp.feature.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.calendar.model.InvitationStatus
import com.zillit.zillitapp.core.calendar.model.RecurrenceFrequency
import com.zillit.zillitapp.core.common.DateTime
import com.zillit.zillitapp.core.common.formatEventRange
import com.zillit.zillitapp.core.common.formatIn
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.theme.toColorOrDefault

/**
 * What a given list wants the card to offer.
 *
 * Every list shows the same event; they differ only in which controls make sense. Received
 * offers accept and reject, Created offers edit and delete, Join Call offers neither. This
 * is one object rather than eight booleans on the composable so a new list declares its
 * intent in one place.
 */
data class EventCardOptions(
    /** Show the invitation status chip. */
    val showStatus: Boolean = false,
    /** Show the invitee count and the See Invitees action. */
    val showInviteeCount: Boolean = false,
    /** Offer edit and delete — for events this user created. */
    val showCreatorActions: Boolean = false,
    /** Offer accept and reject. */
    val showInviteeActions: Boolean = false,
    /** List who declined, with their reasons. Creator-only. */
    val showDecliners: Boolean = false,
)

/** Everything a card can do. Unhandled actions simply stay off the card. */
data class EventCardCallbacks(
    val onClick: (CalendarEvent) -> Unit = {},
    val onEdit: ((CalendarEvent) -> Unit)? = null,
    val onDelete: ((CalendarEvent) -> Unit)? = null,
    val onAccept: ((CalendarEvent) -> Unit)? = null,
    val onReject: ((CalendarEvent) -> Unit)? = null,
    val onJoinCall: ((CalendarEvent) -> Unit)? = null,
    val onSeeInvitees: ((CalendarEvent) -> Unit)? = null,
)

/**
 * One event, as every list renders it.
 *
 * The rules about *which* controls appear are the interesting part, and they are carried
 * over from v2 exactly, because each one exists to stop a specific wrong action:
 *
 *  - Nothing is actionable on a cancelled or expired event.
 *  - Accept disappears once accepted; reject disappears once rejected.
 *  - Join Call follows the call type, not the list — but never appears for someone who
 *    declined, since offering to join a call you turned down is nonsense.
 *  - Edit and delete are withheld from box-schedule events: that module owns them.
 *
 * @param status this user's status when the list knows it better than the event does — the
 *   Received list carries it on the invitation rather than inside the event.
 */
@Composable
fun EventCard(
    event: CalendarEvent,
    options: EventCardOptions,
    callbacks: EventCardCallbacks,
    modifier: Modifier = Modifier,
    status: InvitationStatus? = null,
    /**
     * The expandable list of dates under a recurring card. Null hides it entirely, which
     * is what the agenda and Join Call want — they already show one date each.
     */
    occurrences: OccurrencesState? = null,
    onToggleOccurrences: (() -> Unit)? = null,
    onOccurrenceClick: (CalendarEvent) -> Unit = {},
    /** Shown only where the viewer is the creator — the Declined → Created list. */
    showDecliners: Boolean = false,
    onRequestReason: ((com.zillit.zillitapp.core.calendar.model.Decliner) -> Unit)? = null,
) {
    val expired = remember(event) { event.isSeriesExpired() }
    val cancelled = event.isCancelled
    val struck = cancelled || event.isDeclined
    val zone = remember(event.timezone) { DateTime.zoneOf(event.timezone) }
    val accent = event.color.toColorOrDefault(ZillitTheme.colors.brand)

    val effectiveStatus = status ?: event.invitationStatus

    // Neither an event that is over nor one that was called off can be acted on.
    val actionable = !expired && !cancelled

    val canEdit = options.showCreatorActions && actionable && !event.isFromBoxSchedule
    val showAccept = options.showInviteeActions && actionable &&
        effectiveStatus != InvitationStatus.ACCEPTED
    val showReject = options.showInviteeActions && actionable &&
        effectiveStatus != InvitationStatus.REJECTED
    val showJoin = event.callType.isJoinable && actionable &&
        effectiveStatus != InvitationStatus.REJECTED && !event.isDeclined

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ZillitTheme.colors.surface,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (struck) FADED else 1f)
            .clickable { callbacks.onClick(event) },
    ) {
        Column(modifier = Modifier.padding(ZillitTheme.spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent),
                )

                Text(
                    text = event.displayTitle(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ZillitTheme.colors.textPrimary,
                    textDecoration = if (struck) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = ZillitTheme.spacing.sm),
                )

                if (canEdit) {
                    callbacks.onEdit?.let { edit ->
                        CardIconButton(
                            icon = Icons.Outlined.Edit,
                            description = stringResource(R.string.option_edit),
                            onClick = { edit(event) },
                        )
                    }
                    callbacks.onDelete?.let { delete ->
                        CardIconButton(
                            icon = Icons.Outlined.Delete,
                            description = stringResource(R.string.delete),
                            onClick = { delete(event) },
                            tint = ZillitTheme.colors.danger,
                        )
                    }
                }
            }

            // One chip only, in priority order. Cancellation already reads from the struck
            // title, and a recurring master has no single status — its occurrences each
            // have their own — so both suppress the chip rather than showing a misleading
            // one for the series.
            val chip: @Composable (() -> Unit)? = when {
                cancelled -> null
                event.isRecurring -> null
                expired -> {
                    { StatusChip(stringResource(R.string.calendar_expired), ZillitTheme.colors.danger) }
                }

                options.showStatus && effectiveStatus != null -> {
                    { InvitationStatusChip(effectiveStatus) }
                }

                else -> null
            }

            if (chip != null || event.createUserExclude) {
                Row(
                    modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    chip?.invoke()
                    if (event.createUserExclude) {
                        StatusChip(
                            text = stringResource(R.string.calendar_excluded),
                            color = ZillitTheme.colors.textTertiary,
                        )
                    }
                }
            }

            Text(
                text = if (event.isFullDay) {
                    "${event.startDatetime.formatIn(zone, DateTime.PATTERN_WEEKDAY_DATE_YEAR)} " +
                        stringResource(R.string.calendar_all_day_suffix)
                } else {
                    formatEventRange(event.startDatetime, event.endDatetime, zone)
                },
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
            )

            if (event.isRecurring) {
                RecurrenceLine(event = event, zone = zone)
            }

            if (event.locationDescription.isNotBlank()) {
                Row(
                    modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
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

            val inviteeCount = event.inviteeCount ?: 0
            if (options.showInviteeCount && inviteeCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pluralInvitees(inviteeCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    callbacks.onSeeInvitees?.let { see ->
                        Text(
                            text = stringResource(R.string.calendar_see_invitees),
                            style = MaterialTheme.typography.labelLarge,
                            color = ZillitTheme.colors.brand,
                            modifier = Modifier.clickable { see(event) },
                        )
                    }
                }
            }

            if (showDecliners) {
                DeclinerSection(
                    decliners = event.decliners,
                    totalCount = event.distinctDeclinerCount ?: event.decliners.size,
                    onRequestReason = onRequestReason,
                )
            }

            // Only a series has occurrences to list, and only a host that supplied a
            // toggle wants them shown.
            if (event.isRecurring && occurrences != null && onToggleOccurrences != null) {
                OccurrenceSection(
                    state = occurrences,
                    onToggle = onToggleOccurrences,
                    onOccurrenceClick = onOccurrenceClick,
                )
            }

            if (showAccept || showReject || showJoin) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = ZillitTheme.spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    if (showJoin) {
                        callbacks.onJoinCall?.let { join ->
                            SecondaryButton(
                                text = stringResource(R.string.calendar_join_call),
                                onClick = { join(event) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (showReject) {
                        callbacks.onReject?.let { reject ->
                            SecondaryButton(
                                text = stringResource(R.string.calendar_reject),
                                onClick = { reject(event) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (showAccept) {
                        callbacks.onAccept?.let { accept ->
                            SecondaryButton(
                                text = stringResource(R.string.calendar_accept),
                                onClick = { accept(event) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecurrenceLine(event: CalendarEvent, zone: java.time.ZoneId) {
    val label = when (event.recurrence.frequency) {
        RecurrenceFrequency.DAILY -> stringResource(R.string.calendar_repeat_daily)
        RecurrenceFrequency.WEEKLY -> stringResource(R.string.calendar_repeat_weekly)
        RecurrenceFrequency.MONTHLY -> stringResource(R.string.calendar_repeat_monthly)
        RecurrenceFrequency.YEARLY -> stringResource(R.string.calendar_repeat_yearly)
        RecurrenceFrequency.CUSTOM -> stringResource(R.string.calendar_repeat_custom)
        // An expanded occurrence carries no rule of its own, only a parent.
        RecurrenceFrequency.NONE -> stringResource(R.string.calendar_repeat_recurring)
    }

    Row(
        modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Outlined.Repeat,
            contentDescription = null,
            tint = ZillitTheme.colors.brand,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.brand,
        )
        event.recurrence.until?.let { until ->
            Text(
                text = stringResource(
                    R.string.calendar_repeat_until,
                    until.formatIn(zone, DateTime.PATTERN_DATE),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun InvitationStatusChip(status: InvitationStatus) {
    val (textRes, color) = when (status) {
        InvitationStatus.PENDING -> R.string.calendar_status_pending to ZillitTheme.colors.warning
        InvitationStatus.ACCEPTED -> R.string.calendar_status_accepted to ZillitTheme.colors.success
        InvitationStatus.REJECTED -> R.string.calendar_status_rejected to ZillitTheme.colors.danger
        InvitationStatus.EXPIRED -> R.string.calendar_expired to ZillitTheme.colors.textTertiary
    }
    StatusChip(text = stringResource(textRes), color = color)
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CardIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = ZillitTheme.colors.textSecondary,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun pluralInvitees(count: Int): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.calendar_invitees, count, count)

/** Same rule as the agenda: cancelled wins over declined, and neither is ever blank. */
@Composable
private fun CalendarEvent.displayTitle(): String {
    val base = title.ifBlank { stringResource(R.string.calendar_event_untitled) }
    return when {
        isCancelled -> stringResource(R.string.calendar_event_cancelled, base)
        isDeclined -> stringResource(R.string.calendar_event_declined, base)
        else -> base
    }
}

private const val FADED = 0.6f
