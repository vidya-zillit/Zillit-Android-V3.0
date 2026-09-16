package com.zillit.zillitapp.feature.email.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.AttachmentState
import com.zillit.zillitapp.core.ui.components.StagedAttachmentRow
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.html.RichTextEditor
import com.zillit.zillitapp.core.ui.html.RichTextEditorState
import com.zillit.zillitapp.core.ui.html.RichTextToolbar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailAddress
import com.zillit.zillitapp.feature.email.domain.RecipientSuggestion
import com.zillit.zillitapp.navigation.EmailCompose

/** Which recipient row the user is in. */
enum class RecipientRow { TO, CC, BCC }

/** A file staged for sending. */
data class StagedAttachment(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val state: AttachmentState = AttachmentState.READY,
)

data class ComposeUiState(
    val mode: String = EmailCompose.MODE_NEW,
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val toInput: String = "",
    val ccInput: String = "",
    val bccInput: String = "",
    /** Which row is expanded; null means all three are collapsed. */
    val focusedRow: RecipientRow? = RecipientRow.TO,
    val subject: String = "",
    val attachments: List<StagedAttachment> = emptyList(),
    val suggestions: List<RecipientSuggestion> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
) {
    /**
     * Send is offered whenever there is somebody to send to and nothing still uploading.
     *
     * v2 has the same guard, but `AttachmentStatus.UPLOADING` is never actually assigned
     * there, so the uploading half of it can never fire and a mail can be sent while its
     * attachment is still on its way to S3.
     */
    val canSend: Boolean
        get() = !sending &&
            to.isNotEmpty() &&
            attachments.none { it.state == AttachmentState.BUSY }

    val totalAttachmentBytes: Long get() = attachments.sumOf { it.sizeBytes }
}

/**
 * The composer — v2's `activity_compose`.
 *
 * Laid out as three fixed bands rather than v2's one long `ScrollView`: the recipient and
 * subject fields at the top, the editor filling everything left, and the attachments pinned
 * to the bottom. v2 puts the body's `WebView` *inside* the scroll view and has JavaScript
 * report its height back so the outer scroll can size it — which is why a long mail there
 * scrolls two things at once and the caret can end up under the keyboard. Here the editor
 * owns its own scrolling and nothing nests.
 */
@Composable
fun ComposeScreen(
    state: ComposeUiState,
    editorState: RichTextEditorState,
    onClose: () -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (StagedAttachment) -> Unit,
    onRetryAttachment: (StagedAttachment) -> Unit,
    onInsertImage: () -> Unit,
    onSubjectChange: (String) -> Unit,
    onRecipientInputChange: (RecipientRow, String) -> Unit,
    onCommitRecipient: (RecipientRow) -> Unit,
    onRemoveRecipient: (RecipientRow, EmailAddress) -> Unit,
    onFocusRow: (RecipientRow?) -> Unit,
    onPickSuggestion: (RecipientRow, RecipientSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.surface)
            .imePadding(),
    ) {
        ComposeTopBar(
            mode = state.mode,
            canSend = state.canSend,
            sending = state.sending,
            onClose = onClose,
            onAttach = onAttach,
            onSend = onSend,
        )

        HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)

        // The headers scroll on their own when three rows of chips outgrow their space,
        // without dragging the editor along with them.
        Column(
            modifier = Modifier
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            RecipientRows(
                state = state,
                onRecipientInputChange = onRecipientInputChange,
                onCommitRecipient = onCommitRecipient,
                onRemoveRecipient = onRemoveRecipient,
                onFocusRow = onFocusRow,
            )

            SubjectField(value = state.subject, onValueChange = onSubjectChange)
        }

        HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)

        // Suggestions replace the editor rather than floating over it: a dropdown anchored
        // to a chip row that is itself re-wrapping lands in a different place every time.
        if (state.suggestions.isNotEmpty() && state.focusedRow != null) {
            SuggestionList(
                suggestions = state.suggestions,
                onPick = { onPickSuggestion(state.focusedRow, it) },
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(modifier = Modifier.weight(1f)) {
                RichTextEditor(
                    state = editorState,
                    placeholder = stringResource(R.string.email_write_message),
                )
            }

            if (state.attachments.isNotEmpty()) {
                AttachmentStrip(
                    attachments = state.attachments,
                    onRemove = onRemoveAttachment,
                    onRetry = onRetryAttachment,
                )
            }

            RichTextToolbar(
                state = editorState,
                modifier = Modifier.navigationBarsPadding(),
                onInsertImage = onInsertImage,
            )
        }
    }
}

@Composable
private fun ComposeTopBar(
    mode: String,
    canSend: Boolean,
    sending: Boolean,
    onClose: () -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        // A close ✕, not a back arrow: this screen is a task the user finishes or abandons,
        // and the icon should say which.
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.action_close),
                tint = ZillitTheme.colors.textPrimary,
            )
        }

        Text(
            text = stringResource(composeTitleRes(mode)),
            style = MaterialTheme.typography.titleMedium,
            color = ZillitTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )

        IconButton(onClick = onAttach) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = stringResource(R.string.email_attach_file),
                tint = ZillitTheme.colors.textSecondary,
            )
        }

        if (sending) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = ZillitTheme.colors.brand,
                modifier = Modifier.size(24.dp),
            )
        } else {
            FilledIconButton(
                onClick = onSend,
                enabled = canSend,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ZillitTheme.colors.brand,
                    contentColor = ZillitTheme.colors.textOnBrand,
                ),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Send,
                    contentDescription = stringResource(R.string.email_send),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun RecipientRows(
    state: ComposeUiState,
    onRecipientInputChange: (RecipientRow, String) -> Unit,
    onCommitRecipient: (RecipientRow) -> Unit,
    onRemoveRecipient: (RecipientRow, EmailAddress) -> Unit,
    onFocusRow: (RecipientRow?) -> Unit,
) {
    @Composable
    fun row(kind: RecipientRow, label: String, recipients: List<EmailAddress>, input: String) {
        RecipientField(
            label = label,
            recipients = recipients,
            input = input,
            // A row with nothing in it stays open — there is no summary to collapse to,
            // and a collapsed empty row would be an unlabelled blank strip.
            expanded = state.focusedRow == kind || recipients.isEmpty(),
            onInputChange = { onRecipientInputChange(kind, it) },
            onCommit = { onCommitRecipient(kind) },
            onRemove = { onRemoveRecipient(kind, it) },
            onFocusChange = { focused -> onFocusRow(if (focused) kind else null) },
        )
        HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)
    }

    row(RecipientRow.TO, stringResource(R.string.email_field_to), state.to, state.toInput)
    row(RecipientRow.CC, stringResource(R.string.email_field_cc), state.cc, state.ccInput)
    row(RecipientRow.BCC, stringResource(R.string.email_field_bcc), state.bcc, state.bccInput)
}

@Composable
private fun SubjectField(value: String, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = ZillitTheme.colors.textPrimary,
        ),
        cursorBrush = SolidColor(ZillitTheme.colors.brand),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.email_subject_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = ZillitTheme.colors.textTertiary,
                    )
                }
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(ZillitTheme.spacing.lg),
    )
}

/**
 * Who you might mean.
 *
 * Project users, then contacts, then groups — and de-duplicated so that a colleague who is
 * also saved as a contact appears once. A group resolves to the group's own address, not to
 * its members, which is why it is labelled as a group rather than shown as a list of people.
 */
@Composable
private fun SuggestionList(
    suggestions: List<RecipientSuggestion>,
    onPick: (RecipientSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(suggestions, key = { it.address }) { suggestion ->
            SuggestionRow(suggestion = suggestion, onClick = { onPick(suggestion) })
            HorizontalDivider(
                color = ZillitTheme.colors.divider,
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = 64.dp),
            )
        }
    }
}

private fun composeTitleRes(mode: String): Int = when (mode) {
    EmailCompose.MODE_REPLY -> R.string.email_reply
    EmailCompose.MODE_REPLY_ALL -> R.string.email_reply_all
    EmailCompose.MODE_FORWARD -> R.string.email_forward
    EmailCompose.MODE_EDIT_DRAFT -> R.string.email_draft_title
    else -> R.string.email_new_mail
}

@Composable
private fun SuggestionRow(suggestion: RecipientSuggestion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        UserAvatar(
            initials = suggestion.initials,
            pictureKey = suggestion.pictureKey,
            thumbnailKey = suggestion.thumbnailKey,
            size = 36.dp,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.name.ifBlank { suggestion.address },
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = suggestion.address,
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // A group is one address that fans out server-side, so it is worth saying which
        // suggestions are people and which are not.
        if (suggestion.source == RecipientSuggestion.Source.GROUP) {
            Text(
                text = stringResource(R.string.email_groups),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.brand,
            )
        }
    }
}

/** Staged files, pinned above the toolbar so they stay visible while the body is typed. */
@Composable
private fun AttachmentStrip(
    attachments: List<StagedAttachment>,
    onRemove: (StagedAttachment) -> Unit,
    onRetry: (StagedAttachment) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        attachments.forEach { attachment ->
            StagedAttachmentRow(
                fileName = attachment.name,
                sizeBytes = attachment.sizeBytes.takeIf { it > 0 },
                state = attachment.state,
                onRemove = { onRemove(attachment) },
                onRetry = { onRetry(attachment) },
            )
        }
    }
}
