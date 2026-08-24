package com.zillit.zillitapp.feature.notification.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.common.toShortDateTimeLabel
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import com.zillit.zillitapp.core.ui.text.fromSimpleHtml
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * The project-wide notification list, opened from the Zillit logo.
 *
 * Deleting is per-row **and** all-at-once, both behind a confirmation — v2 asks the same
 * two questions with the same words. A notification is the only record that something
 * happened, so removing one is not undoable.
 */
@Composable
fun NotificationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotificationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val pendingDeleteAll by viewModel.pendingDeleteAll.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    // Pages when the last row comes into view, rather than on a fixed offset — the rows
    // are variable height, so a pixel threshold would fire at the wrong time.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (last != null && last >= state.notifications.lastIndex) viewModel.loadOlder()
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surface)
                .padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_close),
                tint = ZillitTheme.colors.textPrimary,
                modifier = Modifier.size(24.dp).clickable(onClick = onBack),
            )
            Text(
                text = stringResource(R.string.txt_notification),
                style = MaterialTheme.typography.titleMedium,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )

            // Delete All is only offered when there is something to delete — an enabled
            // destructive button over an empty list is a trap with no upside.
            if (state.notifications.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
                        .background(ZillitTheme.colors.danger)
                        .clickable(onClick = viewModel::askDeleteAll)
                        .padding(
                            horizontal = ZillitTheme.spacing.md,
                            vertical = ZillitTheme.spacing.xs,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = ZillitTheme.colors.textOnBrand,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.delete_all),
                        style = MaterialTheme.typography.labelLarge,
                        color = ZillitTheme.colors.textOnBrand,
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading && state.notifications.isEmpty() -> CircularProgressIndicator(
                    color = ZillitTheme.colors.brand,
                    modifier = Modifier.align(Alignment.Center),
                )

                state.notifications.isEmpty() -> EmptyNotifications(
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(vertical = ZillitTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    items(state.notifications, key = { it.id }) { notification ->
                        NotificationRow(
                            notification = notification,
                            onDelete = { viewModel.askDelete(notification) },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let {
        ZillitConfirmDialog(
            message = stringResource(R.string.are_you_sure_you_want_to_delete_this_notification),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
            isDestructive = true,
        )
    }

    if (pendingDeleteAll) {
        ZillitConfirmDialog(
            message = stringResource(R.string.are_you_sure_you_want_to_delete_all_notification),
            onConfirm = viewModel::confirmDeleteAll,
            onDismiss = viewModel::dismissDeleteAll,
            isDestructive = true,
        )
    }
}

@Composable
private fun NotificationRow(
    notification: com.zillit.zillitapp.core.notification.AppNotification,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md)
            .clip(RoundedCornerShape(ZillitTheme.shapes.medium))
            .background(ZillitTheme.colors.surface)
            .padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        // An unread dot rather than a tinted row: the list is mostly unread when opened,
        // and tinting nearly every row conveys nothing.
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .background(
                    if (notification.isRead) {
                        ZillitTheme.colors.border
                    } else {
                        ZillitTheme.colors.brand
                    },
                    CircleShape,
                ),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            if (notification.title.isNotBlank()) {
                Text(
                    text = notification.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = ZillitTheme.colors.textPrimary,
                )
            }
            // Server copy carries light HTML (<strong>, <br>); rendered rather than shown.
            if (notification.body.isNotBlank()) {
                Text(
                    text = notification.body.fromSimpleHtml(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            Text(
                text = notification.createdAt.toShortDateTimeLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = stringResource(R.string.delete),
            tint = ZillitTheme.colors.danger,
            modifier = Modifier.size(20.dp).clickable(onClick = onDelete),
        )
    }
}

@Composable
private fun EmptyNotifications(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(ZillitTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.NotificationsNone,
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(R.string.no_notification),
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}
