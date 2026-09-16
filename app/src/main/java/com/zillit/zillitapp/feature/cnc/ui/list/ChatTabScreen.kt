package com.zillit.zillitapp.feature.cnc.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.SearchField
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.labels.asLabelIfKey
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.size

/**
 * The Chat tab: everything you talk to, filtered.
 *
 * The filters narrow one list rather than swapping screens, so the unread you just spotted
 * does not move when you switch.
 */
@Composable
fun ChatTabScreen(
    state: CncUiState,
    onFilter: (ChatFilter) -> Unit,
    onQuery: (String) -> Unit,
    onOpen: (ConversationRow) -> Unit,
    onToggleFavourite: (ConversationRow) -> Unit,
    onCreateGroup: () -> Unit,
    modifier: Modifier = Modifier,
    /** Long press on a group row. v2 reaches Leave and Delete the same way. */
    onRowActions: (ConversationRow) -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            CncChipRow(modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm)) {
                // What this project offers, not every value of the enum: a personal project
                // has no departments and a pending member has no groups yet.
                state.availableChatFilters.forEach { filter ->
                    CncChip(
                        label = stringResource(filter.labelRes()),
                        selected = state.chatFilter == filter,
                        onClick = { onFilter(filter) },
                        // Only Unread carries a number; the rest are plain filters.
                        count = if (filter == ChatFilter.UNREAD) state.unreadTotal else 0,
                    )
                }
            }

            SearchField(
                query = state.query,
                onQueryChange = onQuery,
                placeholder = stringResource(R.string.action_search),
                modifier = Modifier.padding(
                    horizontal = ZillitTheme.spacing.lg,
                    vertical = ZillitTheme.spacing.xs,
                ),
            )

            when {
                state.loading && state.conversations.isEmpty() -> LoadingState()

                state.conversations.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Chat,
                    title = stringResource(
                        if (state.hasQuery) R.string.cnc_no_match else R.string.cnc_no_chats,
                    ),
                    description = "",
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(state.conversations, key = { it.id }) { row ->
                        ConversationRowItem(
                            row = row,
                            onClick = { onOpen(row) },
                            onLongClick = { onRowActions(row) },
                            onToggleFavourite = { onToggleFavourite(row) },
                        )
                        HorizontalDivider(
                            color = ZillitTheme.colors.divider,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 68.dp),
                        )
                    }
                }
            }
        }

        // Always reachable, rather than appearing and vanishing with the filter as v2's does.
        if (state.canCreateGroup) {
            FloatingActionButton(
                onClick = onCreateGroup,
                containerColor = ZillitTheme.colors.brand,
                contentColor = ZillitTheme.colors.textOnBrand,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(ZillitTheme.spacing.lg),
            ) {
                Icon(Icons.Outlined.GroupAdd, contentDescription = stringResource(R.string.cnc_new_group))
            }
        }
    }
}

/**
 * One conversation.
 *
 * Time, unread and the star stack in a single right-hand column so the list has a straight
 * edge — v2 floats the star above a date at a different height, and the rows read ragged.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ConversationRowItem(
    row: ConversationRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceAvatar(
            initials = row.initials,
            online = row.online,
            pictureKey = row.pictureKey,
            thumbnailKey = row.thumbnailKey,
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // v2 writes "Sanjeev Khanna - Admin" into the name itself; kept as a
                // separate span so the name can ellipsize without eating the suffix.
                if (row.isProjectAdmin) {
                    Text(
                        text = stringResource(R.string.cnc_admin_suffix),
                        style = MaterialTheme.typography.labelMedium,
                        color = ZillitTheme.colors.textTertiary,
                    )
                }
            }
            Text(
                text = row.subtitle.asLabelIfKey(),
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.lastActivity.isNotBlank()) {
                Text(
                    text = row.lastActivity,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            UnreadDot(row.unread)
            FavouriteButton(
                favourite = row.favourite,
                onClick = onToggleFavourite,
                contentDescription = row.name,
            )
        }
    }
}

private fun ChatFilter.labelRes(): Int = when (this) {
    ChatFilter.ALL -> R.string.cnc_filter_all
    ChatFilter.UNREAD -> R.string.cnc_filter_unread
    ChatFilter.MEMBERS -> R.string.cnc_filter_members
    ChatFilter.GROUPS -> R.string.cnc_filter_groups
    ChatFilter.DEPARTMENTS -> R.string.cnc_filter_departments
    ChatFilter.FAVOURITES -> R.string.cnc_filter_favourites
}

/**
 * What a long press on a group row offers.
 *
 * Only the entries the viewer may actually use, so the menu never presents an action that
 * turns out to be refused. Opening the group is always offered: it is where renaming, the
 * photo and the member list live, and this is the quickest way to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationActionsSheet(
    actions: ConversationRowActions,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ZillitTheme.colors.surface,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                text = actions.name,
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.padding(
                    horizontal = ZillitTheme.spacing.lg,
                    vertical = ZillitTheme.spacing.sm,
                ),
            )
            HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)

            if (actions.canLeave) {
                ActionRow(
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    label = stringResource(R.string.cnc_leave_group),
                    onClick = onLeave,
                )
            }
            if (actions.canDelete) {
                ActionRow(
                    icon = Icons.Outlined.DeleteOutline,
                    label = stringResource(R.string.cnc_delete_group),
                    destructive = true,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint = if (destructive) ZillitTheme.colors.danger else ZillitTheme.colors.textPrimary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}
