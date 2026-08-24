package com.zillit.zillitapp.core.attachment

import android.net.Uri

/**
 * One file the user picked, with everything an upload or a chat bubble needs.
 *
 * v2's `GalleryItem` carries thirty-odd fields, most of them UI state for the gallery
 * screen itself (`isVideoPaused`, `lastMinValue`, `trimDuration`, a live `MediaPlayer`).
 * Those belong to the gallery, not to its result — a picker's output should describe the
 * files, so this is only what survives the screen.
 *
 * @param localPath a real filesystem path, not a `content://` URI. Uploads need a `File`,
 *   and a content URI's permission grant does not survive the process, so anything picked
 *   through the system picker is copied into app storage first.
 * @param caption the per-file text v2 calls `description`. Each file carries its own,
 *   which is why the gallery shows a caption field per selection rather than one for the
 *   batch.
 */
data class PickedMedia(
    val uri: Uri,
    val localPath: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val caption: String = "",
    /** Generated for images and videos; null for anything without a visual preview. */
    val thumbnailPath: String? = null,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isAudio: Boolean get() = mimeType.startsWith("audio/")

    /**
     * The message type the chat API expects.
     *
     * Mirrors v2's `messageTypeOrContentTypeProvider`: everything that is not image,
     * video or audio is a document, including PDFs.
     */
    val chatMessageType: String
        get() = when {
            isImage -> "image"
            isVideo -> "video"
            isAudio -> "audio"
            // "document", not "doc" — v2's Constants.DOC. The backend rejects "doc"
            // outright with `unit_chat_message_type_invalid`.
            else -> "document"
        }

    /** Extension without the dot, uppercased — the badge on a file row ("PDF", "XLS"). */
    val fileKind: String
        get() = fileName.substringAfterLast('.', "").uppercase().take(4).ifBlank { "FILE" }
}

/** What the picker hands back. Location and contact are not files, so they are separate. */
sealed interface AttachmentResult {
    data class Media(val items: List<PickedMedia>) : AttachmentResult

    data class Location(
        val latitude: Double,
        val longitude: Double,
        val address: String,
    ) : AttachmentResult

    data class Contact(val name: String, val phone: String) : AttachmentResult

    /** Dismissed, or a permission was refused. */
    data object Cancelled : AttachmentResult
}
