package com.zillit.zillitapp.core.attachment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * What the user sees between picking and sending.
 *
 * Mirrors v2's `GalleryViewer`: a pager over everything selected, a filmstrip to move
 * between them, a **per-item** caption, remove, mute-for-video, and an entry into the
 * image editor. Sending happens from here, so picking → captioning → editing → sending is
 * one flow rather than a sequence of screens.
 *
 * Full-screen rather than a sheet: this is where someone crops a slate or annotates a
 * continuity photo, and a sheet-sized canvas makes that unusable.
 *
 * @param onEdit opens the image editor for one item. The caller supplies the result
 *   because editing writes a new file, and only the caller knows where it should live.
 */
@Composable
fun MediaPreviewScreen(
    media: List<PickedMedia>,
    onSend: (List<PickedMedia>) -> Unit,
    /**
     * Opens the editor. The replacement returned by [onEdited] takes the item's place, so
     * a crop or an annotation is what actually gets sent.
     */
    onEdit: (PickedMedia) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Reopens the gallery to append to this selection.
     *
     * Appending rather than replacing: someone who has already captioned three photos and
     * then remembers a fourth must not lose the first three, which is what a plain
     * "pick again" would do.
     */
    onAddMore: (() -> Unit)? = null,
    /**
     * An edited replacement for an item already on screen, matched by [PickedMedia.uri].
     *
     * Passed in rather than returned by [onEdit] because the editor runs as a separate
     * surface: it finishes long after that call returned, and without this the preview
     * kept rendering the original file.
     */
    edited: PickedMedia? = null,
) {
    // Keyed on identity, not on the list value.
    //
    // `remember(media)` re-ran whenever the caller recomposed with an equal-but-new list,
    // which threw away removals — deleting an item and then adding more brought the
    // deleted one back. The working copy is seeded once and thereafter owned here; the
    // caller re-mounts the screen when it genuinely has a different selection.
    val items: SnapshotStateList<PickedMedia> = remember { media.toMutableStateList() }
    val pagerState = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()
    var mutedVideos by remember { mutableStateOf(setOf<String>()) }

    // Appending from "add more" arrives through the parameter, so genuinely new files are
    // merged in without disturbing what is already here.
    LaunchedEffect(media) {
        val known = items.map { it.localPath }.toSet()
        media.filterNot { it.localPath in known }.forEach { items.add(it) }
    }

    // Removing the last item is a cancel, not an empty screen with a dead send button.
    if (items.isEmpty()) {
        onClose()
        return
    }

    LaunchedEffect(edited) {
        val replacement = edited ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.uri == replacement.uri }
        // Caption survives the edit — it belongs to the item, not to the file.
        if (index >= 0) items[index] = replacement.copy(caption = items[index].caption)
    }

    val currentIndex = pagerState.currentPage.coerceIn(0, items.lastIndex)
    val current = items[currentIndex]

    // Scaffold, not a Column with a weighted middle.
    //
    // In a Column the caption bar is measured last, from whatever the weighted pager
    // leaves — and inside the picker's dialog the bottom inset is not reported, so the bar
    // was squeezed to 21px against the screen edge. Scaffold measures its bars at their
    // intrinsic height *first* and gives the remainder to the content, so the bar is never
    // the loser regardless of what the window reports.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Deliberately dark regardless of theme: a photo is judged against a neutral
        // ground, and a light chrome around it shifts how the image reads.
        containerColor = Color(0xFF0E1116),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            PreviewAction(Icons.Filled.Close, R.string.action_close, onClose)

            Text(
                text = "${currentIndex + 1}/${items.size}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )

            if (current.isVideo) {
                val isMuted = current.localPath in mutedVideos
                PreviewAction(
                    icon = if (isMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    labelRes = R.string.preview_mute,
                ) {
                    mutedVideos = if (isMuted) {
                        mutedVideos - current.localPath
                    } else {
                        mutedVideos + current.localPath
                    }
                }
            }

            // Editing only applies to stills — v2 hides it for video and documents too.
            if (current.isImage) {
                PreviewAction(Icons.Outlined.Edit, R.string.preview_edit) { onEdit(current) }
            }

            onAddMore?.let {
                PreviewAction(Icons.Filled.Add, R.string.preview_add_more, it)
            }

            PreviewAction(Icons.Outlined.Delete, R.string.preview_remove) {
                    items.removeAt(currentIndex)
                }
            }
        },
        bottomBar = {
            Column {
                if (items.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ZillitTheme.spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = ZillitTheme.spacing.sm),
                    ) {
                        itemsIndexed(items, key = { _, item -> item.localPath }) { index, item ->
                            val isCurrent = item.localPath == current.localPath
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    // The filmstrip is the only way to reach another item
                                    // without swiping the pager, and it was not clickable.
                                    .clickable { scope.launch { pagerState.scrollToPage(index) } }
                                    .clip(RoundedCornerShape(ZillitTheme.shapes.small))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0xFF3A4A5F), Color(0xFF23303F)),
                                        ),
                                    )
                                    .then(
                                        if (isCurrent) {
                                            Modifier.border(
                                                2.dp,
                                                ZillitTheme.colors.brand,
                                                RoundedCornerShape(ZillitTheme.shapes.small),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                AsyncImage(
                                    model = item.localPath,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }

                CaptionSendBar(
                    caption = current.caption,
                    onCaptionChange = { text ->
                        // Caption belongs to the item on screen, not the batch — v2's
                        // per-item `description`, and why the field follows the pager.
                        items[currentIndex] = current.copy(caption = text)
                    },
                    onSend = { onSend(items.toList()) },
                )
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { page ->
            PreviewPage(items[page])
        }
    }
}

@Composable
private fun PreviewPage(item: PickedMedia) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ZillitTheme.spacing.lg)
                .clip(RoundedCornerShape(ZillitTheme.shapes.medium)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = item.localPath,
                contentDescription = item.fileName,
                // Fit, not Crop: this is the review step, so the whole frame has to be
                // visible — cropping here would hide exactly what someone is checking.
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )

            if (item.isVideo) {
                Box(
                    modifier = Modifier.size(64.dp).background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            Text(
                text = item.fileName,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(ZillitTheme.spacing.md),
            )
        }
    }
}

@Composable
private fun PreviewAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(labelRes),
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CaptionSendBar(
    caption: String,
    onCaptionChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = caption,
                onValueChange = onCaptionChange,
                textStyle = LocalTextStyle.current
                    .merge(MaterialTheme.typography.bodyMedium)
                    .copy(color = Color.White),
                cursorBrush = SolidColor(ZillitTheme.colors.brand),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (caption.isEmpty()) {
                        Text(
                            text = stringResource(R.string.attachment_caption_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                    inner()
                },
            )
        }

        Box(
            modifier = Modifier
                .size(46.dp)
                .background(ZillitTheme.colors.brand, CircleShape)
                .clickable(onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.chat_send),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
