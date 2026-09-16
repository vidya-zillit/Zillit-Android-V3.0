package com.zillit.zillitapp.feature.email.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.core.ui.html.rememberRichTextEditorState
import com.zillit.zillitapp.feature.email.domain.EmailContact
import com.zillit.zillitapp.feature.email.domain.EmailFolders
import com.zillit.zillitapp.feature.email.ui.compose.ComposeScreen
import com.zillit.zillitapp.feature.email.ui.compose.EmailComposeArgs
import com.zillit.zillitapp.feature.email.ui.compose.EmailComposeViewModel
import com.zillit.zillitapp.feature.email.ui.contacts.ContactListScreen
import com.zillit.zillitapp.feature.email.ui.contacts.ContactFormState
import com.zillit.zillitapp.feature.email.ui.contacts.EditContactScreen
import com.zillit.zillitapp.feature.email.ui.contacts.EmailContactsViewModel
import com.zillit.zillitapp.feature.email.ui.detail.EmailDetailScreen
import com.zillit.zillitapp.feature.email.ui.detail.EmailDetailArgs
import com.zillit.zillitapp.feature.email.ui.detail.EmailDetailViewModel
import com.zillit.zillitapp.feature.email.ui.folder.FolderPickerScreen
import com.zillit.zillitapp.feature.email.ui.folder.FolderPickerViewModel
import com.zillit.zillitapp.feature.email.ui.list.EmailListViewModel
import com.zillit.zillitapp.feature.email.ui.rules.EditEmailRuleScreen
import com.zillit.zillitapp.feature.email.ui.rules.EmailRulesScreen
import com.zillit.zillitapp.feature.email.ui.rules.EmailRulesViewModel
import com.zillit.zillitapp.feature.email.ui.rules.RuleExecutionsScreen
import com.zillit.zillitapp.feature.email.ui.search.EmailSearchScreen
import com.zillit.zillitapp.feature.email.ui.search.EmailSearchViewModel
import com.zillit.zillitapp.feature.email.ui.settings.EditEmailGroupScreen
import com.zillit.zillitapp.feature.email.ui.settings.EditSignatureScreen
import com.zillit.zillitapp.feature.email.ui.settings.EmailForwardingSheet
import com.zillit.zillitapp.feature.email.ui.settings.EmailGroupsScreen
import com.zillit.zillitapp.feature.email.ui.settings.EmailSettingsScreen
import com.zillit.zillitapp.feature.email.ui.settings.EmailSettingsViewModel
import com.zillit.zillitapp.feature.email.ui.settings.GroupMember
import com.zillit.zillitapp.feature.email.ui.settings.MailCredentialsSheet
import com.zillit.zillitapp.feature.email.ui.settings.SignatureListScreen
import com.zillit.zillitapp.R
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.zillit.zillitapp.feature.email.domain.EmailAddress
import com.zillit.zillitapp.feature.email.ui.contacts.ContactActionSheet
import androidx.compose.material.icons.outlined.Print
import com.zillit.zillitapp.feature.email.domain.EmailRule
import com.zillit.zillitapp.feature.email.domain.EmailSignature
import com.zillit.zillitapp.feature.email.domain.EmailGroup
import com.zillit.zillitapp.core.ui.components.DeleteConfirmDialog
import com.zillit.zillitapp.feature.email.ui.list.folderDisplayName
import com.zillit.zillitapp.core.ui.toUiText
import com.zillit.zillitapp.core.ui.openLink
import com.zillit.zillitapp.core.ui.resolve
import com.zillit.zillitapp.core.ui.components.rememberMessageBar
import com.zillit.zillitapp.core.ui.components.ShowOnce
import com.zillit.zillitapp.core.ui.components.MessageBarBox
import com.zillit.zillitapp.core.attachment.AttachmentResult
import com.zillit.zillitapp.core.attachment.AttachmentPickerSheet
import com.zillit.zillitapp.core.attachment.AttachmentOption
import com.zillit.zillitapp.feature.email.ui.settings.BccPresetsScreen
import com.zillit.zillitapp.navigation.EmailCompose
import com.zillit.zillitapp.core.ui.components.SheetAction
import com.zillit.zillitapp.core.ui.components.ActionSheet
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.Icons
import android.content.Context
import com.zillit.zillitapp.feature.email.domain.Email
import com.zillit.zillitapp.core.ui.picker.CommonListPicker
import com.zillit.zillitapp.core.ui.picker.CommonListItem
import com.zillit.zillitapp.feature.email.ui.rules.RuleDriveFolderPickerSheet
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.components.FormTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import com.zillit.zillitapp.core.ui.html.HtmlPrintLauncher
import com.zillit.zillitapp.feature.email.ui.settings.ChangePasswordDialog
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import com.zillit.zillitapp.core.labels.asLabel
import androidx.compose.material3.SnackbarHostState

/**
 * The email module's destinations, each binding its view model to its screen.
 *
 * Kept apart from the screens themselves so every screen stays stateless and previewable:
 * a screen takes a state object and callbacks, and one of these supplies both.
 */

@Composable
fun EmailDetailRoute(
    /**
     * What to show. The destination builds this from its route; the wide-window pane builds
     * it from the row the list selected. Keyed into the view model, so a different mail is
     * a different view model rather than the previous one being asked to reload.
     */
    args: EmailDetailArgs,
    onBack: () -> Unit,
    onReply: (mode: String, emailId: String, folderName: String) -> Unit,
    onPickFolder: (sourceFolder: String) -> Unit,
    onReadBy: (emailId: String, folderName: String) -> Unit,
    onAddContact: (email: String, name: String) -> Unit,
    pickedFolder: String?,
    onFolderHandled: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailDetailViewModel = hiltViewModel<EmailDetailViewModel, EmailDetailViewModel.Factory>(
        key = "email-detail:${args.folderName}:${args.threadId ?: args.emailId}",
        creationCallback = { factory -> factory.create(args) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var messageMenuTarget by remember { mutableStateOf<Email?>(null) }

    // Which messages the pending action applies to: null means the whole conversation, a
    // single id means the one message whose ⋮ menu was used. Remembered across the picker
    // navigation, which is why it is state and not a parameter.
    var actionTargets by remember { mutableStateOf<List<String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var choosingOutsider by remember { mutableStateOf<List<EmailAddress>>(emptyList()) }
    var senderSheet by remember { mutableStateOf<EmailAddress?>(null) }

    val messages = rememberMessageBar()
    val movedToTrash = stringResource(R.string.email_moved_to_trash_success)
    val removed = stringResource(R.string.email_removed_success)
    val movedTo = stringResource(
        R.string.email_moved_to_folder_success,
        folderDisplayName(pickedFolder.orEmpty()),
    )
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val downloadFailed by viewModel.downloadFailed.collectAsStateWithLifecycle()
    val downloadFailedText = stringResource(R.string.email_download_failed)

    messages.ShowOnce(actionError?.toUiText()?.resolve()) { viewModel.consumeError() }
    messages.ShowOnce(downloadFailedText.takeIf { downloadFailed }) { viewModel.consumeError() }

    LaunchedEffect(pickedFolder) {
        pickedFolder?.let { folder ->
            viewModel.move(folder, actionTargets)
            actionTargets = null
            messages.post(movedTo)
            onFolderHandled()
            if (viewModel.isEmpty()) onBack()
        }
    }

    // Opening a downloaded file is an event, so it is collected rather than read from
    // state — state would re-open it on every recomposition and after a rotation.
    LaunchedEffect(Unit) {
        viewModel.openFile.collect { file ->
            viewModel.shareableUri(file)?.let { uri -> context.openAttachment(uri, file) }
        }
    }

    // The print job's name is built here rather than in the view model, so the copy stays
    // translatable and the view model stays free of resources.
    val subject = state.subject.ifBlank { stringResource(R.string.email_no_subject_row) }
    val singleJobName = stringResource(R.string.email_print_job, subject)
    val conversationJobName = stringResource(R.string.email_print_job_conversation, subject)
    val noContent = stringResource(R.string.email_print_no_content)

    HtmlPrintLauncher(requests = viewModel.printRequests)

    MessageBarBox(state = messages, modifier = modifier) {
    EmailDetailScreen(
        state = state,
        onBack = onBack,
        onReplyActionChange = viewModel::setReplyAction,
        onReply = { action ->
            state.emails.lastOrNull()?.let { onReply(action.mode, it.id, state.folderName) }
        },
        onDelete = {
            actionTargets = null
            confirmDelete = true
        },
        onMove = {
            actionTargets = null
            onPickFolder(state.folderName)
        },
        onPrint = { all ->
            viewModel.print(
                all = all,
                noContent = noContent,
                jobName = if (all) conversationJobName else singleJobName,
            )
        },
        onToggleExpanded = viewModel::toggleExpanded,
        onMessageMore = { messageMenuTarget = it },
        onSenderClick = { senderSheet = it.from },
        onAttachmentClick = viewModel::downloadAttachment,
        onAttachmentDownload = viewModel::downloadAttachment,
        // Nothing in a mail body may navigate the view itself, so a tapped link is handed
        // to the browser — and an unopenable one is ignored rather than crashing.
        onLinkClick = { url -> context.openLink(url) },
        onRetry = viewModel::load,
        resolveContentId = viewModel::resolveContentId,
        resolveAvatar = viewModel::avatarFor,
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
                viewModel.delete(actionTargets)
                actionTargets = null
                messages.post(if (state.isTrash) removed else movedToTrash)
                // Only the last message closes the reader. Deleting one reply of a ten
                // message conversation leaves the other nine on screen, as v2 does.
                if (viewModel.isEmpty()) onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (choosingOutsider.isNotEmpty()) {
        ActionSheet(
            title = stringResource(R.string.email_add_to_contacts),
            onDismiss = { choosingOutsider = emptyList() },
            actions = choosingOutsider.map { outsider ->
                SheetAction(
                    label = if (outsider.name.isBlank()) {
                        outsider.address
                    } else {
                        "${outsider.name} <${outsider.address}>"
                    },
                    icon = Icons.Outlined.PersonAdd,
                    onClick = {
                        choosingOutsider = emptyList()
                        onAddContact(outsider.address, outsider.display)
                    },
                )
            },
        )
    }

    senderSheet?.let { address ->
        ContactActionSheet(
            address = address,
            alreadySaved = viewModel.isSavedContact(address.address),
            onAddToContacts = {
                senderSheet = null
                onAddContact(address.address, address.display)
            },
            onDismiss = { senderSheet = null },
        )
    }

    messageMenuTarget?.let { email ->
        val outsiders = remember(email) { viewModel.outsiders(email) }

        ActionSheet(
            title = email.from.display,
            onDismiss = { messageMenuTarget = null },
            actions = buildList {
                add(
                    SheetAction(
                        label = stringResource(R.string.email_reply),
                        icon = Icons.AutoMirrored.Outlined.Reply,
                        onClick = {
                            messageMenuTarget = null
                            onReply(EmailCompose.MODE_REPLY, email.id, email.folderName)
                        },
                    ),
                )

                if (viewModel.canReplyAll(email)) {
                    add(
                        SheetAction(
                            label = stringResource(R.string.email_reply_all),
                            icon = Icons.AutoMirrored.Outlined.ReplyAll,
                            onClick = {
                                messageMenuTarget = null
                                onReply(EmailCompose.MODE_REPLY_ALL, email.id, email.folderName)
                            },
                        ),
                    )
                }

                add(
                    SheetAction(
                        label = stringResource(R.string.email_forward),
                        icon = Icons.AutoMirrored.Outlined.Forward,
                        onClick = {
                            messageMenuTarget = null
                            onReply(EmailCompose.MODE_FORWARD, email.id, email.folderName)
                        },
                    ),
                )

                // A read receipt only exists for a message this user sent.
                if (viewModel.isOwnMessage(email)) {
                    add(
                        SheetAction(
                            label = stringResource(R.string.email_read_by),
                            icon = Icons.Outlined.DoneAll,
                            onClick = {
                                messageMenuTarget = null
                                onReadBy(email.id, email.folderName)
                            },
                        ),
                    )
                }

                // Sent mail has nowhere to be moved to.
                if (!email.folderName.equals(EmailFolders.SENT, ignoreCase = true)) {
                    add(
                        SheetAction(
                            label = stringResource(R.string.email_move),
                            icon = Icons.Outlined.DriveFileMove,
                            onClick = {
                                messageMenuTarget = null
                                actionTargets = listOf(email.id)
                                onPickFolder(email.folderName)
                            },
                        ),
                    )
                }

                add(
                    SheetAction(
                        label = stringResource(R.string.email_print),
                        icon = Icons.Outlined.Print,
                        onClick = {
                            messageMenuTarget = null
                            viewModel.print(
                                all = false,
                                noContent = noContent,
                                jobName = singleJobName,
                                // This message, not the newest one in the trail.
                                emailId = email.id,
                            )
                        },
                    ),
                )

                // Offered when there is somebody on the message who is neither the user,
                // nor a project member, nor already in the address book. One outsider goes
                // straight to the editor; several are offered as a list, since taking the
                // first would leave the rest unreachable.
                if (outsiders.isNotEmpty()) {
                    add(
                        SheetAction(
                            label = stringResource(R.string.email_add_to_contacts),
                            icon = Icons.Outlined.PersonAdd,
                            onClick = {
                                messageMenuTarget = null
                                if (outsiders.size == 1) {
                                    val only = outsiders.first()
                                    onAddContact(only.address, only.display)
                                } else {
                                    choosingOutsider = outsiders
                                }
                            },
                        ),
                    )
                }

                add(
                    SheetAction(
                        label = stringResource(R.string.delete),
                        icon = Icons.Outlined.DeleteOutline,
                        destructive = true,
                        onClick = {
                            messageMenuTarget = null
                            actionTargets = listOf(email.id)
                            confirmDelete = true
                        },
                    ),
                )
            },
        )
    }
}

/**
 * Hands a downloaded attachment to whatever app can open it.
 *
 * The URI is a content grant for that one file, not a `file://` path the receiving app
 * could not legally read.
 */
private fun Context.openAttachment(uri: android.net.Uri, file: java.io.File) {
    val mime = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase())
        ?: "*/*"

    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // No app for this type is a normal outcome, not a crash.
    runCatching { startActivity(intent) }
}


@Composable
fun EmailComposeRoute(
    /**
     * What to write. The destination builds this from its route; the wide-window pane
     * builds it from the draft the list selected. Keyed into the view model, so opening a
     * different draft is a different view model rather than the previous one being asked
     * to swap its content underneath a half-written reply.
     */
    args: EmailComposeArgs,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailComposeViewModel = hiltViewModel<EmailComposeViewModel, EmailComposeViewModel.Factory>(
        key = "email-compose:${args.mode}:${args.draftId ?: args.sourceEmailId ?: "new"}",
        creationCallback = { factory -> factory.create(args) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val initialBody by viewModel.initialBody.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()

    val editorState = rememberRichTextEditorState()
    var showAttachmentPicker by remember { mutableStateOf(false) }
    var showImagePicker by remember { mutableStateOf(false) }

    // Starts the prefill, handing in the fallback signature. Deliberately not done in the
    // view model's `init`: the default is a string resource, and `init` would run before
    // the composable could supply it.
    val defaultSignature = stringResource(R.string.email_default_signature)
    LaunchedEffect(Unit) { viewModel.start(defaultSignature) }

    var confirmingNoSubject by remember { mutableStateOf(false) }
    val messages = rememberMessageBar()

    // Compose errors are label keys, resolved here — a view model has no resources, and a
    // raw key on screen is worse than no message at all.
    val recipientRequired = stringResource(R.string.email_recipient_required)
    val attachmentLimit = stringResource(R.string.email_attachment_limit_error)
    val attachmentFailed = stringResource(R.string.attachment_failed)
    val attachmentBusy = stringResource(R.string.attachment_in_progress)
    val genericError = stringResource(R.string.email_something_went_wrong)

    // Resolved in composition, not inside the effect: `asLabel` reads the label dictionary
    // from a composition local and cannot be called from a coroutine.
    val errorMessage = state.error?.let { key ->
        when (key) {
            "email_recipient_required" -> recipientRequired
            "email_attachment_limit" -> attachmentLimit
            "email_attachment_failed" -> attachmentFailed
            "email_attachment_in_progress" -> attachmentBusy
            // Anything else is a server label key; the dictionary resolves what it can and
            // the generic message covers what it cannot.
            else -> key.asLabel().ifBlank { genericError }
        }
    }

    messages.ShowOnce(errorMessage) { viewModel.consumeError() }

    // The prefill lands asynchronously — a reply has to fetch the message it is quoting —
    // so it is pushed into the editor when it arrives rather than at construction. Applied
    // exactly once: re-running it would wipe whatever the user typed while it was in
    // flight.
    var bodyInjected by remember { mutableStateOf(false) }
    LaunchedEffect(initialBody) {
        if (bodyInjected) return@LaunchedEffect
        initialBody?.let {
            editorState.setHtml(it)
            bodyInjected = true
        }
    }

    LaunchedEffect(editorState.html) {
        viewModel.onBodyChanged(editorState.html)
    }

    LaunchedEffect(closed) {
        if (closed) onClose()
    }

    MessageBarBox(state = messages, modifier = modifier) {
    ComposeScreen(
        state = state,
        editorState = editorState,
        onClose = { viewModel.close(editorState.html) },
        onSend = {
            // v2's first send-time check, and the only one that asks rather than refuses:
            // a subjectless mail is usually a slip, but occasionally deliberate.
            if (state.subject.isBlank()) {
                confirmingNoSubject = true
            } else {
                viewModel.send(editorState.html)
            }
        },
        onAttach = { showAttachmentPicker = true },
        onRemoveAttachment = viewModel::removeAttachment,
        onRetryAttachment = viewModel::retryAttachment,
        onInsertImage = { showImagePicker = true },
        onSubjectChange = viewModel::setSubject,
        onRecipientInputChange = viewModel::setRecipientInput,
        onCommitRecipient = viewModel::commitRecipient,
        onRemoveRecipient = viewModel::removeRecipient,
        onFocusRow = viewModel::focusRow,
        onPickSuggestion = viewModel::pickSuggestion,
    )
    }

    if (confirmingNoSubject) {
        ZillitConfirmDialog(
            title = stringResource(R.string.email_no_subject_row),
            message = stringResource(R.string.email_no_subject_dialog_message),
            confirmLabel = stringResource(R.string.email_send),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                confirmingNoSubject = false
                viewModel.send(editorState.html)
            },
            onDismiss = { confirmingNoSubject = false },
        )
    }

    if (showImagePicker) {
        val notAnImage = stringResource(R.string.email_image_only)
        val scope = rememberCoroutineScope()

        AttachmentPickerSheet(
            // Pictures only: this is placing an image **in** the message, and a PDF has no
            // representation inside a body.
            options = setOf(AttachmentOption.CAMERA, AttachmentOption.GALLERY),
            onResult = { result ->
                showImagePicker = false
                if (result is AttachmentResult.Media) {
                    scope.launch {
                        result.items.forEach { item ->
                            val html = viewModel.addInlineImage(
                                localPath = item.localPath,
                                fileName = item.fileName,
                                mimeType = item.mimeType,
                            )
                            if (html == null) messages.post(notAnImage) else editorState.insertHtml(html)
                        }
                    }
                }
            },
            onDismiss = { showImagePicker = false },
        )
    }

    if (showAttachmentPicker) {
        AttachmentPickerSheet(
            // Mail takes files, not places or people: a location pin or a contact card has
            // no representation in an email body, and v2 offers neither here either.
            options = setOf(
                AttachmentOption.CAMERA,
                AttachmentOption.GALLERY,
                AttachmentOption.VIDEO,
                AttachmentOption.VIDEO_GALLERY,
                AttachmentOption.DOCUMENT,
                AttachmentOption.AUDIO,
            ),
            onResult = { result ->
                showAttachmentPicker = false
                if (result is AttachmentResult.Media) {
                    result.items.forEach { item ->
                        viewModel.addAttachment(
                            localPath = item.localPath,
                            fileName = item.fileName,
                            mimeType = item.mimeType,
                        )
                    }
                }
            },
            onDismiss = { showAttachmentPicker = false },
        )
    }
}

@Composable
fun EmailSearchRoute(
    onBack: () -> Unit,
    onOpenDetail: (emailId: String, folderName: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    EmailSearchScreen(
        modifier = modifier,
        state = state,
        onQueryChange = viewModel::setQuery,
        onBack = onBack,
        onToggleFilters = viewModel::toggleFilters,
        onToggleFolder = viewModel::toggleFolder,
        onToggleField = viewModel::toggleField,
        onReadFilterChange = viewModel::setReadFilter,
        onHasAttachmentsChange = viewModel::setHasAttachments,
        onResultClick = { onOpenDetail(it.id, it.folderName) },
    )
}

/**
 * Pick a destination folder.
 *
 * Hands its answer back through the caller rather than a shared result holder, so the list
 * that opened it decides what a chosen folder means.
 */
@Composable
fun EmailFolderPickerRoute(
    sourceFolder: String,
    onPicked: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FolderPickerViewModel = hiltViewModel(),
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    FolderPickerScreen(
        modifier = modifier,
        folders = folders,
        sourceFolder = sourceFolder,
        loading = folders.isEmpty(),
        // The picker only reports the choice. Whoever opened it owns the selection and does
        // the move — this screen has no idea which messages are being moved.
        onPick = { onPicked(it.folderName) },
        onBack = onBack,
    )
}

@Composable
fun EmailSettingsRoute(
    onBack: () -> Unit,
    onSignatures: () -> Unit,
    onGroups: () -> Unit,
    onRules: () -> Unit,
    onBccPresets: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailSettingsViewModel = hiltViewModel(),
) {
    val conversationView by viewModel.conversationView.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val forwarding by viewModel.forwarding.collectAsStateWithLifecycle()
    val forwardingError by viewModel.forwardingError.collectAsStateWithLifecycle()
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()
    val revealed by viewModel.revealedPassword.collectAsStateWithLifecycle()
    val revealing by viewModel.revealing.collectAsStateWithLifecycle()
    val revealError by viewModel.revealError.collectAsStateWithLifecycle()

    var showForwarding by remember { mutableStateOf(false) }
    var showCredentials by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    var forwardingAddress by remember { mutableStateOf("") }

    val messages = rememberMessageBar()
    val error by viewModel.error.collectAsStateWithLifecycle()
    messages.ShowOnce(error?.toUiText()?.resolve()) { viewModel.consumeError() }

    // The view model deals in keys because it has no resources. Both of these are shown
    // inline rather than as a message, so they are resolved here.
    val revealTooFast = stringResource(R.string.password_reveal_too_fast_txt)
    val revealUnavailable = stringResource(R.string.password_reveal_unavailable_txt)
    val revealFailed = stringResource(R.string.password_reveal_failed_txt)
    val invalidEmail = stringResource(R.string.valid_email)
    val forwardingSaved = stringResource(R.string.email_forwarding_saved)
    val forwardingRemoved = stringResource(R.string.email_forwarding_removed)
    val passwordUpdated = stringResource(R.string.email_password_updated)

    val revealErrorText = revealError?.let { key ->
        when (key) {
            "reveal_rate_limited_locally" -> revealTooFast
            "password_reveal_unavailable", "email_credentials_not_available" -> revealUnavailable
            else -> key.asLabel().ifBlank { revealFailed }
        }
    }
    val forwardingErrorText = forwardingError?.let { key ->
        if (key == "valid_email") invalidEmail else key.asLabel().ifBlank { invalidEmail }
    }

    MessageBarBox(state = messages, modifier = modifier) {
    EmailSettingsScreen(
        conversationView = conversationView,
        isAdmin = viewModel.isAdmin,
        saving = saving,
        onBack = onBack,
        onConversationViewChange = viewModel::setConversationView,
        onSignatures = onSignatures,
        onEmailGroups = onGroups,
        onBccPresets = onBccPresets,
        onEmailRules = onRules,
        onEmailForwarding = {
            viewModel.loadForwarding()
            showForwarding = true
        },
        onExternalSetup = {
            viewModel.loadCredentials()
            showCredentials = true
        },
    )

    }

    LaunchedEffect(forwarding) {
        // Only prefills while the sheet is shut, so it cannot overwrite something being typed.
        if (!showForwarding) forwardingAddress = forwarding.forwardTo
    }

    if (showForwarding) {
        EmailForwardingSheet(
            address = forwardingAddress,
            configured = forwarding.forwardTo.isNotBlank(),
            busy = saving,
            error = forwardingErrorText,
            onAddressChange = { forwardingAddress = it },
            onSave = {
                viewModel.saveForwarding(forwardingAddress) {
                    showForwarding = false
                    messages.post(forwardingSaved)
                }
            },
            onRemove = {
                viewModel.removeForwarding {
                    showForwarding = false
                    messages.post(forwardingRemoved)
                }
            },
            onDismiss = { showForwarding = false },
        )
    }

    if (showCredentials) {
        MailCredentialsSheet(
            credentials = credentials,
            revealedPassword = revealed,
            revealing = revealing,
            revealError = revealErrorText,
            canChangePassword = viewModel.canChangePassword,
            onToggleReveal = viewModel::togglePassword,
            onCopyPassword = viewModel::passwordForClipboard,
            onChangePassword = { changingPassword = true },
            onDismiss = {
                // Closing forgets the plaintext; reopening is another audited fetch.
                viewModel.forgetPassword()
                showCredentials = false
            },
        )
    }

    if (changingPassword) {
        ChangePasswordDialog(
            onConfirm = { password ->
                viewModel.updatePassword(password) {
                    // The stored ciphertext has changed, so any plaintext this session is
                    // holding is now the *old* password.
                    viewModel.forgetPassword()
                    changingPassword = false
                    messages.post(passwordUpdated)
                }
            },
            onDismiss = { changingPassword = false },
        )
    }
}

@Composable
fun EmailSignaturesRoute(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailSettingsViewModel = hiltViewModel(),
) {
    val signatures by viewModel.signatures.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var deleting by remember { mutableStateOf<EmailSignature?>(null) }
    val messages = rememberMessageBar()
    messages.ShowOnce(error?.toUiText()?.resolve()) { viewModel.consumeError() }

    LaunchedEffect(Unit) { viewModel.loadSignatures() }

    MessageBarBox(state = messages, modifier = modifier) {
        SignatureListScreen(
            signatures = signatures,
            loading = loading,
            onBack = onBack,
            onAdd = { onEdit(null) },
            onEdit = { onEdit(it.id) },
            onDelete = { deleting = it },
        )
    }

    DeleteConfirmDialog(
        target = deleting,
        title = stringResource(R.string.email_delete_signature_title),
        name = { it.name },
        onConfirm = { viewModel.deleteSignature(it.id) },
        onDismiss = { deleting = null },
    )
}

@Composable
fun EmailSignatureEditorRoute(
    signatureId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailSettingsViewModel = hiltViewModel(),
) {
    val signatures by viewModel.signatures.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()

    // Loaded here, not inherited: this is its own nav destination, so `hiltViewModel()`
    // hands it a **new** view model whose list is empty — the signature the previous screen
    // fetched is not in it, and the editor opened blank.
    LaunchedEffect(Unit) { viewModel.loadSignatures() }

    val existing = signatures.firstOrNull { it.id == signatureId }
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    val nameRequired = stringResource(R.string.email_name_required)

    val editorState = rememberRichTextEditorState()

    LaunchedEffect(existing) {
        existing?.content?.takeIf { it.isNotBlank() }?.let(editorState::setHtml)
    }

    EditSignatureScreen(
        modifier = modifier,
        name = name,
        editorState = editorState,
        isNew = signatureId == null,
        nameError = nameError,
        saving = saving,
        onNameChange = {
            name = it
            nameError = null
        },
        onBack = onBack,
        onSave = {
            if (name.isBlank()) {
                nameError = nameRequired
            } else {
                viewModel.saveSignature(signatureId, name, editorState.html, onBack)
            }
        },
    )
}

/**
 * BCC presets.
 *
 * The validation messages arrive as label keys, so they are resolved to copy here rather
 * than in the view model — a view model that knew about `R.string` could not be tested
 * without a resource table.
 */
@Composable
fun BccPresetsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailSettingsViewModel = hiltViewModel(),
) {
    val presets by viewModel.bccPresets.collectAsStateWithLifecycle()
    val draft by viewModel.bccDraft.collectAsStateWithLifecycle()
    val errorKey by viewModel.bccError.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadBccPresets() }

    val error = when (errorKey) {
        "email_bcc_required" -> stringResource(R.string.email_bcc_required)
        "email_bcc_duplicate" -> stringResource(R.string.email_bcc_duplicate)
        "valid_email" -> stringResource(R.string.valid_email)
        else -> errorKey
    }

    BccPresetsScreen(
        modifier = modifier,
        presets = presets,
        draft = draft,
        error = error,
        saving = saving,
        onDraftChange = viewModel::setBccDraft,
        onAdd = viewModel::addBccPreset,
        onRemove = viewModel::removeBccPreset,
        onBack = onBack,
    )
}

@Composable
fun EmailGroupsRoute(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailSettingsViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var deleting by remember { mutableStateOf<EmailGroup?>(null) }
    val messages = rememberMessageBar()
    messages.ShowOnce(error?.toUiText()?.resolve()) { viewModel.consumeError() }

    LaunchedEffect(Unit) { viewModel.loadGroups() }

    MessageBarBox(state = messages, modifier = modifier) {
        EmailGroupsScreen(
            groups = groups,
            loading = loading,
            onBack = onBack,
            onAdd = { onEdit(null) },
            onEdit = { onEdit(it.id) },
            onDelete = { deleting = it },
        )
    }

    DeleteConfirmDialog(
        target = deleting,
        title = stringResource(R.string.email_delete_group_title),
        name = { it.groupName },
        onConfirm = { viewModel.deleteGroup(it.id) },
        onDismiss = { deleting = null },
    )
}

@Composable
fun EmailGroupEditorRoute(
    groupId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailSettingsViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val available by viewModel.availableMembers.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val messages = rememberMessageBar()
    messages.ShowOnce(error?.toUiText()?.resolve()) { viewModel.consumeError() }
    val memberRequired = stringResource(R.string.email_group_member_required)
    val noEmailAddresses = stringResource(R.string.email_group_member_no_email)

    // Same reason as the signature editor: its own destination, its own view model.
    LaunchedEffect(Unit) { viewModel.loadGroups() }

    val existing = groups.firstOrNull { it.id == groupId }
    var name by remember(existing) { mutableStateOf(existing?.groupName.orEmpty()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    val nameRequired = stringResource(R.string.email_name_required)
    var members by remember(existing, available) {
        mutableStateOf(
            // A member the directory no longer knows still round-trips, as a bare address —
            // v2 drops them silently, so leaving the project removes you from every group.
            existing?.memberEmails.orEmpty().map { email ->
                available.firstOrNull { it.email.equals(email, ignoreCase = true) }
                    ?: GroupMember(userId = email, name = email, email = email)
            },
        )
    }

    LaunchedEffect(Unit) { viewModel.loadAvailableMembers() }

    var showMemberPicker by remember { mutableStateOf(false) }

    MessageBarBox(state = messages, modifier = modifier) {
    EditEmailGroupScreen(
        name = name,
        members = members,
        isNew = groupId == null,
        nameError = nameError,
        saving = saving,
        onNameChange = {
            name = it
            nameError = null
        },
        onSelectMembers = { showMemberPicker = true },
        onRemoveMember = { members = members - it },
        onBack = onBack,
        // v2's three checks, in v2's order. A group with nobody in it is accepted by the
        // server and is then a distribution list that reaches no one.
        onSave = {
            val addresses = members.map { it.email }.filter { it.isNotBlank() }
            when {
                name.isBlank() -> nameError = nameRequired
                members.isEmpty() -> messages.post(memberRequired)
                addresses.isEmpty() -> messages.post(noEmailAddresses)
                else -> viewModel.saveGroup(groupId, name, addresses, onBack)
            }
        },
    )
    }

    if (showMemberPicker) {
        CommonListPicker(
            title = stringResource(R.string.email_group_select_members),
            // Project users only — the service resolves members against the project, so a
            // free-form address would simply be dropped on save.
            items = available.map { member ->
                CommonListItem(
                    id = member.userId,
                    title = member.name,
                    subtitle = listOf(member.designation, member.email)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                )
            },
            isMultiSelect = true,
            selectedIds = members.map { it.userId }.toSet(),
            onMultiSelected = { picked ->
                val byId = available.associateBy { it.userId }
                // Members the directory no longer knows are kept. They are not in the
                // picker's list at all, so rebuilding purely from what came back would
                // silently drop everyone who has left the project the moment the picker
                // was opened — even if nothing in it was changed.
                val unknown = members.filterNot { it.userId in byId }
                members = unknown + picked.mapNotNull { byId[it.id] }
                showMemberPicker = false
            },
            onDismiss = { showMemberPicker = false },
        )
    }
}

@Composable
fun EmailContactsRoute(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    onCompose: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailContactsViewModel = hiltViewModel(),
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var deleting by remember { mutableStateOf<EmailContact?>(null) }
    val messages = rememberMessageBar()
    messages.ShowOnce(error?.toUiText()?.resolve()) { viewModel.consumeError() }

    MessageBarBox(state = messages, modifier = modifier) {
        ContactListScreen(
            contacts = contacts,
            query = query,
            loading = loading,
            onQueryChange = viewModel::setQuery,
            onBack = onBack,
            onAdd = { onEdit(null) },
            onEdit = { onEdit(it.id) },
            onDelete = { deleting = it },
            onEmail = { onCompose(it.emailAddress) },
        )
    }

    DeleteConfirmDialog(
        target = deleting,
        title = stringResource(R.string.email_delete_contact_title),
        name = { it.displayName },
        onConfirm = { viewModel.delete(it) },
        onDismiss = { deleting = null },
    )
}

@Composable
fun EmailContactEditorRoute(
    contactId: String?,
    prefillEmail: String?,
    prefillName: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailContactsViewModel = hiltViewModel(),
) {
    // The contacts list is fetched by its own destination's view model, so the editor has
    // to fetch it too before it can find the contact it was asked to edit.
    LaunchedEffect(Unit) {
        viewModel.load()
        viewModel.loadCountries()
    }

    val countries by viewModel.countries.collectAsStateWithLifecycle()
    val postalAreas by viewModel.postalAreas.collectAsStateWithLifecycle()
    var showCountryPicker by remember { mutableStateOf(false) }

    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val existing = remember(contacts, contactId) {
        contactId?.let { id -> contacts.firstOrNull { it.id == id } }
    }

    var form by remember(existing) {
        mutableStateOf(
            ContactFormState(
                firstName = existing?.firstName ?: prefillName?.substringBefore(' ').orEmpty(),
                lastName = existing?.lastName
                    ?: prefillName?.substringAfter(' ', "").orEmpty(),
                company = existing?.companyName.orEmpty(),
                email = existing?.emailAddress ?: prefillEmail.orEmpty(),
                country = existing?.country.orEmpty(),
                countryCode = existing?.countryCode.orEmpty(),
                phone = existing?.phoneNumber.orEmpty(),
                zipCode = existing?.zipCode.orEmpty(),
                state = existing?.state.orEmpty(),
                city = existing?.city.orEmpty(),
                address = existing?.address.orEmpty(),
                notes = existing?.notes.orEmpty(),
            ),
        )
    }

    val messages = rememberMessageBar()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val countryFirst = stringResource(R.string.email_select_country_first)
    messages.ShowOnce(error?.toUiText()?.resolve()) { viewModel.consumeError() }

    MessageBarBox(state = messages, modifier = modifier) {
    EditContactScreen(
        form = form,
        isNew = contactId == null,
        onFormChange = { form = it },
        onPickCountry = { showCountryPicker = true },
        onLookUpPostcode = {
            viewModel.lookUpPostalCode(form.countryIso, form.zipCode)
        },
        // The postcode lookup needs a country to look it up in. Without this the field
        // simply did nothing and never said why.
        onCountryRequired = { messages.post(countryFirst) },
        onBack = onBack,
        onSave = {
            viewModel.save(
                contact = EmailContact(
                    id = contactId.orEmpty(),
                    firstName = form.firstName,
                    lastName = form.lastName,
                    // Falls back to the address, as v2 does: a contact saved with only an
                    // email would otherwise reach the server with a blank name and come
                    // back nameless on every other device.
                    contactName = "${form.firstName} ${form.lastName}".trim()
                        .ifBlank { form.email.trim() },
                    emailAddress = form.email,
                    companyName = form.company,
                    phoneNumber = form.phone,
                    countryCode = form.countryCode,
                    address = form.address,
                    city = form.city,
                    state = form.state,
                    zipCode = form.zipCode,
                    country = form.country,
                    notes = form.notes,
                ),
                onValidationError = { message ->
                    form = when (message) {
                        "p_choose_country" -> form.copy(countryError = message)
                        "p_enter_valid_phone_number" -> form.copy(phoneError = message)
                        else -> form.copy(emailError = message)
                    }
                },
                onDone = onBack,
            )
        },
    )
    }

    // A saved contact stores its country by **name** and its dial code, not its ISO code —
    // so the ISO is recovered once the country list loads. Without it the postcode lookup
    // stays disabled on every contact opened for editing.
    LaunchedEffect(countries, existing) {
        if (form.countryIso.isBlank() && form.country.isNotBlank()) {
            countries.firstOrNull { it.name.equals(form.country, ignoreCase = true) }
                ?.let { form = form.copy(countryIso = it.code) }
        }
    }

    if (showCountryPicker) {
        CommonListPicker(
            title = stringResource(R.string.txt_select_country),
            items = countries.map { country ->
                CommonListItem(
                    id = country.code,
                    title = country.name,
                    subtitle = country.dialCode,
                )
            },
            onSingleSelected = { picked ->
                val country = countries.firstOrNull { it.code == picked.id }
                form = form.copy(
                    country = country?.name.orEmpty(),
                    countryIso = country?.code.orEmpty(),
                    countryCode = country?.dialCode.orEmpty(),
                    countryError = null,
                    // Changing country invalidates anything derived from the old one, so
                    // the postcode and what it filled in are cleared rather than left to
                    // describe a different country.
                    zipCode = "",
                    state = "",
                    city = "",
                )
                showCountryPicker = false
            },
            onDismiss = { showCountryPicker = false },
        )
    }

    if (postalAreas.isNotEmpty()) {
        CommonListPicker(
            title = stringResource(R.string.choose_address),
            items = postalAreas.mapIndexed { index, area ->
                CommonListItem(id = index.toString(), title = area.display)
            },
            searchable = false,
            onSingleSelected = { picked ->
                postalAreas.getOrNull(picked.id.toIntOrNull() ?: -1)?.let { area ->
                    form = form.copy(state = area.state, city = area.city)
                }
                viewModel.dismissPostalAreas()
            },
            onDismiss = viewModel::dismissPostalAreas,
        )
    }
}

@Composable
fun EmailRulesRoute(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    onHistory: (ruleId: String, ruleName: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailRulesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var deleting by remember { mutableStateOf<EmailRule?>(null) }
    val messages = rememberMessageBar()
    val deleted = stringResource(R.string.email_rule_delete_success)
    val maxRules = stringResource(R.string.email_rule_max_reached, EmailRule.MAX_RULES_PER_MAILBOX)

    messages.ShowOnce(error?.toUiText()?.resolve()) { viewModel.consumeError() }

    MessageBarBox(state = messages, modifier = modifier) {
        EmailRulesScreen(
            state = state,
            onBack = onBack,
            onScopeChange = viewModel::setScope,
            // The button is dimmed at the cap but still tappable, so the refusal has to be
            // said out loud — otherwise the user fills in a whole rule and is rejected on
            // save with no explanation.
            onCreate = { if (state.canCreate) onEdit(null) else messages.post(maxRules) },
            onEdit = { onEdit(it.id) },
            onHistory = { onHistory(it.id, it.name) },
            onDelete = { deleting = it },
            onToggleEnabled = viewModel::setEnabled,
            onMove = viewModel::move,
            onCommitOrder = viewModel::commitOrder,
        )
    }

    deleting?.let { rule ->
        ZillitConfirmDialog(
            title = stringResource(R.string.email_rule_delete_title),
            message = stringResource(R.string.email_rule_delete_message, rule.name),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(R.string.cancel),
            isDestructive = true,
            onConfirm = {
                deleting = null
                viewModel.delete(rule)
                messages.post(deleted)
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
fun EmailRuleEditorRoute(
    ruleId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailRulesViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val issue by viewModel.issue.collectAsStateWithLifecycle()
    val busy by viewModel.editorBusy.collectAsStateWithLifecycle()
    val folderNames by viewModel.folderNames.collectAsStateWithLifecycle()
    val senderSuggestions by viewModel.senderSuggestions.collectAsStateWithLifecycle()

    val drivePicker by viewModel.drivePicker.collectAsStateWithLifecycle()
    val drivePickerFor by viewModel.drivePickerFor.collectAsStateWithLifecycle()
    val editorLoading by viewModel.editorLoading.collectAsStateWithLifecycle()
    val actionLimit by viewModel.actionLimitReached.collectAsStateWithLifecycle()
    val ruleError by viewModel.error.collectAsStateWithLifecycle()
    var creatingDriveFolder by remember { mutableStateOf(false) }
    var creatingFolderFor by remember { mutableStateOf<Int?>(null) }

    val ruleMessages = rememberMessageBar()
    val maxActions = stringResource(R.string.email_rule_max_actions, EmailRule.MAX_ACTIONS)
    val ruleSaved = stringResource(R.string.email_rule_saved)

    ruleMessages.ShowOnce(ruleError?.toUiText()?.resolve()) { viewModel.consumeError() }
    ruleMessages.ShowOnce(maxActions.takeIf { actionLimit }) { viewModel.consumeActionLimit() }

    LaunchedEffect(ruleId) { viewModel.openEditor(ruleId) }

    MessageBarBox(state = ruleMessages, modifier = modifier) {
    EditEmailRuleScreen(
        draft = draft,
        isNew = ruleId == null,
        saving = busy && !editorLoading,
        // Covers the fetch as well as the save, so a late arrival cannot land on top of
        // something the user has already started typing.
        loading = editorLoading,
        issue = issue,
        folderNames = folderNames,
        senderSuggestions = senderSuggestions,
        onDraftChange = viewModel::updateDraft,
        onAddAction = viewModel::addAction,
        onPickDriveFolder = viewModel::openDrivePicker,
        onCreateFolder = { creatingFolderFor = it },
        onBack = onBack,
        onSave = {
            viewModel.save {
                ruleMessages.post(ruleSaved)
                onBack()
            }
        },
    )
    }

    creatingFolderFor?.let { index ->
        FolderNameDialog(
            title = stringResource(R.string.email_create_folder_title),
            confirmLabel = stringResource(R.string.email_create_action),
            initial = "",
            onConfirm = {
                viewModel.createEmailFolder(index, it)
                creatingFolderFor = null
            },
            onDismiss = { creatingFolderFor = null },
        )
    }

    if (drivePickerFor != null) {
        RuleDriveFolderPickerSheet(
            state = drivePicker,
            onSectionChange = viewModel::setDriveSection,
            onOpenFolder = viewModel::openDriveFolder,
            // Back walks up the tree first; only at the root does it close the sheet.
            onNavigateUp = { if (!viewModel.driveFolderUp()) viewModel.closeDrivePicker() },
            onCreateFolder = { creatingDriveFolder = true },
            onSelect = { folder ->
                drivePickerFor?.let { index ->
                    viewModel.setDriveFolder(index, folder.id, folder.name)
                }
                viewModel.closeDrivePicker()
            },
            onDismiss = viewModel::closeDrivePicker,
        )
    }

    if (creatingDriveFolder) {
        NameDialog(
            title = stringResource(R.string.email_rule_drive_create_folder),
            hint = stringResource(R.string.email_rule_drive_folder_name),
            confirmLabel = stringResource(R.string.email_rule_create_folder_confirm),
            onConfirm = {
                viewModel.createDriveFolder(it)
                creatingDriveFolder = false
            },
            onDismiss = { creatingDriveFolder = false },
        )
    }
}

/**
 * A one-field dialog: a name, and a button.
 *
 * Shared by "new Drive folder" and anything else that needs the same shape, rather than
 * each screen building its own `AlertDialog` around a text field.
 */
@Composable
private fun NameDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FormTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = hint,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        containerColor = ZillitTheme.colors.surface,
    )
}

@Composable
fun EmailRuleExecutionsRoute(
    ruleId: String,
    ruleName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailRulesViewModel = hiltViewModel(),
) {
    val executions by viewModel.executions.collectAsStateWithLifecycle()
    val filter by viewModel.executionFilter.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()

    LaunchedEffect(ruleId) { viewModel.loadExecutions(ruleId) }

    RuleExecutionsScreen(
        modifier = modifier,
        ruleName = ruleName,
        executions = executions,
        statusFilter = filter,
        loading = executions.isEmpty() && loadingMore,
        loadingMore = loadingMore,
        onStatusFilterChange = { viewModel.setExecutionFilter(ruleId, it) },
        onLoadMore = { viewModel.loadExecutions(ruleId, reset = false) },
        onBack = onBack,
    )
}

/** The compose modes, as the detail screen's reply actions map onto them. */
private val com.zillit.zillitapp.feature.email.ui.detail.ReplyAction.mode: String
    get() = when (this) {
        com.zillit.zillitapp.feature.email.ui.detail.ReplyAction.REPLY ->
            com.zillit.zillitapp.navigation.EmailCompose.MODE_REPLY

        com.zillit.zillitapp.feature.email.ui.detail.ReplyAction.REPLY_ALL ->
            com.zillit.zillitapp.navigation.EmailCompose.MODE_REPLY_ALL

        com.zillit.zillitapp.feature.email.ui.detail.ReplyAction.FORWARD ->
            com.zillit.zillitapp.navigation.EmailCompose.MODE_FORWARD
    }

