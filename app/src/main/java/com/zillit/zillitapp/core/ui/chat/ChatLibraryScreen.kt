package com.zillit.zillitapp.core.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/** The three things a chat accumulates that people go looking for later. */
enum class ChatLibraryTab { MEDIA, DOCS, LINKS }

/**
 * Everything posted in a thread, grouped by kind — v2's "Media / Docs / Links".
 *
 * The Gallery option in the long-press menu opens this. Its value is that a production
 * thread is mostly conversation and the file you need is thirty screens up; grouping by
 * kind turns "scroll and hope" into "it is in Docs".
 *
 * Built from the feed already in memory rather than a separate endpoint, exactly as v2
 * does — the messages are loaded, and a second source could disagree with the thread.
 */
@Composable
fun ChatLibraryScreen(
    title: String,
    messages: List<ChatMessage>,
    onOpen: (ChatMessage) -> Unit,
    onOpenLink: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Which tab opens first.
     *
     * Media unless the caller asks otherwise. The Files shortcut on a details screen asks
     * for Docs: landing on Media and making the user find the tab would answer a different
     * question from the one they tapped.
     */
    initialTab: ChatLibraryTab = ChatLibraryTab.MEDIA,
) {
    var tab by remember(initialTab) { mutableStateOf(initialTab) }

    val media = remember(messages) {
        messages.filter { it is ChatMessage.Image || it is ChatMessage.Video }
    }
    val docs = remember(messages) { messages.filterIsInstance<ChatMessage.Document>() }
    val links = remember(messages) {
        messages.filterIsInstance<ChatMessage.Text>()
            .flatMap { message -> LINK_PATTERN.findAll(message.body).map { it.value } }
            .distinct()
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
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surface)
                .padding(
                    start = ZillitTheme.spacing.md,
                    end = ZillitTheme.spacing.md,
                    bottom = ZillitTheme.spacing.sm,
                ),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            LibraryTab(R.string.library_media, media.size, tab == ChatLibraryTab.MEDIA) {
                tab = ChatLibraryTab.MEDIA
            }
            LibraryTab(R.string.library_docs, docs.size, tab == ChatLibraryTab.DOCS) {
                tab = ChatLibraryTab.DOCS
            }
            LibraryTab(R.string.library_links, links.size, tab == ChatLibraryTab.LINKS) {
                tab = ChatLibraryTab.LINKS
            }
        }

        when (tab) {
            // A grid for media because recognition is visual; a list for the other two
            // because their identity is their text.
            ChatLibraryTab.MEDIA -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    ZillitTheme.spacing.xs,
                ),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                items(media, key = { it.id }) { item ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(ZillitTheme.shapes.small))
                            .background(ZillitTheme.colors.surfaceSunken)
                            .clickable { onOpen(item) },
                    ) {
                        LibraryThumbnail(item)
                    }
                }
            }

            ChatLibraryTab.DOCS -> LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            ) {
                items(docs, key = { it.id }) { doc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(doc) }
                            .padding(ZillitTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(ZillitTheme.shapes.small))
                                .background(ZillitTheme.colors.brandSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = doc.fileKind,
                                style = MaterialTheme.typography.labelSmall,
                                color = ZillitTheme.colors.brand,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = doc.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ZillitTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = doc.fileSize,
                                style = MaterialTheme.typography.labelSmall,
                                color = ZillitTheme.colors.textTertiary,
                            )
                        }
                    }
                }
            }

            ChatLibraryTab.LINKS -> LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            ) {
                items(links, key = { it }) { link ->
                    Text(
                        text = link,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenLink(link) }
                            .padding(ZillitTheme.spacing.md),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTab(
    labelRes: Int,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
            .background(
                if (isSelected) ZillitTheme.colors.brand else ZillitTheme.colors.surfaceSunken,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) {
                ZillitTheme.colors.textOnBrand
            } else {
                ZillitTheme.colors.textSecondary
            },
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) {
                ZillitTheme.colors.textOnBrand
            } else {
                ZillitTheme.colors.textTertiary
            },
        )
    }
}

@Composable
private fun LibraryThumbnail(item: ChatMessage) {
    val remoteKey = when (item) {
        is ChatMessage.Image -> item.remoteKey
        is ChatMessage.Video -> item.remoteKey
        else -> null
    }
    val thumbnail = when (item) {
        is ChatMessage.Image -> item.thumbnail
        is ChatMessage.Video -> item.thumbnail
        else -> null
    }
    val local = when (item) {
        is ChatMessage.Image -> item.localPath
        is ChatMessage.Video -> item.localPath
        else -> null
    }

    val model by rememberAttachmentImage(
        remoteKey = remoteKey,
        thumbnailKey = thumbnail,
        fileName = remoteKey?.substringAfterLast('/').orEmpty(),
        localPath = local,
    )

    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )

    if (item is ChatMessage.Video) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = ZillitTheme.colors.textOnBrand,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** Deliberately permissive — a bare `zillit.com/x` in a message is still a link people want. */
private val LINK_PATTERN =
    Regex("""(https?://\S+|www\.\S+)""", RegexOption.IGNORE_CASE)
