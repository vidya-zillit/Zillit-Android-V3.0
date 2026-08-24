package com.zillit.zillitapp.core.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * What a multi-select run is for.
 *
 * Only two options take a selection. v2 arrives at the same split from the other side:
 * Delete and Share call `enableMultiSelection`, Forward's multi-select block is commented
 * out and it posts the one message it was opened on — *"we can delete multiple item at a
 * time and we can share multiple items at a time but forward we send one at a time"*.
 */
enum class ChatSelectionMode {
    DELETE,
    SHARE,
}

/**
 * An in-progress selection.
 *
 * The message the long press opened is already in [selectedIds], so the common case —
 * long press, confirm — is two taps and the bar never starts empty.
 */
data class ChatSelectionState(
    val mode: ChatSelectionMode,
    val selectedIds: Set<String>,
) {
    operator fun contains(messageId: String): Boolean = messageId in selectedIds

    val count: Int get() = selectedIds.size

    fun toggle(messageId: String): ChatSelectionState = copy(
        selectedIds = if (messageId in selectedIds) {
            selectedIds - messageId
        } else {
            selectedIds + messageId
        },
    )
}

/**
 * The action bar shown in the composer's place while a selection is active.
 *
 * It replaces the composer rather than floating over the thread: while selecting there is
 * nothing to type, and v2 likewise hides its keyboard fragment for the duration. Keeping
 * the count and the action in one strip also means the answer to "what happens if I press
 * this?" is always on screen.
 */
@Composable
fun ChatSelectionBar(
    selection: ChatSelectionState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDelete = selection.mode == ChatSelectionMode.DELETE

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .navigationBarsPadding()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = stringResource(R.string.cancel),
            tint = ZillitTheme.colors.textSecondary,
            modifier = Modifier.size(22.dp).clickable(onClick = onCancel),
        )

        Text(
            // "3 Selected" — v2's wording, and the count is what tells you whether the
            // taps you made actually landed.
            text = "${selection.count} ${stringResource(R.string.selected)}",
            style = MaterialTheme.typography.bodyLarge,
            color = ZillitTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )

        Row(
            modifier = Modifier.clickable(onClick = onConfirm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            val tint = if (isDelete) ZillitTheme.colors.danger else ZillitTheme.colors.brand
            Icon(
                imageVector = if (isDelete) {
                    Icons.Outlined.DeleteOutline
                } else {
                    Icons.Outlined.Share
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(if (isDelete) R.string.delete else R.string.share),
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
            )
        }
    }
}

/** Divider above the bar, so it reads as a bar and not as part of the last message. */
@Composable
internal fun ChatSelectionBarDivider() {
    HorizontalDivider(color = ZillitTheme.colors.divider)
}
