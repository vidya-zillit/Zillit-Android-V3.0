package com.zillit.zillitapp.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.chat.data.HomeUnitKind
import com.zillit.zillitapp.core.ui.components.FullScreenSurface
import androidx.compose.runtime.rememberCoroutineScope
import com.zillit.zillitapp.core.attachment.RecordingState
import com.zillit.zillitapp.core.attachment.editor.ImageEditorScreen
import com.zillit.zillitapp.core.attachment.rememberEditorRasterizer
import kotlinx.coroutines.launch
import com.zillit.zillitapp.core.ui.chat.AudioBubbleState
import com.zillit.zillitapp.core.labels.asServerText
import com.zillit.zillitapp.core.labels.resolve
import com.zillit.zillitapp.core.ui.chat.ChatHistoryScreen
import com.zillit.zillitapp.core.ui.chat.ChatLibraryScreen
import com.zillit.zillitapp.core.ui.chat.ChatMessageOptionsSheet
import com.zillit.zillitapp.core.ui.chat.openFileExternally
import com.zillit.zillitapp.core.ui.chat.PrintLauncher
import com.zillit.zillitapp.core.ui.chat.ForwardMode
import com.zillit.zillitapp.core.ui.chat.ForwardShareSheet
import com.zillit.zillitapp.core.ui.chat.ReadByUserSheet
import com.zillit.zillitapp.core.ui.chat.ShareLauncher
import com.zillit.zillitapp.core.ui.chat.ChatThread
import com.zillit.zillitapp.feature.calendar.ui.CalendarRoute
import com.zillit.zillitapp.feature.calendar.ui.lists.EventListKind
import com.zillit.zillitapp.core.ui.chat.MediaViewerScreen
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.chat.SaveToDeviceLauncher

/**
 * Home: the unit strip and the selected unit's thread.
 *
 * Everything rendered here comes from Realm through [HomeViewModel] — units, messages and
 * authors — so the screen shows content on its first frame and updates itself when a
 * socket event or a finished upload writes to the store.
 */
@Composable
fun HomeRoute(
    onOpenEventList: (EventListKind) -> Unit,
    onCreateEvent: (Long) -> Unit,
    onOpenCalendarSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    // Tells the ViewModel whether Home is actually in front of the user. Leaving the tab
    // removes this composable entirely, so onDispose covers the tab switch; the lifecycle
    // observer covers the app going to the background while Home is still the tab.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onScreenVisibilityChanged(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.onScreenVisibilityChanged(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.onScreenVisibilityChanged(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenVisibilityChanged(false)
        }
    }

    val units by viewModel.units.collectAsStateWithLifecycle()
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val selectedUnit by viewModel.selectedUnit.collectAsStateWithLifecycle()
    val attachmentOptions by viewModel.attachmentOptions.collectAsStateWithLifecycle()
    val callSheetPrompt by viewModel.callSheetPrompt.collectAsStateWithLifecycle()
    val longPressed by viewModel.longPressedMessage.collectAsStateWithLifecycle()
    val openedMedia by viewModel.openedMediaId.collectAsStateWithLifecycle()
    val recording by viewModel.recordingState.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val replyingTo by viewModel.replyingTo.collectAsStateWithLifecycle()
    val forwarding by viewModel.forwarding.collectAsStateWithLifecycle()
    val forwardProjects by viewModel.forwardProjects.collectAsStateWithLifecycle()
    val forwardProject by viewModel.forwardProject.collectAsStateWithLifecycle()
    val forwardTargets by viewModel.forwardTargets.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val readByRequest by viewModel.readByRequest.collectAsStateWithLifecycle()
    val longPressedReply by viewModel.longPressedReply.collectAsStateWithLifecycle()
    val replyDeleteConfirmation by viewModel.replyDeleteConfirmation.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val isLoadingThread by viewModel.isLoadingThread.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val libraryOpen by viewModel.libraryOpen.collectAsStateWithLifecycle()
    val imageReplySource by viewModel.imageReplySource.collectAsStateWithLifecycle()
    val retryPrompt by viewModel.retryPrompt.collectAsStateWithLifecycle()
    val publishPrompt by viewModel.publishPrompt.collectAsStateWithLifecycle()
    val historyRequest by viewModel.historyRequest.collectAsStateWithLifecycle()

    // Print needs a PrintManager, so it is performed here for the same reason as Share.
    PrintLauncher(requests = viewModel.printRequests)

    // Refusals ("no connection", "no download rights") surface as a snackbar rather than
    // a dialog: they interrupt nothing and the action can simply be retried.
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { res ->
            snackbarHost.showSnackbar(context.getString(res))
        }
    }

    val uriHandler = LocalUriHandler.current
    LaunchedEffect(viewModel) {
        viewModel.locationRequests.collect { uriHandler.openUri(it) }
    }

    val cache = viewModel.mediaCacheForShare
    LaunchedEffect(viewModel) {
        viewModel.openExternally.collect { file -> context.openFileExternally(file, cache) }
    }

    // Copy needs a ClipboardManager, which is a Context service — same reasoning as the
    // share chooser: the ViewModel decides what to copy, the screen performs it.
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(viewModel) {
        viewModel.clipboardRequests.collect { clipboard.setText(AnnotatedString(it)) }
    }
    val deleteConfirmation by viewModel.deleteConfirmation.collectAsStateWithLifecycle()

    // Turns a gathered share into the OS chooser. Lives here because starting an Activity
    // needs a Context the ViewModel must not hold.
    ShareLauncher(requests = viewModel.shareRequests, cache = viewModel.mediaCacheForShare)
    // Saving needs a permission on API 28 and none above it, and asking for one needs an
    // Activity — so the view model names the file and this performs the copy.
    SaveToDeviceLauncher(requests = viewModel.saveRequests)

    // The strip arrives asynchronously, so the first unit is selected once it does rather
    // than assumed at construction.
    LaunchedEffect(units) { viewModel.selectFirstIfNeeded() }

    // Calendar is a Home unit but not a chat one. It replaces the whole thread rather
    // than switching options off inside it, so there is no composer, emoji, attachment,
    // send or record control anywhere in that branch — a disabled composer would still
    // occupy the space and still read as "you could type here".
    if (selectedUnit?.kind == HomeUnitKind.CALENDAR) {
        CalendarRoute(
            units = units,
            selectedUnitId = selectedUnit?.id,
            onUnitSelected = viewModel::onUnitSelected,
            onOpenReceived = { onOpenEventList(EventListKind.RECEIVED) },
            onOpenCreated = { onOpenEventList(EventListKind.CREATED) },
            onOpenDeclined = { onOpenEventList(EventListKind.DECLINED) },
            onOpenJoinCall = { onOpenEventList(EventListKind.JOIN_CALL) },
            onOpenSettings = onOpenCalendarSettings,
            onCreateEvent = { date ->
                onCreateEvent(
                    date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                )
            },
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier) {
    ChatThread(
        feed = feed,
        units = units,
        selectedUnitId = selectedUnit?.id,
        onUnitSelected = viewModel::onUnitSelected,
        canPost = selectedUnit?.canPost,
        // Call Sheet is documents-only, as in v2.
        allowsText = selectedUnit?.kind != HomeUnitKind.CALL_SHEET,
        attachmentOptions = attachmentOptions,
        // Call Sheet vetoes the picker so its replace confirmation runs first.
        onAttachRequested = viewModel::onAttachRequested,
        onAttachmentPicked = { viewModel.onAttachmentPicked(it) },
        openPickerRequest = (callSheetPrompt as? CallSheetPrompt.OpenPicker)?.replacePrevious,
        onPickerRequestHandled = viewModel::dismissCallSheetPrompt,
        onSend = viewModel::sendText,
        onRetry = viewModel::retry,
        onLoadOlder = viewModel::loadOlder,
        onReply = viewModel::startReply,
        replyingTo = replyingTo,
        onCancelReply = viewModel::cancelReply,
        onLongPress = viewModel::onMessageLongPressed,
        onOpenMedia = viewModel::onMediaOpened,
        onOpenLocation = viewModel::openLocation,
        isRecording = recording is RecordingState.Recording,
        recordingElapsedMs = (recording as? RecordingState.Recording)?.elapsedMs ?: 0,
        recordingAmplitude = (recording as? RecordingState.Recording)?.amplitude ?: 0f,
        onRecordStart = viewModel::startRecording,
        onRecordStop = viewModel::stopRecordingAndSend,
        onRecordCancel = viewModel::cancelRecording,
        audio = AudioBubbleState(
            playingId = playback.id.takeIf { playback.isPlaying || playback.positionMs > 0 },
            progress = playback.durationMs.takeIf { it > 0 }
                ?.let { playback.positionMs.toFloat() / it } ?: 0f,
            onToggle = viewModel::toggleAudio,
            onSeek = viewModel::seekAudio,
        ),
        selection = selection,
        onToggleSelection = viewModel::toggleSelection,
        onSelectionConfirm = viewModel::confirmSelection,
        onSelectionCancel = viewModel::cancelSelection,
        onReplyOptions = viewModel::onReplyOptions,
        editingBody = editing?.body,
        onSaveEdit = viewModel::saveEdit,
        onCancelEdit = viewModel::cancelEdit,
        draft = draft,
        onDraftChanged = viewModel::onDraftChanged,
        isLoading = isLoadingThread,
        onRefresh = viewModel::refresh,
        search = search,
        onSearchOpen = viewModel::openSearch,
        onSearchQueryChange = viewModel::onSearchQueryChanged,
        onSearchNext = viewModel::searchNext,
        onSearchPrevious = viewModel::searchPrevious,
        onSearchClose = viewModel::closeSearch,
        // Call Sheet only. It is the unit whose replace flow pushes the previous sheet
        // into history; an ordinary chat's history is just its deleted messages, which
        // v2 does not surface either.
        onOpenHistory = viewModel::openHistory
            .takeIf { selectedUnit?.kind == HomeUnitKind.CALL_SHEET },
    )

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }

    // Publishing sends a Call Sheet document to everyone with Doc Distribution access —
    // a wider audience than the unit, so it is confirmed by name.
    publishPrompt?.let { doc ->
        ZillitConfirmDialog(
            title = stringResource(R.string.option_distribute_to_dd),
            message = stringResource(R.string.dd_publish_confirm, doc.fileName),
            confirmLabel = stringResource(R.string.option_publish),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = viewModel::confirmPublish,
            onDismiss = viewModel::dismissPublishPrompt,
        )
    }

    // Retrying one file of a failed batch is rarely what was meant — v2 asks, and so
    // does this.
    retryPrompt?.let { prompt ->
        ZillitConfirmDialog(
            message = stringResource(R.string.rety_text),
            confirmLabel = stringResource(R.string.upload_all_value, prompt.siblingIds.size),
            dismissLabel = stringResource(R.string.this_file),
            onConfirm = viewModel::retryWholeBatch,
            // Both buttons act — "this file" is the second choice, not a cancel — so
            // backing out has to retry neither.
            onDismiss = viewModel::retryThisFile,
            onCancel = viewModel::dismissRetryPrompt,
        )
    }

    // A reply gets its own sheet with the reduced set — see ChatMessageOptions.replyOptions.
    longPressedReply?.let { target ->
        ChatMessageOptionsSheet(
            message = target.reply,
            options = viewModel.optionsForReply(target),
            onOptionSelected = viewModel::onReplyOptionSelected,
            onDismiss = viewModel::dismissLongPress,
            isReply = true,
        )
    }

    if (replyDeleteConfirmation != null) {
        ZillitConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.are_you_sure_you_want_to_delete),
            isDestructive = true,
            onConfirm = viewModel::confirmReplyDelete,
            onDismiss = viewModel::dismissReplyDeleteConfirmation,
        )
    }

    // Deleting is not undoable, so it is confirmed even when the selection is the single
    // message the long press opened. v2 asks the same question with the same words.
    if (deleteConfirmation != null) {
        ZillitConfirmDialog(
            title = stringResource(R.string.delete),
            message = stringResource(R.string.are_you_sure_you_want_to_delete),
            isDestructive = true,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDeleteConfirmation,
        )
    }

    // Long-press menu. The options are derived from the message rather than decided here,
    // so every chat surface offers the same set under the same conditions.
    longPressed?.let { message ->
        ChatMessageOptionsSheet(
            message = message,
            options = viewModel.optionsFor(message),
            onOptionSelected = viewModel::onOptionSelected,
            onDismiss = viewModel::dismissLongPress,
        )
    }

    forwarding?.let {
        ForwardShareSheet(
            mode = ForwardMode.FORWARD,
            projects = forwardProjects,
            currentProjectId = viewModel.currentProjectId().orEmpty(),
            selectedProject = forwardProject,
            targets = forwardTargets,
            onProjectSelected = viewModel::onForwardProjectSelected,
            onConfirm = viewModel::forwardTo,
            onDismiss = viewModel::dismissForward,
        )
    }

    // Image Reply: the original, opened in the annotation editor. The marked-up copy is
    // posted as an ordinary photo, which is exactly what v2 does.
    imageReplySource?.let { source ->
        val rasterizer = rememberEditorRasterizer()
        val scope = rememberCoroutineScope()

        FullScreenSurface(onDismiss = viewModel::dismissImageReply) {
            ImageEditorScreen(
                media = source,
                onDone = { editorState ->
                    scope.launch {
                        viewModel.onImageReplyEdited(
                            rasterizer.render(source, editorState) ?: source,
                        )
                    }
                },
                onCancel = viewModel::dismissImageReply,
            )
        }
    }

    // The history of the open unit — replaced call sheets and deleted messages, in the
    // same chat UI.
    historyRequest?.let { request ->
        FullScreenSurface(onDismiss = viewModel::dismissHistory) {
            ChatHistoryScreen(request = request, onClose = viewModel::dismissHistory)
        }
    }

    if (libraryOpen) {
        FullScreenSurface(onDismiss = viewModel::dismissLibrary) {
            ChatLibraryScreen(
                title = selectedUnit?.name?.asServerText()?.resolve()
                    ?: stringResource(R.string.media_docs_links),
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

    readByRequest?.let { request ->
        FullScreenSurface(onDismiss = viewModel::dismissReadBy) {
            ReadByUserSheet(request = request, onClose = viewModel::dismissReadBy)
        }
    }

    openedMedia?.let { id ->
        FullScreenSurface(onDismiss = viewModel::dismissMedia) {
            MediaViewerScreen(
                media = viewModel.viewableMedia(),
                initialId = id,
                onClose = viewModel::dismissMedia,
                onShare = { viewModel.share(it) },
            )
        }
    }

    CallSheetDialogs(
        prompt = callSheetPrompt,
        onContinuation = viewModel::onCallSheetContinuation,
        onNew = viewModel::onCallSheetNew,
        onReplaceConfirmed = viewModel::onCallSheetReplaceConfirmed,
        onDismiss = viewModel::dismissCallSheetPrompt,
    )
}

/**
 * Call Sheet's two-step replace confirmation.
 *
 * Copy is v2's, verbatim: uploading into a unit that already holds a call sheet asks
 * whether this is a continuation or a new sheet, and choosing "New" then spells out that
 * everything currently posted moves to History. Two steps because the second is
 * destructive and the first is not — collapsing them would put a one-tap data move behind
 * a button most people press by habit.
 */
@Composable
private fun CallSheetDialogs(
    prompt: CallSheetPrompt?,
    onContinuation: () -> Unit,
    onNew: () -> Unit,
    onReplaceConfirmed: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (prompt) {
        // Both buttons are real choices, so backing out of the dialog picks neither.
        CallSheetPrompt.ContinuationOrNew -> ZillitConfirmDialog(
            message = stringResource(R.string.call_sheet_permission_first_msg),
            confirmLabel = stringResource(R.string.call_sheet_continue_new),
            dismissLabel = stringResource(R.string.call_sheet_continuation),
            onConfirm = onNew,
            onDismiss = onContinuation,
            onCancel = onDismiss,
        )

        CallSheetPrompt.ConfirmReplace -> ZillitConfirmDialog(
            message = stringResource(R.string.call_sheet_permission_second_msg),
            onConfirm = onReplaceConfirmed,
            onDismiss = onDismiss,
        )

        // OpenPicker is consumed by ChatThread, not shown as a dialog.
        else -> Unit
    }
}
