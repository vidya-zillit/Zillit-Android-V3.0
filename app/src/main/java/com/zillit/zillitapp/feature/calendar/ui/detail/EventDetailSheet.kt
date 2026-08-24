package com.zillit.zillitapp.feature.calendar.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.calendar.model.CallType
import com.zillit.zillitapp.core.calendar.model.EditScope
import com.zillit.zillitapp.core.calendar.model.EventType
import com.zillit.zillitapp.core.calendar.model.InvitationStatus
import com.zillit.zillitapp.core.common.DateTime
import com.zillit.zillitapp.core.common.formatIn
import com.zillit.zillitapp.core.common.toEventTime
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.theme.toColorOrDefault
import com.zillit.zillitapp.feature.calendar.ui.DeleteEventPrompt
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Everything about one event, and the two things you can do about it.
 *
 * Opened from any list or from the agenda. What it shows is the same everywhere; what it
 * *offers* depends on who you are: the creator (or an admin) gets edit and delete, an
 * invitee gets accept and decline, and a cancelled or expired event gets neither — just a
 * banner saying why.
 *
 * @param occurrenceStartMs the occurrence being viewed. Opening a recurring series from its
 *   third occurrence must answer for that one, not for the series.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailSheet(
    eventId: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    occurrenceStartMs: Long? = null,
    viewModel: EventDetailViewModel = hiltViewModel(),
    onEdit: (CalendarEvent) -> Unit = {},
    onDelete: (CalendarEvent) -> Unit = {},
    onJoinCall: (CalendarEvent) -> Unit = {},
    onSeeInvitees: (CalendarEvent) -> Unit = {},
    onChanged: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(eventId, occurrenceStartMs) {
        viewModel.load(eventId, occurrenceStartMs)
    }

    LaunchedEffect(viewModel) {
        viewModel.changed.collect { onChanged() }
    }

    // null = closed; true = accepting; false = declining.
    var answering by remember { mutableStateOf<Boolean?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ZillitTheme.colors.surface,
        modifier = modifier,
    ) {
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = ZillitTheme.colors.brand)
            }

            state.event == null -> Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: stringResource(R.string.calendar_no_events),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                )
            }

            else -> {
                val event = state.event!!

                Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                    DetailHeader(
                        event = event,
                        canManage = state.canManage,
                        onClose = onDismiss,
                        onEdit = { onEdit(event) },
                        onDelete = { confirmingDelete = true },
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = ZillitTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    ) {
                        DetailBody(
                            event = event,
                            creatorName = state.creatorName,
                            inviteeCount = state.invitees.size.takeIf { it > 0 }
                                ?: event.inviteeCount ?: 0,
                            onJoinCall = { onJoinCall(event) },
                            onSeeInvitees = { onSeeInvitees(event) },
                        )
                    }

                    InvitationBar(
                        state = state,
                        onAccept = { answering = true },
                        onDecline = { answering = false },
                    )
                }
            }
        }
    }

    if (confirmingDelete) {
        state.event?.let { event ->
            DeleteEventPrompt(
                event = event,
                onConfirm = { scope ->
                    confirmingDelete = false
                    viewModel.delete(scope)
                    // The sheet describes an event that no longer exists once the whole
                    // series is gone, so it closes with it.
                    if (scope == EditScope.ALL) onDismiss()
                },
                onDismiss = { confirmingDelete = false },
            )
        }
    }

    answering?.let { isAccept ->
        AcceptDeclineSheet(
            isAccept = isAccept,
            isRecurring = state.event?.isRecurring == true,
            onConfirm = { scope, reason ->
                answering = null
                if (isAccept) viewModel.accept(scope) else viewModel.decline(scope, reason)
            },
            onDismiss = { answering = null },
        )
    }
}

@Composable
private fun DetailHeader(
    event: CalendarEvent,
    canManage: Boolean,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // Two rows, as v2 draws them: the actions on top — close on the left, Delete and Edit
    // as named buttons on the right — and the caption under it. Named rather than icons
    // because deleting an event the whole unit can see is not something to offer behind a
    // glyph someone has to guess at.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.action_close),
                tint = ZillitTheme.colors.textPrimary,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (canManage) {
            SecondaryButton(
                text = stringResource(R.string.delete),
                onClick = onDelete,
                fillWidth = false,
            )
            PrimaryButton(
                text = stringResource(R.string.calendar_edit_event),
                onClick = onEdit,
                fillWidth = false,
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Outlined.CalendarMonth,
            contentDescription = null,
            tint = ZillitTheme.colors.brand,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.calendar_event_details),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = ZillitTheme.colors.brand,
            modifier = Modifier.weight(1f),
        )

        if (event.isRecurring) {
            Row(
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
                    text = stringResource(R.string.calendar_recurring),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.brand,
                )
            }
        }
    }
}

@Composable
private fun DetailBody(
    event: CalendarEvent,
    creatorName: String,
    inviteeCount: Int,
    onJoinCall: () -> Unit,
    onSeeInvitees: () -> Unit,
) {
    val zone = remember(event.timezone) { DateTime.zoneOf(event.timezone) }
    val deviceZone = remember { ZoneId.systemDefault() }
    val struck = event.isCancelled || event.isDeclined

    Text(
        text = event.title.ifBlank { stringResource(R.string.calendar_event_untitled) },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = ZillitTheme.colors.textPrimary,
        textDecoration = if (struck) TextDecoration.LineThrough else null,
        modifier = Modifier.padding(top = ZillitTheme.spacing.md),
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ZillitTheme.colors.brandSoft,
    ) {
        Text(
            text = stringResource(
                if (event.type == EventType.PERSONAL) {
                    R.string.calendar_type_personal
                } else {
                    R.string.calendar_type_members
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = ZillitTheme.colors.brand,
            modifier = Modifier.padding(
                horizontal = ZillitTheme.spacing.md,
                vertical = ZillitTheme.spacing.xs,
            ),
        )
    }

    DetailRow(icon = Icons.Outlined.Schedule) {
        Text(
            text = if (event.isFullDay) {
                stringResource(R.string.calendar_all_day)
            } else {
                "${event.startDatetime.toEventTime(zone)} – ${event.endDatetime.toEventTime(zone)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )
        if (!event.isFullDay) {
            Text(
                text = durationLabel(event),
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textTertiary,
            )
        }
        // Named only when it differs from the reader's own zone. v2 rendered the sheet in
        // device time while the card used the event's, so the same event showed two
        // different times; this shows one time and says which zone it is in.
        if (zone != deviceZone) {
            Text(
                text = stringResource(R.string.calendar_timezone_note, zone.id),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.warning,
            )
        }
    }

    DetailRow(icon = Icons.Outlined.CalendarToday) {
        Text(
            text = event.startDatetime.formatIn(zone, DateTime.PATTERN_WEEKDAY_DATE_YEAR),
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )
        Text(
            text = stringResource(R.string.calendar_week_of_year, event.weekOfYear(zone)),
            style = MaterialTheme.typography.bodySmall,
            color = ZillitTheme.colors.textTertiary,
        )
    }

    val hasLocation = event.locationDescription.isNotBlank() ||
        (event.locationLat != null && event.locationLong != null)
    if (hasLocation) {
        val uriHandler = LocalUriHandler.current
        DetailRow(icon = Icons.Outlined.LocationOn) {
            Text(
                text = event.locationDescription.ifBlank {
                    "${event.locationLat}, ${event.locationLong}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.brand,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { uriHandler.openUri(event.mapsUri()) },
            )
        }
    }

    if (event.callType != CallType.NONE) {
        DetailRow(icon = Icons.Outlined.VideoCall) {
            Text(
                text = stringResource(event.callType.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
            )
            if (event.callType.isJoinable && !event.isCancelled && !event.isSeriesExpired()) {
                SecondaryButton(
                    text = stringResource(R.string.calendar_join_call),
                    onClick = onJoinCall,
                    modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
                )
            }
        }
    }

    if (event.notifyMinutes > 0) {
        DetailRow(icon = Icons.Outlined.Alarm) {
            Text(
                text = reminderLabel(event.notifyMinutes),
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
            )
        }
    }

    // Who made it, and when. v2 shows both on every event, and the exclusion tag beside
    // the name is the only place the organiser's own opt-out is visible after the fact.
    if (creatorName.isNotBlank()) {
        DetailRow(icon = Icons.Outlined.People) {
            Text(
                text = stringResource(R.string.calendar_created_by),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.textTertiary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                Text(
                    text = creatorName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ZillitTheme.colors.textPrimary,
                )
                if (event.createUserExclude) {
                    Text(
                        text = stringResource(R.string.calendar_creator_excluded),
                        style = MaterialTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textTertiary,
                    )
                }
            }
        }
    }

    if (event.createdAt > 0) {
        DetailRow(icon = Icons.Outlined.CalendarMonth) {
            Text(
                text = stringResource(R.string.calendar_created_on),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.textTertiary,
            )
            Text(
                // Date then time, as v2 prints it. In the device's own zone: when the
                // event was made is a fact about the user's clock, not the event's.
                text = "${event.createdAt.formatIn(deviceZone, DateTime.PATTERN_DATE)} " +
                    event.createdAt.toEventTime(deviceZone),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = ZillitTheme.colors.textPrimary,
            )
        }
    }

    if (event.description.isNotBlank()) {
        LabelledSection(label = stringResource(R.string.calendar_description)) {
            Text(
                text = event.description,
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }

    LabelledSection(label = stringResource(R.string.calendar_event_color)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(
                        event.color.toColorOrDefault(ZillitTheme.colors.brand),
                        RoundedCornerShape(4.dp),
                    ),
            )
            Text(
                text = event.color,
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textTertiary,
            )
        }
    }

    if (inviteeCount > 0) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ZillitTheme.colors.surfaceSunken,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSeeInvitees),
        ) {
            Row(
                modifier = Modifier.padding(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = androidx.compose.ui.res.pluralStringResource(
                            R.plurals.calendar_invitees,
                            inviteeCount,
                            inviteeCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ZillitTheme.colors.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.calendar_tap_to_view_invitees),
                        style = MaterialTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textTertiary,
                    )
                }
                Text(
                    text = stringResource(R.string.calendar_see_invitees),
                    style = MaterialTheme.typography.labelLarge,
                    color = ZillitTheme.colors.brand,
                )
            }
        }
    }

    Box(modifier = Modifier.padding(bottom = ZillitTheme.spacing.md))
}

/**
 * The bar along the bottom: what my answer currently is, and how to change it.
 *
 * Cancelled and expired states replace the buttons with a reason rather than disabling
 * them — a greyed-out Accept invites a tap that will never do anything.
 */
@Composable
private fun InvitationBar(
    state: EventDetailState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val event = state.event ?: return

    val notice: Pair<Int, Color>? = when {
        event.isCancelled ->
            R.string.calendar_event_cancelled_notice to ZillitTheme.colors.danger

        event.isSeriesExpired() ->
            R.string.calendar_event_expired_notice to ZillitTheme.colors.textTertiary

        else -> null
    }

    if (notice != null) {
        Surface(color = notice.second.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(notice.first),
                style = MaterialTheme.typography.bodyMedium,
                color = notice.second,
                modifier = Modifier.padding(ZillitTheme.spacing.lg),
            )
        }
        return
    }

    // The creator is not an invitee to their own event, and someone who was never invited
    // has nothing to answer.
    if (state.myInvitation == null || state.isCreator) return

    val (labelRes, showAccept, showDecline) = when (state.resolvedStatus) {
        InvitationStatus.ACCEPTED -> Triple(R.string.calendar_you_accepted, false, true)
        InvitationStatus.REJECTED -> Triple(R.string.calendar_you_declined, true, false)
        else -> Triple(R.string.calendar_pending_invitation, true, true)
    }

    HorizontalDivider(color = ZillitTheme.colors.divider)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            if (showDecline) {
                SecondaryButton(
                    text = stringResource(R.string.calendar_decline),
                    onClick = onDecline,
                    enabled = !state.isActing,
                    modifier = Modifier.weight(1f),
                )
            }
            if (showAccept) {
                PrimaryButton(
                    text = stringResource(R.string.calendar_accept),
                    onClick = onAccept,
                    loading = state.isActing,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnContent,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(18.dp).padding(top = 2.dp),
        )
        Column(content = content)
    }
}

private typealias ColumnContent = androidx.compose.foundation.layout.ColumnScope.() -> Unit

@Composable
private fun LabelledSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.textTertiary,
        )
        content()
    }
}

/**
 * `Duration: 1 hour` / `Duration: 1.30 hours` / `Duration: 45 minutes`.
 *
 * v2 phrases it the same way but always pluralises, so a one-hour event read
 * "Duration: 1 hours". Pluralised properly here — that is a typo, not approved copy.
 */
@Composable
private fun durationLabel(event: CalendarEvent): String {
    val totalMinutes = ((event.endDatetime - event.startDatetime) / 60_000L).coerceAtLeast(0)
    val hours = (totalMinutes / MINUTES_PER_HOUR).toInt()
    val minutes = (totalMinutes % MINUTES_PER_HOUR).toInt()

    return if (hours > 0) {
        val value = if (minutes > 0) "$hours.$minutes" else "$hours"
        // Quantity keys off whole hours: 1.30 still reads "hours".
        val unit = androidx.compose.ui.res.pluralStringResource(
            R.plurals.calendar_duration_hour_unit,
            if (minutes > 0) hours + 1 else hours,
            value,
        )
        stringResource(R.string.calendar_duration_hours, unit)
    } else {
        androidx.compose.ui.res.pluralStringResource(
            R.plurals.calendar_duration_minutes,
            minutes,
            minutes,
        )
    }
}

@Composable
private fun reminderLabel(minutes: Int): String = if (minutes == MINUTES_PER_HOUR) {
    stringResource(R.string.calendar_reminder_hour)
} else {
    stringResource(R.string.calendar_reminder_minutes, minutes)
}

private val CallType.labelRes: Int
    get() = when (this) {
        CallType.AUDIO -> R.string.calendar_call_audio
        CallType.VIDEO -> R.string.calendar_call_video
        CallType.MEET_IN_PERSON -> R.string.calendar_call_in_person
        CallType.MEET_IN_PERSON_CALL -> R.string.calendar_call_in_person_call
        CallType.NONE -> R.string.calendar_call_in_person
    }

/**
 * Maps link: coordinates when the event has them, otherwise a text search on the address.
 * A written address is often all a location has, and it still finds the place.
 */
private fun CalendarEvent.mapsUri(): String {
    val lat = locationLat
    val lng = locationLong
    return if (lat != null && lng != null) {
        "https://maps.google.com/?q=$lat,$lng"
    } else {
        "https://maps.google.com/?q=${android.net.Uri.encode(locationDescription)}"
    }
}

private fun CalendarEvent.weekOfYear(zone: ZoneId): Int =
    java.time.Instant.ofEpochMilli(startDatetime)
        .atZone(zone)
        .toLocalDate()
        .get(WeekFields.of(Locale.getDefault()).weekOfYear())

private const val MINUTES_PER_HOUR = 60
