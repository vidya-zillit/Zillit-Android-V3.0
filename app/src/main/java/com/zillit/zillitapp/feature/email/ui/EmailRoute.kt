package com.zillit.zillitapp.feature.email.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.resolve
import com.zillit.zillitapp.core.ui.toUiText
import com.zillit.zillitapp.core.ui.components.rememberMessageBar
import com.zillit.zillitapp.core.ui.components.ShowOnce
import com.zillit.zillitapp.core.ui.components.MessageBarBox
import com.zillit.zillitapp.core.ui.components.ListDetailLayout
import com.zillit.zillitapp.core.ui.components.isMultiPane
import com.zillit.zillitapp.core.ui.window.LocalWindowSize
import com.zillit.zillitapp.feature.email.ui.compose.EmailComposeArgs
import com.zillit.zillitapp.feature.email.ui.detail.EmailDetailArgs
import androidx.compose.runtime.LaunchedEffect
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailFolder
import com.zillit.zillitapp.feature.email.domain.EmailFolders
import com.zillit.zillitapp.feature.email.domain.MailboxScope
import com.zillit.zillitapp.feature.email.ui.drawer.ChooseMailboxSheet
import com.zillit.zillitapp.feature.email.ui.list.EmailFilterSheet
import com.zillit.zillitapp.feature.email.ui.list.EmailListScreen
import com.zillit.zillitapp.feature.email.ui.list.EmailListViewModel
import com.zillit.zillitapp.feature.email.ui.list.EmailReadFilter
import com.zillit.zillitapp.feature.email.ui.list.EmailRowState
import com.zillit.zillitapp.feature.email.ui.list.folderDisplayName
import com.zillit.zillitapp.core.ui.components.SheetAction
import com.zillit.zillitapp.core.ui.components.ActionSheet
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MarkEmailRead
import com.zillit.zillitapp.core.ui.components.EmptyState

/**
 * The Email tab.
 *
 * The drawer, the list and the three sheets that belong to them. Everything that leaves this
 * screen — a message, the composer, settings — is a navigation callback, so the tab does not
 * need to know where those live.
 */
@Composable
fun EmailRoute(
    onOpenDetail: (emailId: String, folderName: String, threadId: String?) -> Unit,
    onCompose: (draftId: String?) -> Unit,
    /** The reading pane's reply/forward. Reaches the same composer route the phone uses. */
    onReply: (mode: String, emailId: String, folderName: String) -> Unit,
    onAddContact: (email: String, name: String) -> Unit,
    onSearch: () -> Unit,
    onPickFolder: (sourceFolder: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenCalendar: () -> Unit,
    onReadBy: (emailId: String, folderName: String) -> Unit,
    pickedFolder: String?,
    onFolderHandled: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState by viewModel.drawerState.collectAsStateWithLifecycle()
    val mailboxCounts by viewModel.mailboxCounts.collectAsStateWithLifecycle()
    val folderName by viewModel.folderName.collectAsStateWithLifecycle()
    val openedEmail by viewModel.openedEmail.collectAsStateWithLifecycle()
    val windowSize = LocalWindowSize.current

    // Back with a mail open beside the list closes the mail, the way it pops the detail
    // page on a phone — without this it left the whole dashboard.
    BackHandler(enabled = windowSize.isMultiPane && openedEmail != null) { viewModel.closeEmail() }

    // Folded while reading: the mail that was beside the list becomes the phone's detail
    // page, so the person keeps their place instead of landing back on the list.
    LaunchedEffect(windowSize.isMultiPane, openedEmail) {
        val opened = openedEmail ?: return@LaunchedEffect
        if (!windowSize.isMultiPane) {
            viewModel.closeEmail()
            // A draft continues in the composer; anything else in the reader.
            if (opened.isDraft) onCompose(opened.id)
            else onOpenDetail(opened.id, opened.folderName, opened.threadId)
        }
    }

    var showFilter by remember { mutableStateOf(false) }
    var showMailboxChooser by remember { mutableStateOf(false) }
    var readFilter by remember { mutableStateOf(EmailReadFilter.ALL) }
    var hasAttachments by remember { mutableStateOf(false) }

    var folderMenuTarget by remember { mutableStateOf<EmailFolder?>(null) }
    var renaming by remember { mutableStateOf<EmailFolder?>(null) }
    var deleting by remember { mutableStateOf<EmailFolder?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var rowMenuTarget by remember { mutableStateOf<EmailRowState?>(null) }
    var confirmClearFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val messages = rememberMessageBar()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val limitReached by viewModel.selectionLimitReached.collectAsStateWithLifecycle()

    val movedToTrash = stringResource(R.string.email_moved_to_trash_success)
    val sendingStandby = stringResource(R.string.email_sending_standby)
    val sendCancelled = stringResource(R.string.email_send_cancelled)
    val removed = stringResource(R.string.email_removed_success)
    val errorText = error?.toUiText()?.resolve()

    messages.ShowOnce(errorText) { viewModel.consumeError() }

    // The picker is its own destination and cannot move anything — it only reports the
    // folder. The selection that is being moved lives here.
    // The picker answers through the dashboard's back-stack entry whichever side asked for
    // it. A live selection means the list asked; otherwise the reading pane did, and the
    // answer is handed to it below rather than moving nothing here.
    val listAskedForFolder = uiState.selection.isActive
    val movedTo = stringResource(R.string.email_moved_to_folder_success, folderDisplayName(pickedFolder.orEmpty()))
    LaunchedEffect(pickedFolder, listAskedForFolder) {
        pickedFolder?.takeIf { listAskedForFolder }?.let {
            viewModel.moveSelected(it)
            messages.post(movedTo)
            onFolderHandled()
        }
    }

    MessageBarBox(state = messages, modifier = modifier) {
    EmailLandingScreen(
        drawerState = drawerState,
        onFolderClick = { viewModel.openFolder(it.folderName) },
        onFolderMore = { folderMenuTarget = it },
        onCreateFolder = { creatingFolder = true },
        onSwitchMailbox = { showMailboxChooser = true },
        onOpenCalendar = onOpenCalendar,
        onOpenContacts = onOpenContacts,
        onOpenSettings = onOpenSettings,
    ) { openDrawer ->
    ListDetailLayout(
        // On a phone the detail is a route, never a pane — see the LaunchedEffect above.
        showDetail = windowSize.isMultiPane && openedEmail != null,
        detailPlaceholder = { EmailReadingPlaceholder() },
        detail = {
            openedEmail?.let { opened ->
                if (opened.isDraft) {
                    // Written where it was picked, so it can be finished and sent without
                    // the list going away — the reason the second pane exists at all.
                    EmailComposeRoute(
                        args = EmailComposeArgs.draft(opened.id),
                        onClose = {
                            viewModel.closeEmail()
                            // The draft has just been saved or sent; the list still shows
                            // what it held before.
                            viewModel.refresh()
                        },
                    )
                } else {
                    EmailDetailRoute(
                        args = EmailDetailArgs(opened.id, opened.folderName, opened.threadId),
                        onBack = viewModel::closeEmail,
                        onReply = onReply,
                        onPickFolder = onPickFolder,
                        onReadBy = onReadBy,
                        onAddContact = onAddContact,
                        pickedFolder = pickedFolder.takeIf { !listAskedForFolder },
                        onFolderHandled = onFolderHandled,
                    )
                }
            }
        },
    ) {
        EmailListScreen(
            state = uiState,
            onOpenDrawer = openDrawer,
            onSearch = onSearch,
            onFilter = { showFilter = true },
            onRefresh = viewModel::refresh,
            onCompose = { onCompose(null) },
            onRowClick = { row ->
                when {
                    // Nothing to open: a queued mail exists only on this device until it
                    // has gone out.
                    row.pending -> messages.post(sendingStandby)

                    // While selecting, a tap extends the selection rather than opening —
                    // otherwise the gesture changes meaning halfway through.
                    uiState.selection.isActive -> viewModel.toggleSelection(row)

                    // A draft opens in the composer, not in a reader: there is nothing to
                    // read, and the only useful action is to carry on writing it. Beside
                    // the list where there is room — so it can be finished and sent
                    // without leaving the folder — and as its own page where there is not.
                    uiState.isDrafts && windowSize.isMultiPane ->
                        viewModel.openEmail(row.id, row.folderName, null)

                    uiState.isDrafts -> onCompose(row.id)

                    // Beside the list where there is room for it; as its own page where
                    // there is not.
                    windowSize.isMultiPane -> viewModel.openEmail(
                        row.id,
                        row.folderName,
                        row.threadId.takeIf { uiState.conversationView },
                    )

                    else -> onOpenDetail(
                        row.id,
                        row.folderName,
                        row.threadId.takeIf { uiState.conversationView },
                    )
                }
            },
            // A queued mail cannot be part of a bulk delete or move either — both act on
            // server-side ids it does not have yet.
            onRowLongClick = { row ->
                if (row.pending) {
                    messages.post(sendingStandby)
                } else {
                    viewModel.toggleSelection(row)
                }
            },
            onRowMore = { rowMenuTarget = it },
            onCancelSelection = viewModel::clearSelection,
            onClearFolder = { confirmClearFolder = true },
            onSelectionDelete = { confirmDelete = true },
            onSelectionMove = { onPickFolder(folderName) },
            onSelectAll = viewModel::selectAll,
        )
    }
    }
    }

    if (showFilter) {
        EmailFilterSheet(
            readFilter = readFilter,
            hasAttachments = hasAttachments,
            onReadFilterChange = {
                readFilter = it
                viewModel.setReadFilter(it)
            },
            onHasAttachmentsChange = {
                hasAttachments = it
                viewModel.setAttachmentFilter(it)
            },
            onDismiss = { showFilter = false },
        )
    }

    if (showMailboxChooser) {
        ChooseMailboxSheet(
            current = drawerState.scope,
            personalEmail = drawerState.personalEmail,
            accountsEmail = drawerState.accountsEmail,
            // Counts are keyed by mailbox address — which is what `level_1` carries on an
            // email notification.
            personalUnread = mailboxCounts[drawerState.personalEmail] ?: 0,
            accountsUnread = drawerState.accountsEmail?.let { mailboxCounts[it] } ?: 0,
            onChoose = {
                showMailboxChooser = false
                viewModel.switchMailbox(it)
            },
            onDismiss = { showMailboxChooser = false },
        )
    }

    // A custom folder's menu: rename and delete, the only two things it can do.
    folderMenuTarget?.let { folder ->
        ActionSheet(
            title = folderDisplayName(folder.folderName),
            onDismiss = { folderMenuTarget = null },
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.email_rename_action),
                    icon = Icons.Outlined.DriveFileRenameOutline,
                    onClick = {
                        renaming = folder
                        folderMenuTarget = null
                    },
                ),
                SheetAction(
                    label = stringResource(R.string.delete),
                    icon = Icons.Outlined.DeleteOutline,
                    destructive = true,
                    onClick = {
                        deleting = folder
                        folderMenuTarget = null
                    },
                ),
            ),
        )
    }

    rowMenuTarget?.let { row ->
        ActionSheet(
            title = row.subject,
            onDismiss = { rowMenuTarget = null },
            actions = buildList {
                // A queued mail has no server-side id, so it can be neither moved nor
                // deleted. Cancelling it is the one thing that makes sense, and v2 allows
                // it only offline — here it is allowed always, because the outbox holds
                // the mail until it is delivered either way.
                if (row.pending) {
                    add(
                        SheetAction(
                            label = stringResource(R.string.email_cancel_send),
                            icon = Icons.Outlined.DeleteOutline,
                            destructive = true,
                            onClick = {
                                rowMenuTarget = null
                                viewModel.cancelPending(row.id)
                                messages.post(sendCancelled)
                            },
                        ),
                    )
                    return@buildList
                }

                // A draft has nowhere to move to — it only exists server-side until sent.
                if (!uiState.isDrafts) {
                    add(
                        SheetAction(
                            label = stringResource(R.string.email_move),
                            icon = Icons.Outlined.DriveFileMove,
                            onClick = {
                                rowMenuTarget = null
                                viewModel.selectOnly(row)
                                onPickFolder(folderName)
                            },
                        ),
                    )
                }

                add(
                    SheetAction(
                        label = stringResource(
                            if (uiState.isTrash) {
                                R.string.email_delete_permanently
                            } else {
                                R.string.delete
                            },
                        ),
                        icon = Icons.Outlined.DeleteOutline,
                        destructive = true,
                        onClick = {
                            rowMenuTarget = null
                            viewModel.selectOnly(row)
                            confirmDelete = true
                        },
                    ),
                )

                // Only meaningful for something you sent — there is no receipt on a mail
                // somebody else sent you.
                if (folderName == EmailFolders.SENT) {
                    add(
                        SheetAction(
                            label = stringResource(R.string.email_read_by),
                            icon = Icons.Outlined.DoneAll,
                            onClick = {
                                rowMenuTarget = null
                                onReadBy(row.id, row.folderName)
                            },
                        ),
                    )
                }
            },
        )
    }

    if (creatingFolder) {
        FolderNameDialog(
            title = stringResource(R.string.email_create_folder_title),
            confirmLabel = stringResource(R.string.email_create_action),
            initial = "",
            onConfirm = {
                viewModel.createFolder(it)
                creatingFolder = false
            },
            onDismiss = { creatingFolder = false },
        )
    }

    renaming?.let { folder ->
        FolderNameDialog(
            title = stringResource(R.string.email_rename_folder_title),
            confirmLabel = stringResource(R.string.email_rename_action),
            initial = folderDisplayName(folder.folderName),
            onConfirm = {
                viewModel.renameFolder(folder.folderName, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { folder ->
        ZillitConfirmDialog(
            title = stringResource(R.string.email_delete_folder_title),
            message = stringResource(R.string.email_delete_folder_message),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(R.string.cancel),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteFolder(folder.folderName)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }

    // v2 raises a dialog rather than a passing message here, and the copy is the client's.
    if (limitReached) {
        ZillitConfirmDialog(
            title = stringResource(R.string.email_selection_limited_title),
            message = stringResource(R.string.email_selection_limited_message),
            confirmLabel = stringResource(R.string.ok),
            dismissLabel = null,
            onConfirm = viewModel::consumeSelectionLimit,
            onDismiss = viewModel::consumeSelectionLimit,
        )
    }

    if (confirmDelete) {
        ZillitConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.are_you_sure_you_want_to_delete),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(R.string.cancel),
            isDestructive = true,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteSelected()
                // Outside Trash and Drafts a delete is a move to Trash, and the message
                // has to say so — it is the only thing telling the user it is recoverable.
                messages.post(
                    if (uiState.isTrash || uiState.isDrafts) removed else movedToTrash,
                )
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (confirmClearFolder) {
        val label = folderDisplayName(folderName)
        ZillitConfirmDialog(
            title = if (uiState.isTrash) {
                stringResource(R.string.email_empty_trash)
            } else {
                stringResource(R.string.email_clear_folder, label)
            },
            message = when {
                uiState.isTrash -> stringResource(R.string.email_empty_trash_message)
                uiState.isDrafts -> stringResource(R.string.email_delete_all_drafts_message)
                else -> stringResource(R.string.email_clear_folder_message, label)
            },
            confirmLabel = if (uiState.isTrash) {
                stringResource(R.string.email_empty_action)
            } else {
                stringResource(R.string.email_clear_action)
            },
            dismissLabel = stringResource(R.string.cancel),
            isDestructive = true,
            onConfirm = {
                viewModel.clearFolder()
                confirmClearFolder = false
            },
            onDismiss = { confirmClearFolder = false },
        )
    }
}

/**
 * Create and rename share a dialog: one field, one button, different words.
 *
 * Internal rather than private because the rule editor offers folder creation in place too,
 * and a second copy of the same dialog is the duplication this project is trying to avoid.
 */
@Composable
internal fun FolderNameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FormTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.email_folder_name_hint),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        containerColor = ZillitTheme.colors.surface,
    )
}

/**
 * The reading pane before anything is chosen.
 *
 * Deliberately quiet: on a wide window this sits beside the list for as long as the
 * person is scanning, and anything louder than a hint competes with the list itself.
 */
@Composable
private fun EmailReadingPlaceholder() {
    EmptyState(
        icon = Icons.Outlined.MarkEmailRead,
        title = stringResource(R.string.email_reading_pane_empty_title),
        description = stringResource(R.string.email_reading_pane_empty_description),
    )
}
