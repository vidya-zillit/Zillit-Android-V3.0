package com.zillit.zillitapp.core.ui.chat

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.labels.asServerText
import com.zillit.zillitapp.core.labels.resolve
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * One row: who, and when they read or received it.
 *
 * The label around the time ("Read", "Not Delivered") is left to the screen so it goes
 * through `stringResource`; only the time itself arrives pre-formatted.
 */
data class ReadByPerson(
    val userId: String,
    val name: String,
    /** Designation, already resolved. */
    val role: String? = null,
    /** "12 Aug at 09:18", or empty when there is no timestamp. */
    val time: String = "",
    val isRead: Boolean = false,
    /** False means it never reached the device at all. */
    val isDelivered: Boolean = true,
)

/** Everything the screen renders. */
data class ReadByUiState(
    val isLoading: Boolean = false,
    val read: List<ReadByPerson> = emptyList(),
    val unread: List<ReadByPerson> = emptyList(),
    /** Notify is offered only where the viewer can post into the thread. */
    val canNotify: Boolean = false,
)

/**
 * Who has read a message — one screen for every chat.
 *
 * v2 has a single `ReadByUserPage` too, but reaches it through a `Navigation` enum with
 * twenty-odd branches that each name a different endpoint constant. Here the caller passes
 * a [com.zillit.zillitapp.core.chat.data.ChatModule] and the state arrives already
 * resolved, so Home, Production Report and the tool chats share this without any of them
 * appearing in it.
 *
 * Two sections, read first, because "who has seen this?" is the question being asked; the
 * unread list is what you act on with Notify.
 */
@Composable
fun ReadByUserScreen(
    state: ReadByUiState,
    onClose: () -> Unit,
    onNotify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var confirmingNotify by remember { mutableStateOf(false) }

    val read = state.read.filtered(query)
    val unread = state.unread.filtered(query)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surface)
                .padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cancel),
                tint = ZillitTheme.colors.textPrimary,
                modifier = Modifier.size(24.dp).clickable(onClick = onClose),
            )
            Text(
                text = stringResource(R.string.read_by_users),
                style = MaterialTheme.typography.titleMedium,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            // Notify sits in the bar rather than under the unread list: with a long crew
            // the list scrolls and the action would be unreachable exactly when it is
            // needed most.
            if (state.canNotify && state.unread.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
                        .background(ZillitTheme.colors.brand)
                        .clickable { confirmingNotify = true }
                        .padding(
                            horizontal = ZillitTheme.spacing.md,
                            vertical = ZillitTheme.spacing.xs,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsActive,
                        contentDescription = null,
                        tint = ZillitTheme.colors.textOnBrand,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.txt_notify_users),
                        style = MaterialTheme.typography.labelLarge,
                        color = ZillitTheme.colors.textOnBrand,
                    )
                }
            }
        }

        SearchField(query = query, onQueryChange = { query = it })

        when {
            state.isLoading && state.read.isEmpty() && state.unread.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = ZillitTheme.colors.brand)
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = ZillitTheme.spacing.xl,
                ),
            ) {
                item(key = "read-header") {
                    SectionHeader(
                        title = stringResource(R.string.read_byr),
                        // The count that matters here is how many are still outstanding,
                        // which is what v2 puts beside this heading too.
                        trailing = if (state.unread.isNotEmpty()) {
                            "${state.unread.size} ${stringResource(R.string.remaining)}"
                        } else {
                            null
                        },
                        showTick = true,
                    )
                }
                items(read, key = { "read-${it.userId}" }) { PersonRow(it) }

                item(key = "unread-header") {
                    SectionHeader(title = stringResource(R.string.unread_users))
                }
                items(unread, key = { "unread-${it.userId}" }) { PersonRow(it) }
            }
        }
    }

    if (confirmingNotify) {
        ZillitConfirmDialog(
            // A server label, not a shipped string — the wording is edited backend-side.
            message = NOTIFY_CONFIRMATION_LABEL.asServerText().resolve(),
            onConfirm = {
                confirmingNotify = false
                onNotify()
            },
            onDismiss = { confirmingNotify = false },
        )
    }
}

private fun List<ReadByPerson>.filtered(query: String): List<ReadByPerson> =
    if (query.isBlank()) this else filter { it.name.contains(query.trim(), ignoreCase = true) }

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(ZillitTheme.spacing.md)
            .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_by_user_name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textTertiary,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyMedium.copy(
                        color = ZillitTheme.colors.textPrimary,
                    ),
                ),
                cursorBrush = SolidColor(ZillitTheme.colors.brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null, showTick: Boolean = false) {
    HorizontalDivider(color = ZillitTheme.colors.divider)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = ZillitTheme.colors.accent,
        )
        if (showTick) {
            Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = null,
                tint = ZillitTheme.colors.brand,
                modifier = Modifier.size(18.dp),
            )
        }
        Box(modifier = Modifier.weight(1f))
        trailing?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun PersonRow(person: ReadByPerson) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(ZillitTheme.colors.brandSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = person.name.initials(),
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.accent,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = person.name,
                style = MaterialTheme.typography.bodyLarge,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val timing = when {
                person.isRead -> "${stringResource(R.string.read)}: ${person.time}"
                !person.isDelivered -> stringResource(R.string.not_delivered)
                else -> "${stringResource(R.string.delivered)}: ${person.time}"
            }
            // The designation is a server label key (`director_label`), resolved here and
            // not when it was stored — a language change must not need the crew rewritten.
            val role = person.role?.takeIf { it.isNotBlank() }?.asServerText()?.resolve()
            val detail = listOfNotNull(timing.takeIf { it.isNotBlank() }, role)
                .joinToString(" · ")

            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
    }
}

private fun String.initials(): String =
    trim().split(Regex("\\s+"))
        .mapNotNull { it.firstOrNull { c -> c.isLetter() }?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }

/** v2's `Constants.NOTIFY_CONFIRMATION`. */
private const val NOTIFY_CONFIRMATION_LABEL = "notify_confirmation_to_send"

/**
 * [ReadByUserScreen] with its own ViewModel, so a caller only has to hand over a request.
 *
 * Every chat surface opens read-by the same way; wiring the fetch at each call site would
 * be the twenty-branch `when` v2 ended up with, one screen further down.
 */
@Composable
fun ReadByUserSheet(
    request: ReadByRequest,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReadByUserViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Keyed on the message so reopening on a different one refetches rather than showing
    // the previous message's readers.
    LaunchedEffect(request.messageId, request.commentId) { viewModel.load(request) }

    ReadByUserScreen(
        state = state,
        onClose = onClose,
        onNotify = viewModel::notifyUnread,
        modifier = modifier,
    )
}
