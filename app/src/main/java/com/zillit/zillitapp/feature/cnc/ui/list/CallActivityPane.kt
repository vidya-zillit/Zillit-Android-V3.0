package com.zillit.zillitapp.feature.cnc.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhoneMissed
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * One call's activity, beside the log — the web client's "Call activity" panel.
 *
 * The same answer the phone's sheet gives, laid out for a pane that stays open while the
 * log is browsed: what kind of call it was, which way it went, when and for how long, and
 * then every person on it with what they actually did.
 *
 * Why a pane and not the sheet at a wider size: the question this answers is usually asked
 * of several calls in a row ("who was on that one? and that one?"), and a sheet has to be
 * dismissed before the next row can be tapped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallActivityPane(
    /** Null while the details are still being assembled, or if the call has gone. */
    details: CallDetails?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (details == null) {
        CallActivityPlaceholder(modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ZillitTheme.spacing.lg,
                    end = ZillitTheme.spacing.sm,
                    top = ZillitTheme.spacing.md,
                    bottom = ZillitTheme.spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.cnc_call_activity),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    tint = ZillitTheme.colors.textSecondary,
                )
            }
        }

        CallActivityHeader(details)

        HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)

        if (details.participants.isEmpty()) {
            // A one-to-one call the server sent no attendance for. The header already says
            // everything there is to say, so an empty "0 participants" heading would only
            // look like something failed to load.
            return@Column
        }

        Text(
            text = pluralStringResource(
                R.plurals.cnc_call_participants,
                details.participants.size,
                details.participants.size,
            ).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = ZillitTheme.colors.textTertiary,
            modifier = Modifier.padding(
                horizontal = ZillitTheme.spacing.lg,
                vertical = ZillitTheme.spacing.md,
            ),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(details.participants, key = { it.userId }) { person ->
                CallParticipantRow(person)
            }
        }
    }
}

/** The call itself: who or what it was with, its kind, and the three facts about it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallActivityHeader(details: CallDetails) {
    Column(
        modifier = Modifier.padding(
            horizontal = ZillitTheme.spacing.lg,
            vertical = ZillitTheme.spacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(ZillitTheme.spacing.sm))
                    .background(ZillitTheme.colors.surfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (details.isVideo) {
                        Icons.Outlined.Videocam
                    } else {
                        Icons.Outlined.Call
                    },
                    contentDescription = null,
                    tint = ZillitTheme.colors.accent,
                    modifier = Modifier.size(24.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = details.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ZillitTheme.colors.textPrimary,
                )
                Text(
                    text = stringResource(
                        if (details.isVideo) R.string.cnc_call_video else R.string.cnc_call_audio,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }

        // Direction, when, how long — as separate chips rather than one run-on line,
        // because each is looked for on its own. Wrapped, so a long date does not push the
        // duration off a narrow pane.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            CallFactChip(
                text = stringResource(details.direction.labelRes()),
                icon = details.direction.icon(),
                tint = details.direction.tint(),
            )
            if (details.startedAt.isNotBlank()) CallFactChip(text = details.startedAt)
            // A call with no duration never connected, and saying nothing is clearer than
            // "0 sec".
            if (details.duration.isNotBlank()) CallFactChip(text = details.duration)
        }
    }
}

@Composable
private fun CallFactChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tint: Color = ZillitTheme.colors.textSecondary,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(ZillitTheme.colors.surfaceElevated)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = ZillitTheme.colors.textPrimary,
        )
    }
}

/**
 * One person's part in the call.
 *
 * The left side says what they did, the right side says when — which is the split the web
 * client uses, and it is the right one: the outcome is what you scan for, the time is what
 * you check once you have found the person.
 */
@Composable
private fun CallParticipantRow(person: CallParticipant) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ZillitTheme.spacing.lg,
                vertical = ZillitTheme.spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(
            initials = person.initials,
            pictureKey = person.pictureKey,
            thumbnailKey = person.thumbnailKey,
            size = 40.dp,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = person.name,
                style = MaterialTheme.typography.bodyLarge,
                color = ZillitTheme.colors.textPrimary,
            )
            // The caller's row leads with what they did; everyone else's leads with how
            // long they were actually connected, which is the thing a duration answers.
            val subtitle = if (person.outcome == CallOutcome.CALLER) {
                stringResource(R.string.cnc_call_started)
            } else {
                person.duration
                    .takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.cnc_call_in_call, it) }
                    .orEmpty()
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }

        Text(
            text = person.outcome.rightLabel(person),
            style = MaterialTheme.typography.labelLarge,
            color = person.outcome.tint(),
        )
    }
}

/** The right-hand marker: "Host" for whoever started it, otherwise how they answered. */
@Composable
private fun CallOutcome.rightLabel(person: CallParticipant): String = when (this) {
    CallOutcome.CALLER -> stringResource(R.string.cnc_call_host)
    CallOutcome.JOINED -> person.joinedAt
        ?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.cnc_call_joined_at, it) }
        ?: stringResource(R.string.cnc_call_joined)

    CallOutcome.DECLINED -> stringResource(R.string.cnc_call_declined)
    CallOutcome.MISSED -> stringResource(R.string.cnc_call_was_missed)
    CallOutcome.NO_ANSWER -> stringResource(R.string.cnc_call_no_answer)
}

/** Orange for the host, green for people who were on it, red for a decline, grey otherwise. */
@Composable
private fun CallOutcome.tint(): Color = when (this) {
    CallOutcome.CALLER -> ZillitTheme.colors.accent
    CallOutcome.JOINED -> ZillitTheme.colors.success
    CallOutcome.DECLINED, CallOutcome.MISSED -> ZillitTheme.colors.danger
    CallOutcome.NO_ANSWER -> ZillitTheme.colors.textTertiary
}

private fun CallDirection.labelRes(): Int = when (this) {
    CallDirection.OUTGOING -> R.string.cnc_call_outgoing
    CallDirection.INCOMING -> R.string.cnc_call_incoming
    CallDirection.MISSED -> R.string.cnc_call_missed
}

private fun CallDirection.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    CallDirection.OUTGOING -> Icons.Outlined.ArrowUpward
    CallDirection.INCOMING -> Icons.Outlined.ArrowDownward
    CallDirection.MISSED -> Icons.Outlined.PhoneMissed
}

@Composable
private fun CallDirection.tint(): Color = when (this) {
    CallDirection.MISSED -> ZillitTheme.colors.danger
    else -> ZillitTheme.colors.textSecondary
}

@Composable
private fun CallActivityPlaceholder(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Outlined.Call,
        title = stringResource(R.string.cnc_call_pane_empty_title),
        description = stringResource(R.string.cnc_call_pane_empty_description),
        modifier = modifier,
    )
}
