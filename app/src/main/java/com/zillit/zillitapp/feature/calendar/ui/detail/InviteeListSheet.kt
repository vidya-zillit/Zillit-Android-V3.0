package com.zillit.zillitapp.feature.calendar.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.model.Invitation
import com.zillit.zillitapp.core.calendar.model.InvitationStatus
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.components.SearchField
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * Who was invited, and what each of them said.
 *
 * Statuses arrive already resolved for the occurrence being viewed — each through that
 * person's **own** overrides. The occurrence-statuses payload belongs to whoever asked for
 * it, so applying it here would show one person's answers as everyone's; v2 shipped that
 * and the whole crew appeared to have the same timeline.
 *
 * Search is local. The list is one event's invitees, already in memory, and a round trip
 * per keystroke would be slower than filtering it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteeListSheet(
    eventId: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    occurrenceStartMs: Long? = null,
    viewModel: InviteeListViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.search.collectAsStateWithLifecycle()

    LaunchedEffect(eventId, occurrenceStartMs) {
        viewModel.load(eventId, occurrenceStartMs)
    }

    val filtered = state.invitees

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ZillitTheme.colors.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = ZillitTheme.spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.calendar_invitees_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ZillitTheme.colors.textPrimary,
            )

            SearchField(
                query = query,
                onQueryChange = viewModel::onSearchChanged,
                placeholder = stringResource(R.string.calendar_search_invitees),
                modifier = Modifier.padding(vertical = ZillitTheme.spacing.sm),
            )

            if (filtered.isEmpty() && state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = ZillitTheme.colors.brand)
                }
            } else if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.calendar_no_invitees),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    contentPadding = PaddingValues(bottom = ZillitTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    items(filtered, key = { it.userId.ifBlank { it.email.orEmpty() } }) { invitee ->
                        InviteeRow(invitee)
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteeRow(invitee: Invitation) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(ZillitTheme.colors.brandSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = invitee.initials(),
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.brand,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                // An external invitee has no directory entry, so their email is their name.
                text = invitee.name.ifBlank { invitee.email.orEmpty() },
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only when it is not already the line above.
            invitee.email?.takeIf { invitee.name.isNotBlank() }?.let { email ->
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The organiser needs to know *why* someone declined, not just that they did.
            invitee.reason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        StatusPill(invitee.status)
    }
}

@Composable
private fun StatusPill(status: InvitationStatus) {
    val (labelRes, color) = when (status) {
        InvitationStatus.PENDING -> R.string.calendar_status_pending to ZillitTheme.colors.warning
        InvitationStatus.ACCEPTED -> R.string.calendar_status_accepted to ZillitTheme.colors.success
        InvitationStatus.REJECTED -> R.string.calendar_status_rejected to ZillitTheme.colors.danger
        InvitationStatus.EXPIRED -> R.string.calendar_expired to ZillitTheme.colors.textTertiary
    }

    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.14f)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun Invitation.initials(): String {
    val source = name.ifBlank { email.orEmpty() }
    return source.trim()
        .split(Regex("[\\s.@]+"))
        .mapNotNull { part -> part.firstOrNull { it.isLetter() }?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }
}
