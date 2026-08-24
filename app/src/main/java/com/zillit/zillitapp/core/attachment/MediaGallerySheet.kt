package com.zillit.zillitapp.core.attachment

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import kotlinx.coroutines.launch

/**
 * The app's own gallery: **albums first, then the media inside one**.
 *
 * Opening straight onto one chronological stream does not survive a real phone — tens of
 * thousands of items, and the screenshot or slate someone wants is nowhere near the top.
 * MediaStore already groups by containing folder, so the first level is those folders,
 * which is also the structure every user has already learned from their own gallery app.
 *
 * There is deliberately **no caption field here**. A caption belongs to a file, and files
 * are reviewed one at a time on the preview screen — putting one in the grid would apply
 * it to whichever item happened to be tapped last, which is not something a user can see
 * or predict.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGallerySheet(
    filter: GalleryFilter,
    maxSelectable: Int,
    onConfirm: (List<PickedMedia>) -> Unit,
    onDismiss: () -> Unit,
) {
    val source = rememberMediaStoreSource()
    val resolver = rememberMediaFileResolver()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var buckets by remember { mutableStateOf<List<MediaBucket>>(emptyList()) }
    var openBucket by remember { mutableStateOf<MediaBucket?>(null) }
    var entries by remember { mutableStateOf<List<GalleryEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isResolving by remember { mutableStateOf(false) }

    // Selection order is send order, so this is a list, not a set. It survives moving
    // between albums — picking two shots from Camera and one from Screenshots is normal.
    val selected: SnapshotStateList<GalleryEntry> =
        remember { mutableListOf<GalleryEntry>().toMutableStateList() }

    LaunchedEffect(filter) {
        isLoading = true
        buckets = source.queryBuckets(filter)
        isLoading = false
    }

    LaunchedEffect(openBucket) {
        val bucket = openBucket ?: return@LaunchedEffect
        isLoading = true
        entries = source.query(filter, bucketId = bucket.id)
        isLoading = false
    }

    // Back goes up a level before it closes the sheet — otherwise opening an album is a
    // one-way trip and the only way out is dismissing the whole picker.
    BackHandler(enabled = openBucket != null) { openBucket = null }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                if (openBucket != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = ZillitTheme.colors.textPrimary,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { openBucket = null },
                    )
                }

                Text(
                    text = openBucket?.name ?: stringResource(
                        when (filter) {
                            GalleryFilter.VIDEOS -> R.string.attachment_video_gallery
                            GalleryFilter.AUDIO -> R.string.attachment_audio
                            else -> R.string.attachment_gallery
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (selected.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.attachment_selected_count,
                            selected.size,
                            maxSelectable,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = ZillitTheme.colors.brand,
                    )
                }
            }

            HorizontalDivider(color = ZillitTheme.colors.divider)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    isLoading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = ZillitTheme.colors.brand,
                    )

                    openBucket == null && buckets.isEmpty() -> EmptyLabel()

                    openBucket == null -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(BUCKET_CELL_MIN),
                        contentPadding = PaddingValues(ZillitTheme.spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(buckets, key = { it.id }) { bucket ->
                            BucketCell(bucket = bucket, onClick = { openBucket = bucket })
                        }
                    }

                    entries.isEmpty() -> EmptyLabel()

                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(MEDIA_CELL_MIN),
                        contentPadding = PaddingValues(ZillitTheme.spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            val index = selected.indexOfFirst { it.id == entry.id }
                            GalleryCell(
                                entry = entry,
                                selectionIndex = if (index >= 0) index + 1 else null,
                                onClick = {
                                    if (index >= 0) {
                                        selected.removeAt(index)
                                    } else if (selected.size < maxSelectable) {
                                        selected.add(entry)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (selected.isNotEmpty()) {
                HorizontalDivider(color = ZillitTheme.colors.divider)
                NextBar(
                    count = selected.size,
                    isBusy = isResolving,
                    onNext = {
                        isResolving = true
                        scope.launch {
                            // Resolved only here: copying every tapped file would stage
                            // megabytes the user may never send.
                            val media = selected.mapNotNull { resolver.resolve(it.uri) }
                            isResolving = false
                            onConfirm(media)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyLabel() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.attachment_gallery_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.padding(ZillitTheme.spacing.xl),
        )
    }
}

@Composable
private fun BucketCell(bucket: MediaBucket, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = bucket.coverUri,
            contentDescription = bucket.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(ZillitTheme.shapes.small))
                .background(ZillitTheme.colors.surfaceSunken),
        )
        Text(
            text = bucket.name,
            style = MaterialTheme.typography.labelLarge,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = bucket.itemCount.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.textTertiary,
        )
    }
}

@Composable
private fun GalleryCell(
    entry: GalleryEntry,
    selectionIndex: Int?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(ZillitTheme.shapes.small))
            .background(ZillitTheme.colors.surfaceSunken)
            .then(
                if (selectionIndex != null) {
                    Modifier.border(
                        3.dp,
                        ZillitTheme.colors.brand,
                        RoundedCornerShape(ZillitTheme.shapes.small),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = entry.uri,
            contentDescription = entry.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        if (entry.mimeType.startsWith("audio/")) {
            Icon(
                imageVector = Icons.Outlined.AudioFile,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }

        if (entry.isVideo) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
            Text(
                text = entry.durationMs.asDuration(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
            )
        }

        // The number, not a tick: with ordered multi-select the position is the
        // information — it tells the user what order the files will send in.
        selectionIndex?.let { index ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .background(ZillitTheme.colors.brand, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textOnBrand,
                )
            }
        }
    }
}

/**
 * Advances to the preview.
 *
 * Labelled as a next step rather than a send: sending happens on the preview screen,
 * after captions and any edits, and a send button here would imply this is the last stop.
 */
@Composable
private fun NextBar(count: Int, isBusy: Boolean, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.attachment_next_count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )

        Box(
            modifier = Modifier
                .size(44.dp)
                .background(ZillitTheme.colors.brand, CircleShape)
                .clickable(enabled = !isBusy, onClick = onNext),
            contentAlignment = Alignment.Center,
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = ZillitTheme.colors.textOnBrand,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.action_next),
                    tint = ZillitTheme.colors.textOnBrand,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun Long.asDuration(): String {
    val totalSeconds = this / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Two per row on a phone, more on a tablet. */
private val BUCKET_CELL_MIN = 150.dp

private val MEDIA_CELL_MIN = 96.dp
