package com.zillit.zillitapp.core.storage

import java.io.File

/**
 * The vocabulary of the transfer layer.
 *
 * v2 spreads this across `UploadingModel`, `AttachmentModel`, `FileDataUploading`,
 * `UploadListModel` and `DownloadModelsHandler`, with fields that mean different things
 * depending on which helper is reading them (`uniqueId` is a remote key in one place and a
 * client id in another). Three types here, each with one meaning.
 */

/**
 * A file going up.
 *
 * @param remoteKey the object key on the storage backend — `{projectId}/{folder}/{name}`.
 *   This is what the chat message will reference, so it is decided by the caller before
 *   the upload starts rather than being whatever the backend assigns.
 * @param localPath the file on disk. Checked for existence before anything is queued: v2
 *   discovers a missing file inside the transfer listener, which surfaces as a generic
 *   upload error rather than "the file is gone".
 * @param module which feature this belongs to, for grouping and for cancelling a
 *   screen's transfers when it closes.
 * @param linkedMessageId the chat row this file belongs to, so progress can be shown on
 *   the right bubble and a failure can mark the right message retryable.
 */
data class UploadRequest(
    val remoteKey: String,
    val localPath: String,
    val fileName: String,
    val module: String,
    val linkedMessageId: String? = null,
    val contentType: String? = null,
    /** Thumbnails ride along with their parent but are never shown as a transfer. */
    val isThumbnail: Boolean = false,
    val caption: String = "",
) {
    val file: File get() = File(localPath)
}

/**
 * A file coming down.
 *
 * @param remoteKey the object key, taken from the attachment on the message.
 * @param destinationPath where it lands. Chosen by the caller so a re-download of the
 *   same attachment is a cache hit rather than a second copy.
 */
data class DownloadRequest(
    val remoteKey: String,
    val destinationPath: String,
    val fileName: String,
    val module: String,
    val linkedMessageId: String? = null,
) {
    val file: File get() = File(destinationPath)
}

/**
 * Where a transfer has got to.
 *
 * A sealed hierarchy rather than an enum plus a loose `progress` field, for the same
 * reason as `SendState`: only one state carries a percentage and only one carries an
 * error, and this shape makes "50% and failed" unrepresentable.
 */
sealed interface TransferState {
    data object Queued : TransferState

    /** [fraction] is 0f..1f. */
    data class InProgress(
        val fraction: Float,
        val bytesTransferred: Long,
        val bytesTotal: Long,
    ) : TransferState

    data class Complete(val remoteKey: String, val localPath: String) : TransferState

    /**
     * @param retryable false for causes retrying cannot fix — a missing local file, or
     *   credentials the project does not have. Showing "Tap to retry" on those trains
     *   users to tap something that will never work.
     */
    data class Failed(
        val reason: String,
        val retryable: Boolean = true,
        val attempt: Int = 0,
    ) : TransferState

    data object Cancelled : TransferState
}

/** A transfer plus its live state, as the UI sees it. */
data class Transfer(
    val id: String,
    val module: String,
    val fileName: String,
    val remoteKey: String,
    val linkedMessageId: String?,
    val direction: Direction,
    val state: TransferState,
) {
    enum class Direction { UPLOAD, DOWNLOAD }

    val fraction: Float
        get() = when (state) {
            is TransferState.InProgress -> state.fraction
            is TransferState.Complete -> 1f
            else -> 0f
        }

    val isActive: Boolean
        get() = state is TransferState.Queued || state is TransferState.InProgress

    val isFailed: Boolean get() = state is TransferState.Failed

    val canRetry: Boolean get() = (state as? TransferState.Failed)?.retryable == true
}
