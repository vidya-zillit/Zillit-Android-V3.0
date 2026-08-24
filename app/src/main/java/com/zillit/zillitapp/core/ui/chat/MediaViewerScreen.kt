package com.zillit.zillitapp.core.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.storage.DownloadState
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/** One item in the viewer. Flattened from the thread so paging does not touch Realm. */
data class ViewableMedia(
    val id: String,
    val remoteKey: String?,
    val thumbnailKey: String?,
    val localPath: String?,
    val fileName: String,
    val caption: String?,
    val authorName: String,
    val timestamp: String,
    val isImage: Boolean,
)

/**
 * Full-screen media viewer, opened by tapping an attachment in the thread.
 *
 * Pages across **every** media message in the unit rather than showing only the one
 * tapped: that is how v2's `CustomImageViewer` behaves, and it is what someone reviewing a
 * day's stills actually wants — swipe, not back-and-tap-the-next-one.
 *
 * The full-resolution file is fetched on open and cached; until it arrives the thumbnail
 * already on screen is shown, so the transition never flashes empty.
 */
@Composable
fun MediaViewerScreen(
    media: List<ViewableMedia>,
    initialId: String,
    onClose: () -> Unit,
    onShare: (ViewableMedia) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (media.isEmpty()) {
        onClose()
        return
    }

    val startIndex = media.indexOfFirst { it.id == initialId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { media.size })
    val current = media.getOrNull(pagerState.currentPage) ?: media.first()

    val (downloadState, startDownload) = rememberDownloadState(
        remoteKey = current.remoteKey,
        fileName = current.fileName,
    )

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ViewerAction(Icons.Filled.Close, R.string.action_close, onClose)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current.authorName,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${current.timestamp} · ${pagerState.currentPage + 1}/${media.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }

            // Progress replaces the button while fetching, so a slow file is visibly
            // working rather than an unresponsive icon.
            when (downloadState) {
                is DownloadState.InProgress -> CircularProgressIndicator(
                    progress = { downloadState.fraction },
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )

                is DownloadState.Ready -> ViewerAction(Icons.Outlined.Share, R.string.option_share) {
                    onShare(current)
                }

                else -> ViewerAction(Icons.Outlined.Download, R.string.option_save) {
                    startDownload()
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            ZoomableMedia(media[page])
        }

        current.caption?.takeIf { it.isNotBlank() }?.let { caption ->
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            )
        }
    }
}

/**
 * Pinch-to-zoom and pan.
 *
 * Bounded so the image cannot be flung off screen or shrunk to nothing — a zoom that can
 * get lost is worse than no zoom, since the only recovery is closing and reopening.
 */
@Composable
private fun ZoomableMedia(item: ViewableMedia) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }

    val model by rememberAttachmentImage(
        remoteKey = item.remoteKey,
        thumbnailKey = item.thumbnailKey,
        fileName = item.fileName,
        localPath = item.localPath,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    // Panning is only meaningful while zoomed in; at 1x it would just
                    // slide the picture away from the frame.
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = model,
            contentDescription = item.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

@Composable
private fun ViewerAction(
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

private const val MIN_SCALE = 1f

private const val MAX_SCALE = 5f
