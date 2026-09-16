package com.zillit.zillitapp.feature.cnc.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.attachment.AttachmentResult
import com.zillit.zillitapp.core.ui.chat.AudioBubbleState
import com.zillit.zillitapp.core.ui.chat.ChatSearchState
import com.zillit.zillitapp.core.ui.chat.ChatSelectionState
import com.zillit.zillitapp.core.ui.chat.ChatThread
import com.zillit.zillitapp.core.ui.chat.model.ChatFeedItem
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.labels.asLabelIfKey
import com.zillit.zillitapp.core.ui.chat.model.ChatMention

/**
 * One C&C conversation — direct or group.
 *
 * The body is [ChatThread], the app's one chat surface, run in its two-sided mode: your
 * messages on the right in blue, theirs on the left under the avatar and name. Everything
 * a unit chat has — replies inside the card, the day chip, multi-select, in-thread search,
 * voice notes, the attachment sheet — arrives with it rather than being rebuilt here.
 *
 * What this screen adds is the part a socket chat has and a unit chat does not: who you
 * are talking to, whether they are typing, how many have read it, and the banners that
 * explain a composer you cannot use.
 */
@Composable
fun CncThreadScreen(
    state: CncThreadUiState,
    onBack: () -> Unit,
    onOpenDetails: () -> Unit,
    onAttachments: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onSend: (String, List<ChatMention>) -> Unit,
    onAttachmentPicked: (AttachmentResult) -> Unit,
    onDraftChanged: (String) -> Unit,
    onReply: (String) -> Unit,
    onCancelReply: () -> Unit,
    onLongPress: (ChatMessage) -> Unit,
    onOpenMedia: (ChatMessage) -> Unit,
    onReact: (ChatMessage, String) -> Unit,
    onOpenQuoted: (String) -> Unit,
    jumpToId: String?,
    onJumpHandled: () -> Unit,
    onCancelUpload: (ChatMessage) -> Unit,
    onOpenLocation: (ChatMessage.Location) -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onLoadOlder: () -> Unit,
    onReachedBottom: () -> Unit,
    onUnblock: () -> Unit,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false,
    recordingElapsedMs: Long = 0,
    recordingAmplitude: Float = 0f,
    onRecordStart: () -> Unit = {},
    onRecordStop: () -> Unit = {},
    onRecordCancel: () -> Unit = {},
    audio: AudioBubbleState = AudioBubbleState(),
    selection: ChatSelectionState? = null,
    onToggleSelection: (ChatMessage) -> Unit = {},
    onSelectionConfirm: () -> Unit = {},
    onSelectionCancel: () -> Unit = {},
    search: ChatSearchState? = null,
    onSearchOpen: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onSearchNext: () -> Unit = {},
    onSearchPrevious: () -> Unit = {},
    onSearchClose: () -> Unit = {},
    onSaveEdit: (String) -> Unit = {},
    onCancelEdit: () -> Unit = {},
) {
    val typingLabel = typingLabel(state.typing)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background),
    ) {
        // No separate top bar: the header below *is* the bar, back arrow included. A strip
        // holding nothing but an arrow above a strip holding the person is two bars doing
        // one job, and it reads as a page loaded inside another page.
        CncThreadHeader(
            onBack = onBack,
            title = state.title,
            subtitle = state.subtitle.asLabelIfKey(),
            initials = state.initials,
            pictureKey = state.pictureKey,
            thumbnailKey = state.thumbnailKey,
            online = state.online,
            onOpenDetails = onOpenDetails,
            onAttachments = onAttachments,
            onAudioCall = onAudioCall,
            onVideoCall = onVideoCall,
        )

        state.block?.let { block ->
            ThreadBanner(
                icon = Icons.Outlined.Block,
                text = when (block) {
                    ThreadBlock.BLOCKED_BY_ME ->
                        stringResource(R.string.cnc_blocked_by_me, state.title)
                    ThreadBlock.BLOCKED_ME ->
                        stringResource(R.string.cnc_blocked_me, state.title)
                    ThreadBlock.REMOVED_FROM_GROUP ->
                        stringResource(R.string.cnc_removed_from_group)
                },
                tint = ZillitTheme.colors.danger,
                background = ZillitTheme.colors.dangerSoft,
                // Only your own block is yours to lift; the other two have nothing to tap.
                onClick = if (block == ThreadBlock.BLOCKED_BY_ME) onUnblock else null,
                action = if (block == ThreadBlock.BLOCKED_BY_ME) {
                    stringResource(R.string.cnc_unblock_action)
                } else {
                    null
                },
            )
        }

        ChatThread(
            feed = state.feed,
            modifier = Modifier.fillMaxSize(),
            twoSided = true,
            // A C&C message has named recipients, so "delivered" and "read" mean something
            // here in a way they never do on a unit broadcast.
            showDeliveryReceipts = true,
            readByLabel = { post ->
                state.readCounts[post.root.id]
                    ?.takeIf { state.isGroup && post.root.isOwn && it > 0 }
                    ?.let { count -> stringResource(R.string.cnc_read_by, count) }
            },
            typingLabel = typingLabel,
            canPost = state.canPost,
            // A blocked thread is not a rights problem, so it must not be explained as
            // one: the banner above has already said what is going on.
            isReadOnly = state.block != null,
            isLoading = state.isLoading,
            draft = state.draft,
            onDraftChanged = onDraftChanged,
            onSend = onSend,
            // Only a group: naming the one other person in a direct thread says nothing.
            mentionCandidates = if (state.isGroup) state.mentionCandidates else emptyList(),
            onAttachmentPicked = onAttachmentPicked,
            onReply = onReply,
            replyingTo = state.replyingTo,
            onCancelReply = onCancelReply,
            editingBody = (state.editing as? ChatMessage.Text)?.body,
            onSaveEdit = onSaveEdit,
            onCancelEdit = onCancelEdit,
            onLongPress = onLongPress,
            onOpenMedia = onOpenMedia,
            onReact = onReact,
            onOpenQuoted = onOpenQuoted,
            jumpToId = jumpToId,
            onJumpHandled = onJumpHandled,
            onCancelUpload = onCancelUpload,
            onOpenLocation = onOpenLocation,
            onRetry = onRetry,
            onLoadOlder = onLoadOlder,
            onReachedBottom = onReachedBottom,
            isRecording = isRecording,
            recordingElapsedMs = recordingElapsedMs,
            recordingAmplitude = recordingAmplitude,
            onRecordStart = onRecordStart,
            onRecordStop = onRecordStop,
            onRecordCancel = onRecordCancel,
            audio = audio,
            selection = selection,
            onToggleSelection = onToggleSelection,
            onSelectionConfirm = onSelectionConfirm,
            onSelectionCancel = onSelectionCancel,
            search = search,
            onSearchOpen = onSearchOpen,
            onSearchQueryChange = onSearchQueryChange,
            onSearchNext = onSearchNext,
            onSearchPrevious = onSearchPrevious,
            onSearchClose = onSearchClose,
        )
    }
}

/**
 * "Sahil is typing…", or a count once more than one person is.
 *
 * Naming everybody would grow the line past the width it has and change length on every
 * keystroke, which is worse than not naming them.
 */
@Composable
private fun typingLabel(typing: List<String>): String? = when (typing.size) {
    0 -> null
    1 -> stringResource(R.string.cnc_typing, typing.first())
    else -> pluralStringResource(R.plurals.cnc_typing_several, typing.size, typing.size)
}

/**
 * A one-line notice between the header and the feed — currently only ever a block.
 *
 * Tinted rather than grey so the reason reads before the words do, and shaped to take more
 * than one kind of notice because a thread has other things it may need to explain.
 */
@Composable
private fun ThreadBanner(
    icon: ImageVector,
    text: String,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    action: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                horizontal = ZillitTheme.spacing.lg,
                vertical = ZillitTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        action?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = tint,
            )
        }
    }
}
