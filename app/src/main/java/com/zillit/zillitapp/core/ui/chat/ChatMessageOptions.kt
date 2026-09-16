package com.zillit.zillitapp.core.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import com.zillit.zillitapp.core.ui.chat.model.QUICK_REACTIONS

/**
 * Everything a long press can offer, across every chat surface.
 *
 * v2 exposes this as `showDropDownOptions` with **twenty-two boolean parameters**, each
 * caller passing a different combination and several of them commented out mid-argument.
 * The same set is modelled here as an enum plus a [ChatMessageOptions] rule object, so a
 * caller states the facts about the message and the rules decide the menu — rather than
 * every screen re-deriving "should Forward show?" and drifting apart.
 */
enum class ChatMessageOption(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    /** Destructive entries are tinted and placed last. */
    val isDestructive: Boolean = false,
) {
    Reply(R.string.option_reply, Icons.AutoMirrored.Outlined.Reply),
    ImageReply(R.string.option_image_reply, Icons.Outlined.PermMedia),
    Edit(R.string.option_edit, Icons.Outlined.Edit),
    Copy(R.string.option_copy, Icons.Outlined.ContentCopy),
    Translate(R.string.option_translate, Icons.Outlined.Translate),
    Save(R.string.option_save, Icons.Outlined.Download),
    Share(R.string.option_share, Icons.Outlined.Share),
    Forward(R.string.option_forward, Icons.AutoMirrored.Outlined.Send),
    Print(R.string.option_print, Icons.Outlined.Print),
    ReadByUser(R.string.option_read_by_user, Icons.Outlined.Visibility),
    DistributeToDD(R.string.option_distribute_to_dd, Icons.Outlined.UploadFile),
    Gallery(R.string.option_gallery, Icons.Outlined.PermMedia),
    Delete(R.string.option_delete, Icons.Outlined.DeleteOutline, isDestructive = true),
}

/**
 * The facts about a message; the menu is derived from them.
 *
 * Stating conditions once, here, is the whole point: v2 computes them inline at each call
 * site, which is why Forward is hidden on Call Sheet in one screen and not in another.
 */
data class ChatMessageOptions(
    val isOwnMessage: Boolean,
    /** Confirmed by the server. Nothing that needs an id is offered before this. */
    val isConfirmed: Boolean,
    val isTextMessage: Boolean,
    val isLocationMessage: Boolean,
    /** Image Reply is offered against an image and nothing else, as in v2. */
    val isImageMessage: Boolean,
    val hasAttachment: Boolean,
    val hasBody: Boolean,
    val isAdmin: Boolean,
    val canPost: Boolean,
    /** v2's per-unit `download_access`. Without it Save is not offered. */
    val canDownload: Boolean,
    /**
     * Whether the edit/delete window is still open.
     *
     * v2 gives everyone **30 minutes** (`Constants.DIFFERENCE_IN_HOURS`) from the moment a
     * message was posted, and lets an admin act at any time. Computed by the caller, which
     * is the only layer that knows the clock.
     */
    val isWithinEditWindow: Boolean,
    /** Call Sheet hides Forward and Share, and is the only unit offering Publish to DD. */
    val isCallSheet: Boolean,
    val isTranslationEnabled: Boolean,
    val isAlreadyTranslated: Boolean,
) {
    fun visibleOptions(): List<ChatMessageOption> = buildList {
        // Everything below needs a server id, so an unsent message offers nothing.
        if (!isConfirmed) return@buildList

        if (canPost) add(ChatMessageOption.Reply)
        // v2 offers Image Reply against an **image** only — replying with a photo to a
        // spreadsheet or a voice note is not a flow the backend models.
        if (canPost && isImageMessage) add(ChatMessageOption.ImageReply)

        // v2 allows editing text and media captions alike, but only where there is
        // something to edit — media posted with no caption has no body.
        if (hasBody && canEditOrDelete) add(ChatMessageOption.Edit)

        if (isTextMessage) add(ChatMessageOption.Copy)
        if (isTranslationEnabled && !isAlreadyTranslated && !isOwnMessage) {
            add(ChatMessageOption.Translate)
        }

        // Saving needs a file **and** the right to take it off the unit.
        if (hasAttachment && !isLocationMessage && canDownload) add(ChatMessageOption.Save)
        // Share is offered for every message type outside Call Sheet, text included —
        // v2 passes `showShare = unit != CALL_SHEET` with no type test, and sharing the
        // text of a message is a real thing people do.
        if (!isCallSheet) {
            add(ChatMessageOption.Share)
            add(ChatMessageOption.Forward)
        }

        add(ChatMessageOption.ReadByUser)
        add(ChatMessageOption.Gallery)

        // Call Sheet documents are the ones that get published onward.
        if (isCallSheet && hasAttachment && !isLocationMessage) {
            add(ChatMessageOption.DistributeToDD)
        }

        if (canEditOrDelete) add(ChatMessageOption.Delete)
    }

    /**
     * Who may still change a message.
     *
     * Two rules at once: it has to be yours, and it has to be recent — unless you are an
     * admin, who is bound by neither. v2 enforces this after the fact with a snackbar; the
     * option is simply not offered here, which is the same rule stated once instead of
     * repeated at five call sites.
     */
    private val canEditOrDelete: Boolean
        get() = isAdmin || (isOwnMessage && isWithinEditWindow)

    /**
     * The reduced menu a **reply** gets.
     *
     * v2 opens replies through the same popup but with almost everything switched off:
     * `showReply/Save/Forward/Share/Print/ImageReply/Archive = false`, leaving Copy, Edit,
     * Translate, Read By User and Delete. A reply is a comment on a message, not a message
     * of its own — it has no attachment story of its own to forward or save, and it cannot
     * be replied to.
     */
    fun replyOptions(): List<ChatMessageOption> = buildList {
        if (!isConfirmed) return@buildList

        // Editing needs something written; a media-only reply has no body.
        if (hasBody && canEditOrDelete) add(ChatMessageOption.Edit)
        if (isTextMessage) add(ChatMessageOption.Copy)
        if (isTranslationEnabled && !isAlreadyTranslated && !isOwnMessage) {
            add(ChatMessageOption.Translate)
        }
        // Saving a reply's attachment is not in v2's comment menu, but the file is real and
        // there is no reason it should be reachable on a root and not here.
        if (hasAttachment && !isLocationMessage && canDownload) add(ChatMessageOption.Save)

        add(ChatMessageOption.ReadByUser)

        if (canEditOrDelete) add(ChatMessageOption.Delete)
    }
}

/**
 * The long-press menu.
 *
 * A bottom sheet rather than v2's anchored `PopupWindow`: a popup near the bottom of the
 * screen gets clipped or flipped depending on where the message sits, and on a tablet it
 * lands far from the thumb. A sheet is always reachable and always fully visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMessageOptionsSheet(
    message: ChatMessage,
    options: ChatMessageOptions,
    onOptionSelected: (ChatMessageOption) -> Unit,
    onDismiss: () -> Unit,
    /** A reply gets the reduced set — see [ChatMessageOptions.replyOptions]. */
    isReply: Boolean = false,
    /**
     * Emoji reactions, offered as a row above the actions. Null hides the row entirely.
     *
     * Above rather than in the list because a reaction is a different kind of act from
     * Delete or Forward — one tap that changes nothing about the message — and v2 puts it
     * in the same place for the same reason.
     */
    onReact: ((String) -> Unit)? = null,
) {
    val entries = if (isReply) options.replyOptions() else options.visibleOptions()
    if (entries.isEmpty() && onReact == null) {
        onDismiss()
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.surface,
    ) {
        // Only once the server has the message: a reaction addresses its server id, and
        // offering it on a row still uploading would fail silently.
        if (onReact != null && options.isConfirmed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ZillitTheme.spacing.lg,
                        vertical = ZillitTheme.spacing.md,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QUICK_REACTIONS.forEach { emoji ->
                    val mine = message.reactions.any { it.isMine && it.emoji == emoji }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (mine) {
                                    ZillitTheme.colors.accentSoft
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable {
                                onReact(emoji)
                                onDismiss()
                            },
                    ) {
                        Text(text = emoji, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(entries, key = { it.name }) { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onOptionSelected(option)
                            onDismiss()
                        }
                        .padding(
                            horizontal = ZillitTheme.spacing.lg,
                            vertical = ZillitTheme.spacing.md,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                ) {
                    val tint = if (option.isDestructive) {
                        ZillitTheme.colors.danger
                    } else {
                        ZillitTheme.colors.textSecondary
                    }

                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(option.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (option.isDestructive) {
                            ZillitTheme.colors.danger
                        } else {
                            ZillitTheme.colors.textPrimary
                        },
                    )
                }
            }
        }
    }
}
