package com.zillit.zillitapp.core.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.chat.data.ChatModule
import com.zillit.zillitapp.core.labels.asServerText
import com.zillit.zillitapp.core.labels.resolve
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/** What history to show. Enough for any surface — the module supplies the endpoint. */
data class ChatHistoryRequest(
    val module: ChatModule,
    /** Unit, group or tool id. */
    val scopeId: String,
    /** Already resolved; the screen appends " History". */
    val title: String,
    /** Set on the script tools, which partition history by episode. */
    val episode: String? = null,
)

/**
 * A thread's replaced and deleted messages.
 *
 * **The same chat UI**, read-only: [ChatThread] with no composer, no selection and no
 * reply affordances. Vidya asked for exactly this — *"history page UI it's same as the chat
 * ui so you can reuse it"* — and it is the right shape anyway, because history is chat that
 * has stopped changing. Rebuilding a parallel list would mean two renderers to keep in step
 * every time a bubble changes.
 *
 * The long-press menu is reduced to what still means something on a message nobody can
 * reply to: **Save** and **Print**. v2 makes the same cut.
 */
@Composable
fun ChatHistoryScreen(
    request: ChatHistoryRequest,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatHistoryViewModel = hiltViewModel(),
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val longPressed by viewModel.longPressed.collectAsStateWithLifecycle()

    LaunchedEffect(request.scopeId, request.episode) { viewModel.load(request) }

    PrintLauncher(requests = viewModel.printRequests)

    // Same external-viewer handoff the live thread uses for documents.
    val context = LocalContext.current
    val cache = viewModel.mediaCache
    LaunchedEffect(viewModel) {
        viewModel.openRequests.collect { file -> context.openFileExternally(file, cache) }
    }

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
                // The unit name is a server label key (`call_sheet_unit_label`), so it is
                // resolved here rather than shown raw.
                text = stringResource(
                    R.string.history_title,
                    request.title.asServerText().resolve(),
                ),
                style = MaterialTheme.typography.titleMedium,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        ChatThread(
            feed = feed,
            modifier = Modifier.fillMaxSize(),
            // Read-only, not "no rights": nobody can post to a replaced message, so the
            // composer is absent and no notice explains its absence.
            isReadOnly = true,
            isLoading = isLoading,
            onLoadOlder = viewModel::loadOlder,
            onLongPress = viewModel::onLongPressed,
            onOpenMedia = viewModel::onMediaOpened,
        )
    }

    longPressed?.let { message ->
        ChatMessageOptionsSheet(
            message = message,
            options = viewModel.optionsFor(),
            onOptionSelected = viewModel::onOptionSelected,
            onDismiss = viewModel::dismissLongPress,
        )
    }
}
