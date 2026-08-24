package com.zillit.zillitapp.core.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.KeyboardAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.attachment.AttachmentOption
import com.zillit.zillitapp.core.attachment.AttachmentPickerSheet
import com.zillit.zillitapp.core.attachment.AttachmentResult
import com.zillit.zillitapp.core.labels.asServerText
import com.zillit.zillitapp.core.labels.resolve
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.chat.model.ChatFeedItem
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.core.ui.chat.model.ChatUnit
import kotlinx.coroutines.launch

/**
 * The shared chat surface — feed, composer, and an optional unit strip.
 *
 * **This is the only chat UI in the app.** v2 grew a separate screen per feature (Home,
 * Confidential Info, Info, File Cabinet, Form Discussion, Catering, Accounts, Production
 * Report), each with its own copy of the adapters and their own drift; a fix to reply
 * rendering had to be made eight times. Every caller here passes data and callbacks into
 * this one composable instead.
 *
 * Everything feature-specific is a parameter:
 *
 *  - [units] empty for a chat with no unit concept (a tool discussion). The strip is then
 *    not composed at all rather than rendered empty.
 *  - [canPost] the caller's posting-rights decision. Defaults to the selected unit's own
 *    `canPost`, which is what a unit-based chat wants.
 *  - [onSend]/[onAttach]/[onRecord]/[onRetry] — the surface owns no business logic.
 *
 * @param feed already ordered oldest-first. Deleted messages are expected to be filtered
 *   out by the caller; there is no placeholder row for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThread(
    feed: List<ChatFeedItem>,
    modifier: Modifier = Modifier,
    units: List<ChatUnit> = emptyList(),
    selectedUnitId: String? = null,
    onUnitSelected: (ChatUnit) -> Unit = {},
    canPost: Boolean? = null,
    /**
     * Which attachment options this thread allows. Call Sheet passes
     * [AttachmentOption.CALL_SHEET] (documents only); an ordinary unit passes the default.
     */
    attachmentOptions: Set<AttachmentOption> = AttachmentOption.CHAT_DEFAULT,
    /**
     * Whether the thread accepts typed messages.
     *
     * False for Call Sheet, which is documents-only: v2 hides the send/record button there
     * entirely, leaving the paperclip as the only way to post. Showing a composer that
     * cannot send is worse than not showing one.
     */
    allowsText: Boolean = true,
    onSend: (String) -> Unit = {},
    /**
     * Called before the picker opens. Return false to suppress it — Call Sheet uses this
     * to run its replace-confirmation first and open the picker from there instead.
     */
    onAttachRequested: () -> Boolean = { true },
    onAttachmentPicked: (AttachmentResult) -> Unit = {},
    /**
     * Non-null asks the thread to open the picker itself — Call Sheet uses it to resume
     * after its replace confirmation, since the tap that would normally open the picker
     * was consumed by the dialog.
     */
    openPickerRequest: Boolean? = null,
    onPickerRequestHandled: () -> Unit = {},
    isRecording: Boolean = false,
    recordingElapsedMs: Long = 0,
    recordingAmplitude: Float = 0f,
    onRecordStart: () -> Unit = {},
    onRecordStop: () -> Unit = {},
    onRecordCancel: () -> Unit = {},
    onRetry: (ChatMessage) -> Unit = {},
    /** Fired when the thread is scrolled near the top, to page older messages in. */
    onLoadOlder: () -> Unit = {},
    /** Parent server id — starts a reply in the composer. */
    onReply: (String) -> Unit = {},
    onLongPress: (ChatMessage) -> Unit = {},
    onOpenMedia: (ChatMessage) -> Unit = {},
    onOpenLocation: (ChatMessage.Location) -> Unit = {},
    audio: AudioBubbleState = AudioBubbleState(),
    /** The message being replied to, shown as a quoted bar above the composer. */
    replyingTo: ChatMessage? = null,
    onCancelReply: () -> Unit = {},
    /**
     * Non-null puts the thread in multi-select: rows show a tick, a tap toggles instead of
     * opening, and the composer is replaced by the action bar.
     */
    selection: ChatSelectionState? = null,
    onToggleSelection: (ChatMessage) -> Unit = {},
    onSelectionConfirm: () -> Unit = {},
    onSelectionCancel: () -> Unit = {},
    /** Opens the reduced options menu for a reply. */
    onReplyOptions: ((parentServerId: String?, reply: ChatMessage) -> Unit)? = null,
    /** Non-null puts the composer in edit mode, preloaded with this text. */
    editingBody: String? = null,
    onSaveEdit: (String) -> Unit = {},
    onCancelEdit: () -> Unit = {},
    /** The saved draft for this thread; null while it is still being read. */
    draft: String? = null,
    onDraftChanged: (String) -> Unit = {},
    /** True while the first page is still loading, so an empty thread is not called empty. */
    isLoading: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    /**
     * Read-only: no composer **and** no "no posting rights" notice.
     *
     * `canPost = false` alone is the wrong signal for history — it explains an absence as a
     * permissions problem, when in fact nobody can post to a replaced message.
     */
    isReadOnly: Boolean = false,
    /** Non-null replaces the unit strip with the search bar. */
    search: ChatSearchState? = null,
    onSearchOpen: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onSearchNext: () -> Unit = {},
    onSearchPrevious: () -> Unit = {},
    onSearchClose: () -> Unit = {},
    /** Null hides the history affordance — history has none of its own. */
    onOpenHistory: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()

    // Land on the newest post when the thread changes, the way a chat is expected to
    // open. Scrolling up then stays put because the key does not change again.
    LaunchedEffect(selectedUnitId, feed.size) {
        if (feed.isNotEmpty()) listState.scrollToItem(feed.lastIndex)
    }

    val postingAllowed = canPost
        ?: units.firstOrNull { it.id == selectedUnitId }?.canPost
        ?: true

    // Walking the matches scrolls the thread to each one; without this the count moves and
    // nothing else does.
    LaunchedEffect(search?.currentId) {
        val id = search?.currentId ?: return@LaunchedEffect
        val index = feed.indexOfFirst { it is ChatFeedItem.Post && it.id == id }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.background)) {
        if (search != null) {
            ChatSearchBar(
                state = search,
                onQueryChange = onSearchQueryChange,
                onNext = onSearchNext,
                onPrevious = onSearchPrevious,
                onClose = onSearchClose,
            )
        } else if (units.isNotEmpty()) {
            ChatUnitStrip(
                units = units,
                selectedUnitId = selectedUnitId,
                onUnitSelected = onUnitSelected,
            )
        }

        // Paging back through history: when the first item becomes visible there is
        // nothing above it on screen, which is the moment to fetch the previous page.
        LaunchedEffect(listState, feed.size) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .collect { index -> if (index <= PAGE_TRIGGER_INDEX && feed.isNotEmpty()) onLoadOlder() }
        }

        val pullState = rememberPullToRefreshState()

        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { onRefresh?.invoke() },
            state = pullState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            // A thread with nothing in it is a real state (a unit created this morning),
            // not an error — but only once the first fetch has come back, or every open
            // would flash "nothing here" before the messages arrive.
            if (feed.isEmpty() && !isLoading) {
                EmptyThread(modifier = Modifier.align(Alignment.Center))
            }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            items(
                items = feed,
                key = { item ->
                    when (item) {
                        is ChatFeedItem.Day -> item.id
                        is ChatFeedItem.Post -> item.id
                        ChatFeedItem.UnreadDivider -> ChatFeedItem.UnreadDivider.ID
                    }
                },
            ) { item ->
                when (item) {
                    is ChatFeedItem.Day -> DateSeparatorRow(item.day.label())
                    ChatFeedItem.UnreadDivider -> UnreadDividerRow()
                    is ChatFeedItem.Post -> ChatPostRow(
                        post = item,
                        onRetry = onRetry,
                        onReply = onReply,
                        onLongPress = onLongPress,
                        onOpenMedia = onOpenMedia,
                        onOpenLocation = onOpenLocation,
                        audio = audio,
                        selection = selection,
                        onToggleSelection = onToggleSelection,
                        isReadOnly = isReadOnly,
                        searchQuery = search?.query.orEmpty(),
                        isSearchHit = search?.currentId == item.id,
                        onReplyOptions = onReplyOptions?.let { open ->
                            { reply -> open(item.rootServerId, reply) }
                        },
                    )
                }
            }
        }

            // The day the top of the list currently sits in, pinned above it — v2 shows the
            // same chip and updates it as you scroll.
            //
            // Derived from the first visible index rather than tracked in a variable, and
            // keyed on `feed` so it cannot capture an earlier, shorter list: an unkeyed
            // `remember` here freezes on the feed's first (empty) value and the chip never
            // appears, which is the same trap the scroll-to-bottom button hit.
            val stickyDay by remember(feed) {
                derivedStateOf {
                    if (feed.isEmpty()) {
                        null
                    } else {
                        val top = listState.firstVisibleItemIndex.coerceIn(0, feed.lastIndex)
                        // Walk back to the nearest separator at or above the top row: the
                        // messages between it and here all belong to that day.
                        feed.subList(0, top + 1).lastOrNull { it is ChatFeedItem.Day }
                            as? ChatFeedItem.Day
                    }
                }
            }

            stickyDay?.let { day ->
                DateSeparatorRow(
                    label = day.day.label(),
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            // v2's `arrowDownCard`: shown once you have scrolled up, so a long thread has
            // a way back without dragging. Also what stops an incoming message yanking the
            // list down while you are reading history.
            // `canScrollForward` rather than comparing indices against `feed`: a plain
            // `remember {}` captures `feed` from the first composition — when it is still
            // empty — so the comparison was frozen false and the button never appeared.
            // This is a State read, so it tracks scrolling without capturing anything.
            val isAwayFromBottom = listState.canScrollForward
            val scope = rememberCoroutineScope()

            // Both float over the feed at the bottom-end, History above the jump button —
            // v2 pins its History FAB to the same corner. Out of the unit strip, which is
            // for units, and within thumb reach of where the thread already is.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(ZillitTheme.spacing.md),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                onOpenHistory?.let { open ->
                    Text(
                        text = stringResource(R.string.history),
                        style = MaterialTheme.typography.labelLarge,
                        color = ZillitTheme.colors.textOnBrand,
                        modifier = Modifier
                            .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
                            .background(ZillitTheme.colors.brand)
                            .clickable(onClick = open)
                            .padding(
                                horizontal = ZillitTheme.spacing.md,
                                vertical = ZillitTheme.spacing.sm,
                            ),
                    )
                }

                androidx.compose.animation.AnimatedVisibility(visible = isAwayFromBottom) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(ZillitTheme.colors.surface, CircleShape)
                            .clickable {
                                scope.launch { listState.scrollToItem(feed.lastIndex) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowDownward,
                            contentDescription = stringResource(R.string.chat_scroll_to_latest),
                            tint = ZillitTheme.colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        // Read-only threads replace the composer with a notice rather than showing a box
        // that rejects the send.
        if (isReadOnly) {
            // Nothing at the bottom at all — the thread simply ends.
        } else if (selection != null) {
            ChatSelectionBarDivider()
            ChatSelectionBar(
                selection = selection,
                onConfirm = onSelectionConfirm,
                onCancel = onSelectionCancel,
            )
        } else if (postingAllowed) {
            ChatComposer(
                onSend = onSend,
                onSearchOpen = onSearchOpen,
                savedDraft = draft,
                onDraftChanged = onDraftChanged,
                editingBody = editingBody,
                onSaveEdit = onSaveEdit,
                onCancelEdit = onCancelEdit,
                replyingTo = replyingTo,
                onCancelReply = onCancelReply,
                allowsText = allowsText,
                attachmentOptions = attachmentOptions,
                onAttachRequested = onAttachRequested,
                onAttachmentPicked = onAttachmentPicked,
                openPickerRequest = openPickerRequest,
                onPickerRequestHandled = onPickerRequestHandled,
                isRecording = isRecording,
                recordingElapsedMs = recordingElapsedMs,
                recordingAmplitude = recordingAmplitude,
                onRecordStart = onRecordStart,
                onRecordStop = onRecordStop,
                onRecordCancel = onRecordCancel,
            )
        } else {
            NoPostingRights()
        }
    }
}

@Composable
private fun ChatComposer(
    onSend: (String) -> Unit,
    onSearchOpen: () -> Unit,
    savedDraft: String?,
    onDraftChanged: (String) -> Unit,
    editingBody: String?,
    onSaveEdit: (String) -> Unit,
    onCancelEdit: () -> Unit,
    replyingTo: ChatMessage?,
    onCancelReply: () -> Unit,
    allowsText: Boolean,
    attachmentOptions: Set<AttachmentOption>,
    onAttachRequested: () -> Boolean,
    onAttachmentPicked: (AttachmentResult) -> Unit,
    openPickerRequest: Boolean?,
    onPickerRequestHandled: () -> Unit,
    isRecording: Boolean,
    recordingElapsedMs: Long,
    recordingAmplitude: Float,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onRecordCancel: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var overLimit by remember { mutableStateOf(false) }
    var emojiOpen by rememberSaveable { mutableStateOf(false) }

    // Restores what was typed in this thread. Only while not editing: an edit's text is
    // the message being changed, and a stale draft arriving on top of it would silently
    // rewrite someone else's words.
    LaunchedEffect(savedDraft) {
        if (savedDraft != null && editingBody == null) draft = savedDraft
    }
    var pickerOpen by remember { mutableStateOf(false) }

    // Entering edit mode replaces whatever was typed with the existing text — v2 does the
    // same, and an edit that starts from a half-written unrelated draft is never wanted.
    // The draft is not restored on cancel because the row is keyed to the message, not
    // the composer; losing two words beats silently sending the wrong ones.
    LaunchedEffect(editingBody) {
        if (editingBody != null) draft = editingBody
    }
    val isEditing = editingBody != null

    // Opened on the host's behalf once a Call Sheet confirmation resolves.
    LaunchedEffect(openPickerRequest) {
        if (openPickerRequest != null) {
            pickerOpen = true
            onPickerRequestHandled()
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxWidth().background(ZillitTheme.colors.surface)) {
        HorizontalDivider(color = ZillitTheme.colors.divider)

        // Replying uses the main composer rather than a box inside the bubble: a reply
        // often wants an attachment or an emoji, and duplicating those controls per
        // message would be both cluttered and a second place for them to drift.
        if (overLimit) {
            Text(
                text = stringResource(R.string.word_limit_error, MESSAGE_CHAR_LIMIT),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ZillitTheme.colors.surfaceSunken)
                    .padding(
                        horizontal = ZillitTheme.spacing.md,
                        vertical = ZillitTheme.spacing.xs,
                    ),
            )
        }

        when {
            isEditing -> EditPreviewBar(
                onCancel = {
                    draft = ""
                    onCancelEdit()
                },
            )
            replyingTo != null -> ReplyPreviewBar(replyingTo, onCancelReply)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Only pad for the keyboard when the emoji panel is closed — the panel
                // takes the keyboard's place, and padding for both leaves a dead gap.
                .then(if (emojiOpen) Modifier else Modifier.imePadding())
                .padding(
                    horizontal = ZillitTheme.spacing.md,
                    vertical = ZillitTheme.spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            // Toggles to a keyboard glyph while the panel is open, so the same button
            // gets you back rather than leaving the panel with no obvious exit.
            if (allowsText) Icon(
                imageVector = if (emojiOpen) {
                    Icons.Outlined.KeyboardAlt
                } else {
                    Icons.Outlined.EmojiEmotions
                },
                contentDescription = stringResource(
                    if (emojiOpen) R.string.chat_keyboard else R.string.chat_emoji,
                ),
                tint = ZillitTheme.colors.textSecondary,
                modifier = Modifier
                    .size(22.dp)
                    .clickable {
                        emojiOpen = !emojiOpen
                        if (emojiOpen) keyboard?.hide() else keyboard?.show()
                    },
            )

            // v2 keeps search in the composer bar rather than a toolbar menu — it is used
            // constantly on a long unit, and a menu would make it two taps every time.
            if (!isEditing) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.chat_search_open),
                    tint = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.size(22.dp).clickable(onClick = onSearchOpen),
                )
            }

            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = stringResource(R.string.chat_attach),
                tint = ZillitTheme.colors.textSecondary,
                modifier = Modifier.size(22.dp).clickable {
                    // The host gets a veto so Call Sheet can confirm the replace first.
                    if (onAttachRequested()) pickerOpen = true
                },
            )

            if (isRecording) {
                RecordingIndicator(
                    elapsedMs = recordingElapsedMs,
                    amplitude = recordingAmplitude,
                    modifier = Modifier.weight(1f),
                )
            } else if (allowsText) Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
                    .background(ZillitTheme.colors.surfaceSunken)
                    .padding(
                        horizontal = ZillitTheme.spacing.md,
                        vertical = ZillitTheme.spacing.sm,
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { text ->
                    // v2 refuses the keystroke past the limit rather than truncating on
                    // send, so what is on screen is always what will be sent.
                    if (text.length <= MESSAGE_CHAR_LIMIT) {
                        draft = text
                        overLimit = false
                        if (editingBody == null) onDraftChanged(text)
                    } else {
                        overLimit = true
                    }
                },
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyMedium,
                    ).copy(color = ZillitTheme.colors.textPrimary),
                    cursorBrush = SolidColor(ZillitTheme.colors.brand),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_message_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ZillitTheme.colors.textTertiary,
                            )
                        }
                        inner()
                    },
                )
            }

            // Documents-only threads get a label where the composer would be, so the
            // paperclip is unambiguous rather than looking like a broken text box.
            if (!allowsText) {
                Text(
                    text = stringResource(R.string.chat_documents_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
            }

            // Mic swaps to Send once there is something to send, so the primary action
            // is always in the same place under the thumb.
            if (allowsText) {
                // While editing the mic is suppressed even on an empty field: there is
                // nothing to record into an existing message, and clearing the text to
                // cancel would otherwise start a recording.
                if (draft.isBlank() && !isEditing) {
                    // Press and hold to record, release to send — the gesture every
                    // messaging app has trained people on. A tap-to-start / tap-to-stop
                    // toggle leaves recordings running when the screen is left.
                    RecordButton(
                        isRecording = isRecording,
                        onStart = onRecordStart,
                        onStop = onRecordStop,
                        onCancel = onRecordCancel,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(ZillitTheme.colors.brand, CircleShape)
                            .clickable {
                                if (isEditing) onSaveEdit(draft) else onSend(draft)
                                draft = ""
                                overLimit = false
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = ZillitTheme.colors.textOnBrand,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = emojiOpen && allowsText) {
            EmojiPanel(onEmojiPicked = { draft += it })
        }
    }

    if (pickerOpen) {
        AttachmentPickerSheet(
            options = attachmentOptions,
            onResult = onAttachmentPicked,
            onDismiss = { pickerOpen = false },
        )
    }
}

/** The quoted message above the composer while a reply is being written. */
@Composable
private fun ReplyPreviewBar(message: ChatMessage, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(
                start = ZillitTheme.spacing.md,
                end = ZillitTheme.spacing.sm,
                top = ZillitTheme.spacing.sm,
                bottom = ZillitTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 34.dp)
                .background(ZillitTheme.colors.brand, RoundedCornerShape(2.dp)),
        )

        Column(modifier = Modifier.weight(1f)) {
            // The designation is a server label key, resolved at render — the same rule
            // as the message header. `displayName` would show the raw key.
            val role = message.author.role?.takeIf { it.isNotBlank() }?.asServerText()?.resolve()
            Text(
                text = if (role.isNullOrBlank()) {
                    message.author.name
                } else {
                    "${message.author.name} ($role)"
                },
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.brand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // A media message has no body worth quoting, so it is described instead.
                text = message.replyPreviewText(),
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.cancel),
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp).clickable(onClick = onCancel),
        )
    }
}

@Composable
private fun ChatMessage.replyPreviewText(): String = when (this) {
    is ChatMessage.Text -> body
    is ChatMessage.Image -> caption ?: stringResource(R.string.attachment_photo)
    is ChatMessage.Video -> stringResource(R.string.attachment_video)
    is ChatMessage.Voice -> stringResource(R.string.attachment_audio)
    is ChatMessage.Document -> fileName
    is ChatMessage.Location -> placeName
}

/**
 * Press-and-hold record button.
 *
 * Uses a raw press gesture rather than a click so the recording is bound to the finger
 * being down: lifting sends, and dragging off the button cancels — which is the escape
 * hatch people expect when they change their mind mid-sentence.
 */
@Composable
private fun RecordButton(
    isRecording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    Box(
        modifier = Modifier
            // Grows while recording, so it is obvious the mic is live.
            .size(if (isRecording) 52.dp else 40.dp)
            .background(
                if (isRecording) ZillitTheme.colors.danger else ZillitTheme.colors.brand,
                CircleShape,
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false)

                        // Asked on first use rather than up front: the mic is only needed
                        // by people who actually send voice notes, and a permission prompt
                        // at launch for a feature nobody has touched gets refused.
                        if (!hasPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            continue
                        }

                        onStart()

                        // Waiting for the release tells us whether this was a send or a
                        // cancel; a drag out of bounds is a cancel.
                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val position = change.position
                                val outOfBounds = position.x < -CANCEL_SLOP_PX ||
                                    position.y < -CANCEL_SLOP_PX ||
                                    position.x > size.width + CANCEL_SLOP_PX ||
                                    position.y > size.height + CANCEL_SLOP_PX
                                if (outOfBounds) {
                                    cancelled = true
                                    onCancel()
                                    break
                                }
                            } else {
                                break
                            }
                        }
                        if (!cancelled) onStop()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Mic,
            contentDescription = stringResource(R.string.chat_record),
            tint = ZillitTheme.colors.textOnBrand,
            modifier = Modifier.size(if (isRecording) 26.dp else 20.dp),
        )
    }
}

/** Live level and elapsed time, so a dead mic is obvious before a whole take is lost. */
@Composable
private fun RecordingIndicator(
    elapsedMs: Long,
    amplitude: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
            .background(ZillitTheme.colors.dangerSoft)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                // Scales with the mic level rather than blinking on a timer, so it
                // reflects that sound is actually being captured.
                .size((6 + amplitude * 8).dp)
                .background(ZillitTheme.colors.danger, CircleShape),
        )
        Text(
            text = "%d:%02d".format(elapsedMs / 60000, (elapsedMs / 1000) % 60),
            style = MaterialTheme.typography.labelMedium,
            color = ZillitTheme.colors.danger,
        )
        Text(
            text = stringResource(R.string.chat_recording_hint),
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.textTertiary,
        )
    }
}

/**
 * The system emoji picker, embedded as a panel below the composer.
 *
 * This is `androidx.emoji2.emojipicker`, the same widget v2 uses — it brings recents,
 * categories, skin-tone variants and the platform's own emoji font, none of which is
 * worth reimplementing in Compose for a keyboard accessory.
 */
@Composable
private fun EmojiPanel(onEmojiPicked: (String) -> Unit) {
    val background = ZillitTheme.colors.surface

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(EMOJI_PANEL_HEIGHT)
            .background(background),
        factory = { context ->
            // The view resolves AppCompat attributes for its category tabs; under the
            // app's framework theme that row renders blank. Wrapping the context is
            // cheaper than moving the whole app onto AppCompat for one widget.
            val themed = ContextThemeWrapper(context, R.style.Theme_Zillit_EmojiPicker)
            EmojiPickerView(themed).apply {
                emojiGridColumns = EMOJI_GRID_COLUMNS
                setBackgroundColor(background.toArgb())
                setOnEmojiPickedListener { onEmojiPicked(it.emoji) }
            }
        },
    )
}

@Composable
private fun NoPostingRights() {
    Column(modifier = Modifier.fillMaxWidth().background(ZillitTheme.colors.surface)) {
        HorizontalDivider(color = ZillitTheme.colors.divider)
        Text(
            text = stringResource(R.string.chat_no_posting_rights),
            style = MaterialTheme.typography.bodySmall,
            color = ZillitTheme.colors.textTertiary,
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
        )
    }
}

/** Roughly a soft-keyboard's height, so toggling between them doesn't jump the layout. */
private val EMOJI_PANEL_HEIGHT = 280.dp

private const val EMOJI_GRID_COLUMNS = 9

/** How far past the button a finger must travel before the take is abandoned. */
private const val CANCEL_SLOP_PX = 80f

/** Near enough to the top that the previous page should already be loading. */
private const val PAGE_TRIGGER_INDEX = 2

/**
 * The bar shown above the composer while editing.
 *
 * Deliberately different from the reply bar: an edit rewrites something already posted, and
 * the two modes look enough alike that without a distinct label it is easy to send a reply
 * believing you corrected a typo.
 */
@Composable
private fun EditPreviewBar(onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(
                start = ZillitTheme.spacing.md,
                end = ZillitTheme.spacing.sm,
                top = ZillitTheme.spacing.sm,
                bottom = ZillitTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 24.dp)
                .background(ZillitTheme.colors.brand, RoundedCornerShape(2.dp)),
        )
        Text(
            text = stringResource(R.string.chat_editing_message),
            style = MaterialTheme.typography.labelMedium,
            color = ZillitTheme.colors.brand,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.cancel),
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp).clickable(onClick = onCancel),
        )
    }
}

/** v2's `Constants.TEXT_LIMIT`. */
private const val MESSAGE_CHAR_LIMIT = 2000

/** Shown when a unit has no messages yet. Copy is v2's. */
@Composable
private fun EmptyThread(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(ZillitTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(R.string.chat_empty_title),
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/**
 * "Unread messages" — where you had got to when the thread was opened.
 *
 * Rendered in the accent colour rather than the day separator's grey: it answers a
 * different question ("where was I?" rather than "when was this?") and losing it among the
 * day headers is the whole failure mode it exists to prevent.
 */
@Composable
private fun UnreadDividerRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ZillitTheme.spacing.md,
                vertical = ZillitTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        HorizontalDivider(color = ZillitTheme.colors.brand, modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.chat_unread_divider),
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.brand,
        )
        HorizontalDivider(color = ZillitTheme.colors.brand, modifier = Modifier.weight(1f))
    }
}
