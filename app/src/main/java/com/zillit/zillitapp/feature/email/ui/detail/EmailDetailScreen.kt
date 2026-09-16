package com.zillit.zillitapp.feature.email.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.ui.resolve
import com.zillit.zillitapp.core.ui.toUiText
import com.zillit.zillitapp.core.ui.components.ErrorState
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.Email
import com.zillit.zillitapp.feature.email.domain.EmailAddress
import com.zillit.zillitapp.feature.email.domain.EmailAttachment
import com.zillit.zillitapp.feature.email.domain.EmailFolders

/** Which reply the bottom bar's main button performs. */
enum class ReplyAction { REPLY, REPLY_ALL, FORWARD }

data class EmailDetailUiState(
    /** Oldest first. The list renders newest first. */
    val emails: List<Email> = emptyList(),
    val folderName: String = EmailFolders.INBOX,
    val loading: Boolean = false,
    /**
     * A **load** failure, which replaces the screen.
     *
     * Kept as the typed error rather than its message: the backend sends a label key, and a
     * view model has no resources to turn one into a sentence. Action failures do not belong
     * here — those are transient, and blanking the message the user is reading to report a
     * failed download would be worse than the failure.
     */
    val error: ApiError? = null,
    val replyAction: ReplyAction = ReplyAction.REPLY,
    /** Whether Reply All is offered at all — see the note on the selector. */
    val canReplyAll: Boolean = false,
    val expandedIds: Set<String> = emptySet(),
    /** `cid:` → `data:` URI, filled in once the bodies are on screen. */
    val inlineImages: Map<String, String> = emptyMap(),
) {
    val subject: String get() = emails.firstOrNull()?.subject.orEmpty()

    val isConversation: Boolean get() = emails.size > 1

    val isTrash: Boolean get() = folderName == EmailFolders.TRASH
}

/**
 * One mail, or one conversation — v2's `activity_email_detail`.
 *
 * The header carries no mail actions at all; everything lives in a bottom bar, which is
 * v2's arrangement and the right one on a phone — reply is the most common action in the
 * app and it belongs under the thumb, not in the far corner.
 */
@Composable
fun EmailDetailScreen(
    state: EmailDetailUiState,
    onBack: () -> Unit,
    onReplyActionChange: (ReplyAction) -> Unit,
    onReply: (ReplyAction) -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onPrint: (all: Boolean) -> Unit,
    onToggleExpanded: (Email) -> Unit,
    onMessageMore: (Email) -> Unit,
    onSenderClick: (Email) -> Unit,
    onAttachmentClick: (Email, EmailAttachment) -> Unit,
    onAttachmentDownload: (Email, EmailAttachment) -> Unit,
    onLinkClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    resolveContentId: (String) -> String? = { null },
    resolveAvatar: (EmailAddress) -> Pair<String?, String?> = { null to null },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ZillitTheme.colors.surface,
        topBar = {
            ZillitTopBar(
                title = state.subject.ifBlank { stringResource(R.string.email_no_subject_row) },
                onBackClick = onBack,
                onHelpClick = null,
            )
        },
        bottomBar = {
            if (!state.loading && state.emails.isNotEmpty()) {
                DetailBottomBar(
                    state = state,
                    onReplyActionChange = onReplyActionChange,
                    onReply = onReply,
                    onDelete = onDelete,
                    onMove = onMove,
                    onPrint = onPrint,
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                state.loading -> LoadingState()

                state.error != null -> ErrorState(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.error_generic),
                    description = state.error.toUiText().resolve(),
                    onRetry = onRetry,
                    retryLabel = stringResource(R.string.action_retry),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Newest first: the message you came to read is the one you see.
                    items(state.emails.sortedByDescending { it.createdAt }, key = { it.id }) { email ->
                        EmailTrailItem(
                            email = email,
                            expanded = email.id in state.expandedIds,
                            onToggleExpanded = { onToggleExpanded(email) },
                            onMoreClick = { onMessageMore(email) },
                            onSenderClick = { onSenderClick(email) },
                            onAttachmentClick = { onAttachmentClick(email, it) },
                            onAttachmentDownload = { onAttachmentDownload(email, it) },
                            onLinkClick = onLinkClick,
                            resolveContentId = resolveContentId,
                            resolveAvatar = resolveAvatar,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reply selector, delete, overflow.
 *
 * The selector is one control doing two things: tapping the label performs the action shown,
 * tapping the chevron offers the others. That matters because the *right* default differs by
 * message — a mail addressed to six people wants Reply All and a mail from one person does
 * not — so the app picks and the user overrides, rather than the user choosing every time.
 */
@Composable
private fun DetailBottomBar(
    state: EmailDetailUiState,
    onReplyActionChange: (ReplyAction) -> Unit,
    onReply: (ReplyAction) -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onPrint: (all: Boolean) -> Unit,
) {
    var showReplyMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    Column {
        HorizontalDivider(color = ZillitTheme.colors.divider)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surface)
                .navigationBarsPadding()
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .clickable { onReply(state.replyAction) }
                        .padding(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    Icon(
                        imageVector = state.replyAction.icon,
                        contentDescription = null,
                        tint = ZillitTheme.colors.brand,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(state.replyAction.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textSecondary,
                    )
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = ZillitTheme.colors.textTertiary,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { showReplyMenu = true },
                    )
                }

                DropdownMenu(
                    expanded = showReplyMenu,
                    onDismissRequest = { showReplyMenu = false },
                ) {
                    ReplyMenuItem(ReplyAction.REPLY) {
                        showReplyMenu = false
                        onReplyActionChange(ReplyAction.REPLY)
                        onReply(ReplyAction.REPLY)
                    }
                    // Offered only when there is somebody else to reply to. Showing it
                    // always would make "Reply All" and "Reply" do the same thing on most
                    // messages, which teaches people to stop reading the difference.
                    if (state.canReplyAll) {
                        ReplyMenuItem(ReplyAction.REPLY_ALL) {
                            showReplyMenu = false
                            onReplyActionChange(ReplyAction.REPLY_ALL)
                            onReply(ReplyAction.REPLY_ALL)
                        }
                    }
                    ReplyMenuItem(ReplyAction.FORWARD) {
                        showReplyMenu = false
                        onReplyActionChange(ReplyAction.FORWARD)
                        onReply(ReplyAction.FORWARD)
                    }
                }
            }

            Box(modifier = Modifier.weight(1f))

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.delete),
                    tint = ZillitTheme.colors.textSecondary,
                )
            }

            Box {
                IconButton(onClick = { showOverflow = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreHoriz,
                        contentDescription = stringResource(R.string.action_more),
                        tint = ZillitTheme.colors.textSecondary,
                    )
                }

                DropdownMenu(
                    expanded = showOverflow,
                    onDismissRequest = { showOverflow = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.email_move_to_folder)) },
                        onClick = {
                            showOverflow = false
                            onMove()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (state.isTrash) {
                                        R.string.email_delete_permanently
                                    } else {
                                        R.string.delete
                                    },
                                ),
                            )
                        },
                        onClick = {
                            showOverflow = false
                            onDelete()
                        },
                    )

                    // One message prints; a conversation asks which.
                    if (state.isConversation) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.email_print_this)) },
                            onClick = {
                                showOverflow = false
                                onPrint(false)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.email_print_all)) },
                            onClick = {
                                showOverflow = false
                                onPrint(true)
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.email_print)) },
                            onClick = {
                                showOverflow = false
                                onPrint(false)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyMenuItem(action: ReplyAction, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(action.menuLabelRes)) },
        leadingIcon = { Icon(action.icon, contentDescription = null) },
        onClick = onClick,
    )
}

private val ReplyAction.icon: ImageVector
    get() = when (this) {
        ReplyAction.REPLY -> Icons.AutoMirrored.Outlined.Reply
        ReplyAction.REPLY_ALL -> Icons.AutoMirrored.Outlined.ReplyAll
        ReplyAction.FORWARD -> Icons.AutoMirrored.Outlined.Forward
    }

/** The bar's own label — v2 writes "Reply all" here and "Reply All" in the menu. */
private val ReplyAction.labelRes: Int
    get() = when (this) {
        ReplyAction.REPLY -> R.string.email_reply
        ReplyAction.REPLY_ALL -> R.string.email_reply_all_label
        ReplyAction.FORWARD -> R.string.email_forward
    }

private val ReplyAction.menuLabelRes: Int
    get() = when (this) {
        ReplyAction.REPLY -> R.string.email_reply
        ReplyAction.REPLY_ALL -> R.string.email_reply_all
        ReplyAction.FORWARD -> R.string.email_forward
    }
