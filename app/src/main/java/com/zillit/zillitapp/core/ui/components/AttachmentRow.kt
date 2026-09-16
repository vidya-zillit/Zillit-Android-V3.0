package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import java.util.Locale

/** Where an attachment is in its life. Drives the trailing control, nothing else. */
enum class AttachmentState {
    /** On the server, or on disk. The row offers whatever [AttachmentRow.onAction] is. */
    READY,

    /** Going up, or coming down. Spinner, no action. */
    BUSY,

    /**
     * It did not make it.
     *
     * v2 models this and then renders it identically to a successful upload, so a mail
     * sends with an attachment the recipient never receives. Here it is a distinct state
     * with a distinct look.
     */
    FAILED,
}

/**
 * One attachment, as a row.
 *
 * Used in three places that each used to draw their own: the compose screen's staged
 * attachments (with a remove ✕), the detail screen's received attachments (with a
 * download ⤓), and the rules editor's example payloads (with nothing).
 *
 * The icon is derived from the file name rather than passed in, because every caller was
 * deriving it the same way and getting slightly different answers about what counts as a
 * document.
 */
@Composable
fun AttachmentRow(
    fileName: String,
    modifier: Modifier = Modifier,
    sizeBytes: Long? = null,
    state: AttachmentState = AttachmentState.READY,
    onClick: (() -> Unit)? = null,
    /** The trailing control. Null draws nothing — a read-only listing. */
    onAction: (() -> Unit)? = null,
    actionIcon: ImageVector = Icons.Outlined.Download,
    actionDescription: String? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ZillitTheme.colors.surfaceSunken,
        border = BorderStroke(
            1.dp,
            if (state == AttachmentState.FAILED) {
                ZillitTheme.colors.danger.copy(alpha = 0.5f)
            } else {
                ZillitTheme.colors.border
            },
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Icon(
                imageVector = iconFor(fileName),
                contentDescription = null,
                tint = if (state == AttachmentState.FAILED) {
                    ZillitTheme.colors.danger
                } else {
                    ZillitTheme.colors.brand
                },
                modifier = Modifier.size(24.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val caption = when (state) {
                    AttachmentState.FAILED -> stringResource(R.string.attachment_failed)
                    AttachmentState.BUSY -> stringResource(R.string.attachment_in_progress)
                    AttachmentState.READY -> sizeBytes?.let { formatFileSize(it) }
                }
                if (caption != null) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state == AttachmentState.FAILED) {
                            ZillitTheme.colors.danger
                        } else {
                            ZillitTheme.colors.textSecondary
                        },
                    )
                }
            }

            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                when {
                    state == AttachmentState.BUSY -> CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = ZillitTheme.colors.brand,
                        modifier = Modifier.size(18.dp),
                    )

                    state == AttachmentState.FAILED && onAction != null -> Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = actionDescription,
                        tint = ZillitTheme.colors.danger,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(onClick = onAction),
                    )

                    onAction != null -> Icon(
                        imageVector = actionIcon,
                        contentDescription = actionDescription,
                        tint = ZillitTheme.colors.textSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(onClick = onAction),
                    )
                }
            }
        }
    }
}

/** Convenience for the compose screen: the action is always "take this one off again". */
@Composable
fun StagedAttachmentRow(
    fileName: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    sizeBytes: Long? = null,
    state: AttachmentState = AttachmentState.READY,
    /** Offered on a failed upload. Null keeps the row as remove-only. */
    onRetry: (() -> Unit)? = null,
) {
    AttachmentRow(
        fileName = fileName,
        modifier = modifier,
        sizeBytes = sizeBytes,
        state = state,
        // A failed upload is the one case where the row's own tap should do something:
        // retrying is the action the user wants, and removing and re-picking the file is
        // the only alternative.
        onClick = onRetry.takeIf { state == AttachmentState.FAILED },
        onAction = onRemove,
        actionIcon = Icons.Outlined.Close,
        actionDescription = stringResource(R.string.action_remove),
    )
}

private fun iconFor(fileName: String): ImageVector =
    when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
        "pdf" -> Icons.Outlined.PictureAsPdf
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> Icons.Outlined.Image
        "mp4", "mov", "avi", "mkv", "webm", "3gp" -> Icons.Outlined.VideoFile
        "mp3", "wav", "m4a", "aac", "ogg", "opus" -> Icons.Outlined.AudioFile
        "xls", "xlsx", "csv", "numbers" -> Icons.Outlined.TableChart
        else -> Icons.Outlined.Description
    }

/**
 * "1.4 MB".
 *
 * Base 1024, which is the base the 25 MB attachment cap is written in. Sizing the display
 * in base 1000 next to a base-1024 limit means a file shown as 26.1 MB is accepted while
 * one shown as 25.1 MB is refused — and it is also what v2 shows for the same file.
 */
fun formatFileSize(bytes: Long): String = when {
    bytes < KILOBYTE -> "$bytes B"
    bytes < MEGABYTE -> String.format(Locale.US, "%.0f KB", bytes / KILOBYTE.toDouble())
    bytes < GIGABYTE -> String.format(Locale.US, "%.1f MB", bytes / MEGABYTE.toDouble())
    else -> String.format(Locale.US, "%.1f GB", bytes / GIGABYTE.toDouble())
}

private const val KILOBYTE = 1024L
private const val MEGABYTE = KILOBYTE * 1024
private const val GIGABYTE = MEGABYTE * 1024
