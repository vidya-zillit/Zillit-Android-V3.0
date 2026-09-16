package com.zillit.zillitapp.feature.cnc.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * One call, opened from its row in the log.
 *
 * The log answers "did anybody call me"; this answers the question that follows, which on a
 * group call is always the same one — who actually joined. A row can only say "Group call"
 * and a time, so without this a missed group call is indistinguishable from one where
 * everybody turned up.
 *
 * A sheet rather than a screen: it is read and dismissed, and it has nothing to navigate to
 * while calling itself is deferred.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailsSheet(
    details: CallDetails,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ZillitTheme.colors.surface,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ZillitTheme.spacing.lg,
                        vertical = ZillitTheme.spacing.md,
                    ),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(
                    initials = details.initials,
                    pictureKey = details.pictureKey,
                    thumbnailKey = details.thumbnailKey,
                    size = 44.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = details.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ZillitTheme.colors.textPrimary,
                    )
                    // Time first, then how long it ran. A call with no duration never
                    // connected, and saying nothing is clearer than "0 sec".
                    Text(
                        text = listOf(details.startedAt, details.duration)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                Icon(
                    imageVector = if (details.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Call,
                    contentDescription = null,
                    tint = details.direction.tint(),
                    modifier = Modifier.size(20.dp),
                )
            }

            if (details.participants.isNotEmpty()) {
                HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(details.participants, key = { it.userId }) { person ->
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
                                size = 32.dp,
                            )
                            Text(
                                text = person.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ZillitTheme.colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = stringResource(person.outcome.labelRes()),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = person.outcome.tint(),
                                )
                                if (person.duration.isNotBlank()) {
                                    Text(
                                        text = person.duration,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ZillitTheme.colors.textTertiary,
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

private fun CallOutcome.labelRes(): Int = when (this) {
    CallOutcome.CALLER -> R.string.cnc_call_started
    CallOutcome.JOINED -> R.string.cnc_call_joined
    CallOutcome.DECLINED -> R.string.cnc_call_declined
    CallOutcome.MISSED -> R.string.cnc_call_was_missed
    CallOutcome.NO_ANSWER -> R.string.cnc_call_no_answer
}

/** Green for people who were on it, red for a decline, grey for the rest. */
@Composable
private fun CallOutcome.tint(): Color = when (this) {
    CallOutcome.CALLER, CallOutcome.JOINED -> ZillitTheme.colors.success
    CallOutcome.DECLINED, CallOutcome.MISSED -> ZillitTheme.colors.danger
    CallOutcome.NO_ANSWER -> ZillitTheme.colors.textTertiary
}

@Composable
private fun CallDirection.tint(): Color = when (this) {
    CallDirection.MISSED -> ZillitTheme.colors.danger
    else -> ZillitTheme.colors.textSecondary
}
