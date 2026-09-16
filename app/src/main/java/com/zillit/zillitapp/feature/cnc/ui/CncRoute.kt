package com.zillit.zillitapp.feature.cnc.ui

import androidx.activity.compose.BackHandler
import com.zillit.zillitapp.core.ui.components.ListDetailLayout
import com.zillit.zillitapp.core.ui.components.isMultiPane
import com.zillit.zillitapp.core.ui.window.LocalWindowSize
import com.zillit.zillitapp.feature.cnc.ui.list.CncTab
import com.zillit.zillitapp.feature.cnc.ui.list.CallActivityPane
import com.zillit.zillitapp.core.ui.components.EmptyState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.feature.cnc.ui.list.CncListViewModel
import com.zillit.zillitapp.feature.cnc.ui.thread.CncThreadArgs
import com.zillit.zillitapp.feature.cnc.ui.thread.CncThreadViewModel
import com.zillit.zillitapp.feature.cnc.ui.group.CncGroupViewModel
import com.zillit.zillitapp.feature.cnc.ui.profile.CncProfileViewModel
import com.zillit.zillitapp.feature.cnc.ui.group.CreateGroupScreen
import com.zillit.zillitapp.feature.cnc.ui.list.ConversationKind
import com.zillit.zillitapp.feature.cnc.ui.profile.GroupInfoScreen
import com.zillit.zillitapp.feature.cnc.ui.profile.PersonProfileScreen
import com.zillit.zillitapp.core.ui.chat.AudioBubbleState
import com.zillit.zillitapp.core.ui.chat.ChatSelectionMode
import com.zillit.zillitapp.core.ui.chat.ChatMessageOption
import com.zillit.zillitapp.core.ui.chat.ChatMessageOptions
import com.zillit.zillitapp.core.ui.chat.ChatMessageOptionsSheet
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.core.ui.chat.model.SendState
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.cnc.ui.thread.CncThreadScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import com.zillit.zillitapp.core.ui.chat.ChatLibraryScreen
import com.zillit.zillitapp.core.ui.chat.MediaViewerScreen
import com.zillit.zillitapp.core.ui.chat.ShareLauncher
import com.zillit.zillitapp.core.ui.chat.openFileExternally
import com.zillit.zillitapp.core.ui.components.FullScreenSurface
import com.zillit.zillitapp.core.ui.chat.ReadByUserScreen
import com.zillit.zillitapp.core.ui.chat.ChatLibraryTab
import com.zillit.zillitapp.core.attachment.AttachmentOption
import com.zillit.zillitapp.core.attachment.AttachmentPickerSheet
import com.zillit.zillitapp.core.attachment.AttachmentResult
import com.zillit.zillitapp.feature.cnc.ui.list.CallDetailsSheet
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.zillit.zillitapp.feature.cnc.ui.list.ConversationRowActions
import com.zillit.zillitapp.feature.cnc.ui.list.ConversationActionsSheet
import com.zillit.zillitapp.core.ui.chat.SaveToDeviceLauncher
import android.widget.Toast

/**
 * The C&C tab: the three lists, and nothing else.
 *
 * Everything a row opens — a conversation, a profile, group info, creating a group — is a
 * **destination of its own**, not a screen swapped into this container. That is what keeps
 * the project bar and the tab bar off a conversation, gives each page one top bar instead
 * of two, and makes back mean the same thing here as everywhere else in the app.

 */
@Composable
fun CncRoute(
    onOpenConversation: (id: String, isGroup: Boolean) -> Unit,
    onOpenProfile: (id: String) -> Unit,
    onCreateGroup: () -> Unit,
    /**
     * Profile or group info for the conversation in the pane.
     *
     * Separate from [onOpenProfile], which the Contacts tab uses and which only ever names
     * a person: a group's details are a different destination.
     */
    onOpenProfileOrGroup: (id: String, isGroup: Boolean) -> Unit = { id, _ -> onOpenProfile(id) },
    modifier: Modifier = Modifier,
    viewModel: CncListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val callDetails by viewModel.callDetails.collectAsStateWithLifecycle()
    val rowActions by viewModel.rowActions.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(viewModel) {
        viewModel.locationRequests.collect { uriHandler.openUri(it) }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { res ->
            Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
        }
    }
    var confirmingLeave by remember { mutableStateOf<ConversationRowActions?>(null) }
    var confirmingDelete by remember { mutableStateOf<ConversationRowActions?>(null) }

    val openedConversation by viewModel.openedConversation.collectAsStateWithLifecycle()
    val openedCall by viewModel.openedCall.collectAsStateWithLifecycle()
    val windowSize = LocalWindowSize.current
    val multiPane = windowSize.isMultiPane

    // What the second pane is showing, if anything. A conversation and a call activity
    // belong to different tabs, so only the open tab's selection can fill the pane —
    // switching tabs must not leave the previous tab's pane beside the new list.
    val paneConversation = openedConversation?.takeIf { multiPane && state.tab == CncTab.CHAT }
    val paneCall = openedCall?.takeIf { multiPane && state.tab == CncTab.CALL }

    // Back with something open beside the list closes it, the way it pops the page on a
    // phone — without this it left the whole dashboard.
    BackHandler(enabled = paneConversation != null) { viewModel.closeConversation() }
    BackHandler(enabled = paneCall != null) { viewModel.closeCallActivity() }

    // Folded while reading: what was beside the list becomes the phone's own page, so the
    // person keeps their place instead of landing back on the list.
    LaunchedEffect(multiPane, openedConversation) {
        val opened = openedConversation ?: return@LaunchedEffect
        if (!multiPane) {
            viewModel.closeConversation()
            onOpenConversation(opened.id, opened.isGroup)
        }
    }

    // A call has no page of its own — it is a sheet on a phone — so folding hands it back
    // to the sheet rather than to a destination.
    LaunchedEffect(multiPane, openedCall) {
        val call = openedCall ?: return@LaunchedEffect
        if (!multiPane) {
            viewModel.closeCallActivity()
            viewModel.openCallDetails(call)
        }
    }

    ListDetailLayout(
        modifier = modifier,
        showDetail = paneConversation != null || paneCall != null,
        detailPlaceholder = { CncDetailPlaceholder(isCallTab = state.tab == CncTab.CALL) },
        detail = {
            paneConversation?.let { opened ->
                CncThreadRoute(
                    args = CncThreadArgs(opened.id, opened.isGroup),
                    onBack = viewModel::closeConversation,
                    onOpenDetails = { id, isGroup -> onOpenProfileOrGroup(id, isGroup) },
                )
            } ?: paneCall?.let { callId ->
                CallActivityPane(
                    details = callDetails?.takeIf { it.id == callId },
                    onClose = viewModel::closeCallActivity,
                )
            }
        },
    ) {
    CncScreen(
        state = state,
        modifier = modifier,
        onTab = viewModel::onTab,
        onChatFilter = viewModel::onChatFilter,
        onCallFilter = viewModel::onCallFilter,
        onQuery = viewModel::onQuery,
        // Beside the list where there is room for it; as its own page where there is not.
        onOpenConversation = { row ->
            val isGroup = row.kind == ConversationKind.GROUP
            if (multiPane) viewModel.openConversation(row.id, isGroup)
            else onOpenConversation(row.id, isGroup)
        },
        onConversationActions = { viewModel.openRowActions(it.id) },
        onToggleConversationFavourite = { viewModel.onToggleFavourite(it.id) },
        onCreateGroup = onCreateGroup,
        onClearCallLog = viewModel::clearCallLog,
        onJoinViaLink = { },
        // Same rule as a conversation: the activity fills the second pane where there is
        // one, and is a sheet over the log where there is not.
        onCallDetails = { row ->
            viewModel.openCallDetails(row.id)
            if (multiPane) viewModel.openCallActivity(row.id)
        },
        onCallBack = { },
        onNewCall = { },
        onLoadMoreCalls = viewModel::loadMoreCalls,
        onOpenProfile = { onOpenProfile(it.id) },
        // Messaging a contact lands in the same place a chat row does — the pane where
        // there is one, a page where there is not. Without this the Contacts tab was the
        // one way into a conversation that ignored the second pane.
        onMessageContact = { contact ->
            if (multiPane) {
                viewModel.onTab(CncTab.CHAT)
                viewModel.openConversation(contact.id, false)
            } else {
                onOpenConversation(contact.id, false)
            }
        },
        onAudioCallContact = { },
        onVideoCallContact = { },
        onShareLocation = { viewModel.openContactLocation(it.id) },
        onToggleContactFavourite = { viewModel.onToggleFavourite(it.id) },
    )
    }

    // One call's details, opened from its row. A row can only say "Group call" and a time,
    // so without this a missed group call reads the same as one everybody joined. Only when
    // there is no pane to put it in — otherwise it would cover the pane showing the same
    // thing.
    callDetails?.takeIf { paneCall == null }?.let { details ->
        CallDetailsSheet(details = details, onDismiss = viewModel::dismissCallDetails)
    }

    // The shortcut v2 puts on a long press. Everything here is also two taps away through
    // the group's own screen; this saves the trip for the two people do most.
    rowActions?.let { actions ->
        ConversationActionsSheet(
            actions = actions,
            onDismiss = viewModel::dismissRowActions,
            onLeave = {
                viewModel.dismissRowActions()
                confirmingLeave = actions
            },
            onDelete = {
                viewModel.dismissRowActions()
                confirmingDelete = actions
            },
        )
    }

    // Both are irreversible, so neither happens on the tap that opened the menu.
    confirmingLeave?.let { actions ->
        ZillitConfirmDialog(
            title = stringResource(R.string.cnc_leave_group),
            message = stringResource(R.string.cnc_leave_group_message, actions.name),
            confirmLabel = stringResource(R.string.cnc_leave_group),
            isDestructive = true,
            onDismiss = { confirmingLeave = null },
            onConfirm = {
                confirmingLeave = null
                viewModel.leaveGroup(actions.conversationId)
            },
        )
    }

    confirmingDelete?.let { actions ->
        ZillitConfirmDialog(
            title = stringResource(R.string.cnc_delete_group),
            message = stringResource(R.string.cnc_delete_group_message, actions.name),
            confirmLabel = stringResource(R.string.cnc_delete_group),
            isDestructive = true,
            onDismiss = { confirmingDelete = null },
            onConfirm = {
                confirmingDelete = null
                viewModel.deleteGroup(actions.conversationId)
            },
        )
    }
}

/**
 * One conversation — a page on a phone, the right-hand pane on a wide window.
 *
 * @param args which conversation. The destination builds this from its back-stack entry;
 *   the pane builds it from the row the list selected. Keyed into the view model, so
 *   picking a different conversation is a different view model rather than the previous one
 *   being asked to swap its messages, draft and selection underneath the user.
 */
@Composable
fun CncThreadRoute(
    args: CncThreadArgs,
    onBack: () -> Unit,
    onOpenDetails: (id: String, isGroup: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CncThreadViewModel = hiltViewModel<CncThreadViewModel, CncThreadViewModel.Factory>(
        key = "cnc-thread:${args.surface ?: "cnc"}:${args.conversationId}",
        creationCallback = { factory -> factory.create(args) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val deleteConfirmation by viewModel.deleteConfirmation.collectAsStateWithLifecycle()
    val openedMediaId by viewModel.openedMediaId.collectAsStateWithLifecycle()
    val libraryOpen by viewModel.libraryOpen.collectAsStateWithLifecycle()
    val readBy by viewModel.readBy.collectAsStateWithLifecycle()
    val jumpTo by viewModel.jumpTo.collectAsStateWithLifecycle()

    var options by remember { mutableStateOf<ChatMessage?>(null) }

    // Handing a file to another app needs a Context, which the view model must not hold —
    // so it names the file and the screen performs the hand-off. Same split as Home.
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val cache = viewModel.mediaCacheForShare
    LaunchedEffect(viewModel) {
        viewModel.openExternally.collect { file -> context.openFileExternally(file, cache) }
    }
    ShareLauncher(requests = viewModel.shareRequests, cache = cache)
    // Saving needs a permission on API 28 and none above it, and asking for one needs an
    // Activity — so the view model names the file and this performs the copy.
    SaveToDeviceLauncher(requests = viewModel.saveRequests)

    // Copy needs a ClipboardManager, which is a Context service: the view model decides what
    // to copy, the screen performs it.
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(viewModel) {
        viewModel.clipboardRequests.collect { clipboard.setText(AnnotatedString(it)) }
    }
    LaunchedEffect(viewModel) {
        viewModel.locationRequests.collect { uriHandler.openUri(it) }
    }
    // A write that did not take says so. Nothing is said on success: the thread changing is
    // the confirmation, and a toast per message would be noise.
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { res ->
            Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
        }
    }

    CncThreadScreen(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onOpenDetails = { onOpenDetails(state.id, state.isGroup) },
        onAttachments = viewModel::openLibrary,
        onAudioCall = { },
        onVideoCall = { },
        onSend = viewModel::send,
        onAttachmentPicked = viewModel::onAttachmentPicked,
        onDraftChanged = viewModel::onDraftChanged,
        onReply = viewModel::startReply,
        onCancelReply = viewModel::cancelReply,
        onLongPress = { options = it },
        onOpenMedia = viewModel::onMediaOpened,
        onReact = viewModel::onReact,
        onOpenQuoted = viewModel::jumpToQuoted,
        jumpToId = jumpTo,
        onJumpHandled = viewModel::onJumpHandled,
        onCancelUpload = viewModel::onCancelUpload,
        onOpenLocation = viewModel::openLocation,
        onRetry = viewModel::onRetry,
        onLoadOlder = viewModel::loadOlder,
        onReachedBottom = viewModel::onReachedBottom,
        onUnblock = viewModel::unblock,
        onSaveEdit = viewModel::saveEdit,
        onCancelEdit = viewModel::cancelEdit,
        selection = selection,
        onToggleSelection = viewModel::toggleSelection,
        onSelectionConfirm = viewModel::confirmSelection,
        onSelectionCancel = viewModel::cancelSelection,
        search = search,
        onSearchOpen = viewModel::openSearch,
        onSearchQueryChange = viewModel::onSearchQueryChanged,
        onSearchNext = viewModel::searchNext,
        onSearchPrevious = viewModel::searchPrevious,
        onSearchClose = viewModel::closeSearch,
        audio = AudioBubbleState(
            playingId = playback.id.takeIf { playback.isPlaying || playback.positionMs > 0 },
            progress = playback.durationMs.takeIf { it > 0 }
                ?.let { playback.positionMs.toFloat() / it } ?: 0f,
            onToggle = viewModel::toggleAudio,
            onSeek = viewModel::seekAudio,
        ),
    )

    // Media, Docs and Links for this conversation, built from the feed already loaded.
    if (libraryOpen) {
        FullScreenSurface(onDismiss = viewModel::dismissLibrary) {
            ChatLibraryScreen(
                title = state.title,
                messages = viewModel.libraryMessages(),
                onOpen = { message ->
                    viewModel.dismissLibrary()
                    viewModel.onMediaOpened(message)
                },
                onOpenLink = { uriHandler.openUri(it) },
                onClose = viewModel::dismissLibrary,
            )
        }
    }

    // The full-screen viewer, which pages through every image and video in the thread
    // rather than showing only the one that was tapped.
    openedMediaId?.let { id ->
        FullScreenSurface(onDismiss = viewModel::dismissMedia) {
            MediaViewerScreen(
                media = viewModel.viewableMedia(),
                initialId = id,
                onClose = viewModel::dismissMedia,
                onShare = viewModel::share,
            )
        }
    }

    // The names behind "Read by 11" in a group.
    readBy?.let { readByState ->
        FullScreenSurface(onDismiss = viewModel::dismissReadBy) {
            ReadByUserScreen(
                state = readByState,
                onClose = viewModel::dismissReadBy,
                onNotify = { },
            )
        }
    }

    // The long-press menu. Which entries appear is decided once, by ChatMessageOptions,
    // rather than by each screen — that shared decision is why Reply is offered on the same
    // terms here as in a unit chat.
    options?.let { message ->
        ChatMessageOptionsSheet(
            message = message,
            options = message.optionsFor(
                canPost = state.canPost,
                isGroup = state.isGroup,
            ),
            onDismiss = { options = null },
            onReact = { emoji -> viewModel.onReact(message, emoji) },
            onOptionSelected = { option ->
                when (option) {
                    ChatMessageOption.Reply ->
                        message.serverIdOrNull()?.let(viewModel::startReply)
                    ChatMessageOption.Edit -> viewModel.startEdit(message)
                    ChatMessageOption.Delete ->
                        viewModel.startSelection(ChatSelectionMode.DELETE, message)
                    ChatMessageOption.Share ->
                        viewModel.startSelection(ChatSelectionMode.SHARE, message)
                    ChatMessageOption.ReadByUser ->
                        message.serverIdOrNull()?.let(viewModel::openReadBy)
                    ChatMessageOption.Copy -> viewModel.copyToClipboard(message)
                    ChatMessageOption.Save -> viewModel.saveToDevice(message)
                    ChatMessageOption.Gallery -> viewModel.openLibrary()
                    // Forward opens the same picker the unit chat uses. Only the entry
                    // point is built, per your instruction; the destination picker is what
                    // finishes it.
                    ChatMessageOption.Forward ->
                        viewModel.startSelection(ChatSelectionMode.SHARE, message)
                    // Everything left belongs to a feature this surface does not have —
                    // translation, printing, Document Distribution, image reply. None of
                    // them is offered in the menu, so nothing here silently does nothing.
                    else -> Unit
                }
                options = null
            },
        )
    }

    deleteConfirmation?.let { ids ->
        ZillitConfirmDialog(
            title = stringResource(R.string.delete),
            message = pluralStringResource(R.plurals.chat_delete_confirm, ids.size, ids.size),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDeleteConfirmation,
            isDestructive = true,
        )
    }
}

/** Naming a group and choosing who is in it, full screen. */
@Composable
fun CreateGroupRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CncGroupViewModel = hiltViewModel(),
) {
    val state by viewModel.createState.collectAsStateWithLifecycle()
    var pickingPhoto by remember { mutableStateOf(false) }

    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { res ->
            Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
        }
    }

    CreateGroupScreen(
        name = state.name,
        onNameChange = viewModel::onNameChange,
        query = state.query,
        onQueryChange = viewModel::onQueryChange,
        contacts = state.candidates,
        selected = state.selected,
        onToggle = viewModel::onToggleMember,
        onPickPhoto = { pickingPhoto = true },
        onBack = onBack,
        onCreate = { viewModel.createGroup(onDone = onBack) },
        isSaving = state.saving,
        isAddingToExistingGroup = state.isAddingToExistingGroup,
        modifier = modifier,
    )

    // Held until the group exists: there is nothing to attach a picture to before that.
    if (pickingPhoto) {
        AttachmentPickerSheet(
            options = setOf(AttachmentOption.CAMERA, AttachmentOption.GALLERY),
            maxSelectable = 1,
            onDismiss = { pickingPhoto = false },
            onResult = { result ->
                pickingPhoto = false
                (result as? AttachmentResult.Media)?.items?.firstOrNull()?.let { picked ->
                    viewModel.onPhotoPicked(
                        localPath = picked.localPath,
                        fileName = picked.fileName,
                        mimeType = picked.mimeType,
                    )
                }
            },
        )
    }
}

/** One person's details, full screen. */
@Composable
fun CncProfileRoute(
    userId: String,
    onBack: () -> Unit,
    onMessage: (id: String) -> Unit,
    /** True asks for Docs rather than Media — the Files shortcut. */
    onOpenLibrary: (documentsFirst: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CncProfileViewModel = hiltViewModel(),
) {
    val profile by viewModel.person.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(viewModel) {
        viewModel.locationRequests.collect { uriHandler.openUri(it) }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { res ->
            Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
        }
    }

    PersonProfileScreen(
        profile = profile,
        modifier = modifier,
        onBack = onBack,
        onAudioCall = { },
        onVideoCall = { },
        onShareLocation = viewModel::openLocation,
        onMessage = { onMessage(userId) },
        onOpenMedia = { onOpenLibrary(false) },
        onOpenFiles = { onOpenLibrary(true) },
        onToggleBlock = viewModel::toggleBlock,
    )
}

/**
 * Media, Docs and Links for one conversation, on its own screen.
 *
 * Backed by the thread's own view model. The destination carries the same two arguments the
 * thread does, so this gets the identical feed — already decrypted, grouped into batches and
 * mapped to display messages — instead of a second pipeline that could disagree with the
 * thread about what is in it.
 */
@Composable
fun CncLibraryRoute(
    documentsFirst: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CncThreadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openedMediaId by viewModel.openedMediaId.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val cache = viewModel.mediaCacheForShare
    LaunchedEffect(viewModel) {
        viewModel.openExternally.collect { file -> context.openFileExternally(file, cache) }
    }
    ShareLauncher(requests = viewModel.shareRequests, cache = cache)

    ChatLibraryScreen(
        title = state.title,
        messages = viewModel.libraryMessages(),
        initialTab = if (documentsFirst) ChatLibraryTab.DOCS else ChatLibraryTab.MEDIA,
        onOpen = viewModel::onMediaOpened,
        onOpenLink = { uriHandler.openUri(it) },
        onClose = onBack,
        modifier = modifier,
    )

    openedMediaId?.let { id ->
        FullScreenSurface(onDismiss = viewModel::dismissMedia) {
            MediaViewerScreen(
                media = viewModel.viewableMedia(),
                initialId = id,
                onClose = viewModel::dismissMedia,
                onShare = viewModel::share,
            )
        }
    }
}

/** One group's details, full screen. */
@Composable
fun CncGroupInfoRoute(
    roomId: String,
    onBack: () -> Unit,
    onMessage: (id: String) -> Unit,
    onMember: (id: String) -> Unit,
    onAddMembers: () -> Unit,
    /** True asks for Docs rather than Media — the Files shortcut. */
    onOpenLibrary: (documentsFirst: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CncProfileViewModel = hiltViewModel(),
) {
    val group by viewModel.group.collectAsStateWithLifecycle()

    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { res ->
            Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
        }
    }

    // Both are irreversible and sit next to each other, so neither happens on one tap.
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingPhoto by remember { mutableStateOf(false) }

    GroupInfoScreen(
        group = group,
        modifier = modifier,
        onBack = onBack,
        onAudioCall = { },
        onVideoCall = { },
        onMessage = { onMessage(roomId) },
        onChangePhoto = { pickingPhoto = true },
        onMember = { onMember(it.id) },
        onAddMembers = onAddMembers,
        onLeave = { confirmLeave = true },
        onOpenMedia = { onOpenLibrary(false) },
        onOpenFiles = { onOpenLibrary(true) },
        onDelete = { confirmDelete = true },
        onRename = viewModel::renameGroup,
        onSetMemberAdmin = { member, isAdmin -> viewModel.setMemberAdmin(member.id, isAdmin) },
        onRemoveMember = { member -> viewModel.removeMember(member.id) },
        currentUserId = viewModel.currentUserId,
    )

    // The same picker the composer uses, narrowed to the two ways you get one picture.
    if (pickingPhoto) {
        AttachmentPickerSheet(
            options = setOf(AttachmentOption.CAMERA, AttachmentOption.GALLERY),
            maxSelectable = 1,
            onDismiss = { pickingPhoto = false },
            onResult = { result ->
                pickingPhoto = false
                (result as? AttachmentResult.Media)?.items?.firstOrNull()?.let { picked ->
                    viewModel.changeGroupPhoto(
                        localPath = picked.localPath,
                        fileName = picked.fileName,
                        mimeType = picked.mimeType,
                    )
                }
            },
        )
    }

    if (confirmLeave) {
        ZillitConfirmDialog(
            title = stringResource(R.string.cnc_leave_group),
            message = stringResource(R.string.cnc_leave_group_message, group.name),
            confirmLabel = stringResource(R.string.cnc_leave_group),
            isDestructive = true,
            onConfirm = {
                confirmLeave = false
                viewModel.leaveGroup(onDone = onBack)
            },
            onDismiss = { confirmLeave = false },
        )
    }

    if (confirmDelete) {
        ZillitConfirmDialog(
            title = stringResource(R.string.cnc_delete_group),
            message = stringResource(R.string.cnc_delete_group_message, group.name),
            confirmLabel = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteGroup(onDone = onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * Which long-press entries this message offers in a socket chat.
 *
 * Stated once here rather than at each call site, which is the whole reason
 * [ChatMessageOptions] exists. What differs from a unit chat: no Publish to Document
 * Distribution, and Read By only on a group, where a count is not the whole answer.
 */
private fun ChatMessage.optionsFor(canPost: Boolean, isGroup: Boolean) = ChatMessageOptions(
    isOwnMessage = isOwn,
    isConfirmed = sendState != null && sendState != SendState.Sending,
    isTextMessage = this is ChatMessage.Text,
    isLocationMessage = this is ChatMessage.Location,
    // Image Reply downloads the original, opens the annotation editor on it and posts the
    // marked-up copy as a new message. That editor is wired for the unit chat only, so the
    // entry stays off this menu rather than sitting in it and doing nothing.
    isImageMessage = false,
    hasAttachment = hasOpenableMedia,
    hasBody = this is ChatMessage.Text,
    // Group admin rights are not the same as project admin, and nothing here needs them.
    isAdmin = false,
    canPost = canPost,
    canDownload = true,
    isWithinEditWindow = System.currentTimeMillis() - createdAt < EDIT_WINDOW_MS,
    // Call Sheet is a Home unit; a socket chat is never one.
    isCallSheet = false,
    isTranslationEnabled = false,
    isAlreadyTranslated = isTranslated,
).let { base ->
    if (isGroup) base else base.copy(isAdmin = false)
}

/** The server id, when the message has one. Reply, delete and read-by all need it. */
private fun ChatMessage.serverIdOrNull(): String? = id.takeIf { sendState != SendState.Sending }

/** v2's 30-minute edit window, the same one the unit chat uses. */
private const val EDIT_WINDOW_MS = 30 * 60 * 1000L

/**
 * What the second pane shows while nothing is selected.
 *
 * Worded for the tab it sits beside, because "select a conversation" next to a call log
 * reads as though the list were the wrong one.
 */
@Composable
private fun CncDetailPlaceholder(isCallTab: Boolean) {
    if (isCallTab) {
        EmptyState(
            icon = Icons.Outlined.Call,
            title = stringResource(R.string.cnc_call_pane_empty_title),
            description = stringResource(R.string.cnc_call_pane_empty_description),
        )
    } else {
        EmptyState(
            icon = Icons.Outlined.Forum,
            title = stringResource(R.string.cnc_chat_pane_empty_title),
            description = stringResource(R.string.cnc_chat_pane_empty_description),
        )
    }
}
