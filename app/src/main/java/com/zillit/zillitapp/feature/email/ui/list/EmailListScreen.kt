package com.zillit.zillitapp.feature.email.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MailLock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import com.zillit.zillitapp.R
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.SelectionAction
import com.zillit.zillitapp.core.ui.components.SelectionBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailFolders

/**
 * A folder's mail — v2's `fragment_email_list`.
 *
 * The screen is stateless: everything it draws arrives in [state] and every gesture leaves
 * through a callback. That is what lets the search screen reuse [EmailRow] unchanged, and
 * what makes the list testable without a mail server.
 *
 * One thing worth knowing before reading further: **this list re-renders whenever a badge
 * changes**, not only when Realm changes, because an email's read state lives in the badge
 * tree rather than on the row. [EmailRowState] is `@Immutable` so that stays cheap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailListScreen(
    state: EmailListUiState,
    /** Opens the folder drawer. Null when the drawer is permanently on screen. */
    onOpenDrawer: (() -> Unit)?,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
    onRefresh: () -> Unit,
    onCompose: () -> Unit,
    onRowClick: (EmailRowState) -> Unit,
    onRowLongClick: (EmailRowState) -> Unit,
    onRowMore: (EmailRowState) -> Unit,
    onSelectAll: () -> Unit,
    onCancelSelection: () -> Unit,
    onClearFolder: () -> Unit,
    onSelectionDelete: () -> Unit,
    onSelectionMove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val folderLabel = folderDisplayName(state.folder.folderName)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ZillitTheme.colors.surface,
        // Zero, not the default `systemBars`: this Scaffold is nested inside the
        // dashboard's, which has already consumed the status bar. Leaving the default on
        // inserts that inset a second time, as a band of empty space above the folder name.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                if (state.selection.isActive) {
                    SelectionTopBar(
                        count = state.selection.count,
                        onCancel = onCancelSelection,
                    )
                } else {
                    EmailListTopBar(
                        title = folderLabel,
                        filterActive = state.filterActive,
                        onOpenDrawer = onOpenDrawer,
                        onSearch = onSearch,
                        onFilter = onFilter,
                        onRefresh = onRefresh,
                    )
                }

                // A thin bar rather than a spinner over the list: a sync that arrives while
                // the user is reading must not hide what they are reading.
                if (state.refreshing) {
                    LinearProgressIndicator(
                        color = ZillitTheme.colors.brand,
                        trackColor = ZillitTheme.colors.brandSoft,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        bottomBar = {
            if (state.selection.isActive) {
                SelectionBar(
                    count = state.selection.count,
                    limit = EmailSelection.LIMIT,
                    onCancel = onCancelSelection,
                    actions = selectionActions(
                        state = state,
                        onDelete = onSelectionDelete,
                        onMove = onSelectionMove,
                        onSelectAll = onSelectAll,
                    ),
                )
            }
        },
        floatingActionButton = {
            if (!state.selection.isActive) {
                ExtendedFloatingActionButton(
                    onClick = onCompose,
                    containerColor = ZillitTheme.colors.brand,
                    contentColor = ZillitTheme.colors.textOnBrand,
                    icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    text = { Text(stringResource(R.string.email_compose)) },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // The strip above the list: select-many, and the destructive folder-wide action.
            // Hidden during a selection, when its own buttons would compete with the bar.
            if (state.rows.isNotEmpty() && !state.selection.isActive) {
                FolderActionStrip(
                    isTrash = state.isTrash,
                    folderLabel = folderLabel,
                    onSelectAll = onSelectAll,
                    onClearFolder = onClearFolder,
                )
                HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)
            }

            // Said once, above the list, rather than as a message that comes and goes:
            // being offline is a state the user stays in, not an event.
            if (state.offline) {
                Text(
                    text = stringResource(R.string.no_internet),
                    style = MaterialTheme.typography.labelMedium,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ZillitTheme.colors.warningSoft)
                        .padding(
                            horizontal = ZillitTheme.spacing.lg,
                            vertical = ZillitTheme.spacing.xs,
                        ),
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                // Pull-to-refresh wraps the empty state as well as the list: an empty
                // folder is exactly where somebody reaches for it, and having it work only
                // when there is already mail reads as the gesture being broken.
                val pullState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    state = pullState,
                ) {
                when {
                    state.noMailbox -> EmptyState(
                        icon = Icons.Outlined.MailLock,
                        title = stringResource(R.string.email_no_mailbox_title),
                        description = stringResource(R.string.email_no_mailbox_subtitle),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )

                    state.loading && state.rows.isEmpty() -> LoadingState()

                    state.isEmpty -> EmptyState(
                        icon = Icons.Outlined.Mail,
                        title = stringResource(R.string.email_empty_title),
                        description = stringResource(
                            R.string.email_empty_subtitle,
                            folderLabel.lowercase(),
                        ),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )

                    else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                // Clears the FAB, so the last row is never stuck under it.
                                contentPadding = PaddingValues(bottom = 88.dp),
                            ) {
                                items(state.rows, key = { it.id }) { row ->
                                    EmailRow(
                                        state = row,
                                        selected = row.id in state.selection,
                                        selectionActive = state.selection.isActive,
                                        onClick = { onRowClick(row) },
                                        onLongClick = { onRowLongClick(row) },
                                        onMoreClick = { onRowMore(row) },
                                    )
                                    HorizontalDivider(
                                        color = ZillitTheme.colors.divider,
                                        thickness = 0.5.dp,
                                        // Inset past the avatar, so the divider separates
                                        // the text rather than cutting the row in half.
                                        modifier = Modifier.padding(start = 66.dp),
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
 * Which bulk actions this folder offers.
 *
 * v2 rebuilds an options menu on every state emission to answer the same question. The rules
 * are the folder's, not the selection's: Trash deletes permanently, Drafts deletes drafts
 * and cannot move, everything else moves to Trash.
 */
@Composable
private fun selectionActions(
    state: EmailListUiState,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onSelectAll: () -> Unit,
): List<SelectionAction> = buildList {
    // The strip carrying Select All is hidden once a selection starts, so the bar has to
    // carry it — otherwise "select everything" is only reachable before selecting anything.
    add(
        SelectionAction(
            icon = Icons.Outlined.SelectAll,
            label = if (state.selection.count >= minOf(state.rows.size, EmailSelection.LIMIT)) {
                stringResource(R.string.email_deselect_all)
            } else {
                stringResource(R.string.email_select_all)
            },
            onClick = onSelectAll,
        ),
    )

    add(
        SelectionAction(
            icon = Icons.Outlined.DeleteOutline,
            label = when {
                state.isTrash -> stringResource(R.string.email_delete_permanently)
                state.isDrafts -> stringResource(R.string.email_delete_drafts)
                else -> stringResource(R.string.email_move_to_trash)
            },
            onClick = onDelete,
            destructive = true,
        ),
    )

    // A draft has no folder to move to — it only exists on the server until it is sent.
    if (!state.isDrafts) {
        add(
            SelectionAction(
                icon = Icons.Outlined.DriveFileMove,
                label = stringResource(R.string.email_move_to_folder),
                onClick = onMove,
            ),
        )
    }
}

@Composable
private fun EmailListTopBar(
    title: String,
    filterActive: Boolean,
    onOpenDrawer: (() -> Unit)?,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            // Deliberately no top inset: this bar is nested inside the dashboard, whose
            // own top bar has already consumed the status bar. Only the horizontal cutout
            // inset is still ours to honour, for landscape on a notched device.
            .windowInsetsPadding(
                WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
            )
            .height(56.dp)
            .padding(horizontal = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No hamburger when the folders are already beside the list — a control that opens
        // what is on screen is a control that does nothing.
        if (onOpenDrawer != null) {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Outlined.Menu,
                    contentDescription = stringResource(R.string.email_folders_header),
                    tint = ZillitTheme.colors.textPrimary,
                )
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onOpenDrawer != null) ZillitTheme.spacing.xs else ZillitTheme.spacing.sm),
        )

        IconButton(onClick = onSearch) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = stringResource(R.string.action_search),
                tint = ZillitTheme.colors.textPrimary,
            )
        }

        // The dot is the only thing telling the user results are being hidden from them.
        Box {
            IconButton(onClick = onFilter) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = stringResource(R.string.email_filter),
                    tint = if (filterActive) {
                        ZillitTheme.colors.brand
                    } else {
                        ZillitTheme.colors.textSecondary
                    },
                )
            }
            if (filterActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 8.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(ZillitTheme.colors.brand),
                )
            }
        }

        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Outlined.Sync,
                contentDescription = stringResource(R.string.email_refresh),
                tint = ZillitTheme.colors.textPrimary,
            )
        }
    }
}

/** The bar that replaces the header while rows are selected. */
@Composable
private fun SelectionTopBar(count: Int, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            // Deliberately no top inset: this bar is nested inside the dashboard, whose
            // own top bar has already consumed the status bar. Only the horizontal cutout
            // inset is still ours to honour, for landscape on a notched device.
            .windowInsetsPadding(
                WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
            )
            .height(56.dp)
            .padding(horizontal = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = ZillitTheme.colors.textPrimary,
            )
        }
        Text(
            text = stringResource(R.string.email_selected_count, count),
            style = MaterialTheme.typography.titleMedium,
            color = ZillitTheme.colors.brand,
            modifier = Modifier.padding(start = ZillitTheme.spacing.xs),
        )
    }
}

/**
 * "Select Items" on the left, the folder-emptying action on the right.
 *
 * The destructive one is deliberately at the far edge from the FAB and in danger red — it
 * deletes a whole folder, and in v2 it sits in the same row as the innocuous select button
 * with nothing but colour to tell them apart.
 */
@Composable
private fun FolderActionStrip(
    isTrash: Boolean,
    folderLabel: String,
    onSelectAll: () -> Unit,
    onClearFolder: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(
            onClick = onSelectAll,
            contentPadding = PaddingValues(
                horizontal = ZillitTheme.spacing.md,
                vertical = ZillitTheme.spacing.xxs,
            ),
        ) {
            Text(
                text = stringResource(R.string.email_select_all),
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.brand,
            )
        }

        OutlinedButton(
            onClick = onClearFolder,
            contentPadding = PaddingValues(
                horizontal = ZillitTheme.spacing.md,
                vertical = ZillitTheme.spacing.xxs,
            ),
        ) {
            Text(
                text = if (isTrash) {
                    stringResource(R.string.email_empty_trash)
                } else {
                    stringResource(R.string.email_clear_folder, folderLabel)
                },
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}

/**
 * What a folder is called on screen.
 *
 * Only the inbox is renamed — `INBOX` is a protocol name, not a word. Every other folder,
 * including a qualified custom one, is shown as the server names it, because that is what
 * the user typed when they created it.
 */
@Composable
fun folderDisplayName(folderName: String): String =
    if (folderName == EmailFolders.INBOX) {
        stringResource(R.string.email_folder_inbox)
    } else {
        folderName.substringAfterLast('.')
    }
