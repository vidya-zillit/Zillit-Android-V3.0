package com.zillit.zillitapp.core.storage

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zillit.zillitapp.core.chat.data.ChatAttachmentDto
import com.zillit.zillitapp.core.chat.data.ChatLocationDto
import com.zillit.zillitapp.core.chat.data.ChatModule
import com.zillit.zillitapp.core.chat.data.ChatRepository
import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.feature.cnc.data.CncAttachmentSend
import com.zillit.zillitapp.feature.cnc.data.CncRepository
import com.zillit.zillitapp.core.database.entity.PendingUploadEntity
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.session.SessionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.Sort
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Uploads one project's queued attachments, then posts each as a chat message.
 *
 * Runs under WorkManager so it survives the app being closed, the process being killed
 * and the device rebooting, and so it waits for a connection rather than failing without
 * one. This is what makes "send now, it goes when it can" true rather than aspirational.
 *
 * **Scoped to one project.** The worker is given a `projectId` and touches only that
 * project's rows, and every request it makes is stamped with the project captured when
 * the row was queued — not with whatever project the user is looking at now. That is what
 * keeps 100 files uploading for project A separate from 100 queued afterwards for B.
 *
 * The two halves are tracked separately on purpose: a file that reached storage is never
 * re-uploaded because the chat POST failed. A retry resumes at the POST.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val realmProvider: RealmProvider,
    private val s3: S3Client,
    private val chatRepository: ChatRepository,
    private val cncRepository: CncRepository,
    private val credentials: StorageCredentialsStore,
) : CoroutineWorker(appContext, params) {

    private val realm get() = realmProvider.realm

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID)
            ?: return Result.failure()

        val pending = realm.query<PendingUploadEntity>(
            "projectId == $0 AND status != $1 AND status != $2",
            projectId,
            PendingUploadEntity.STATUS_DONE,
            PendingUploadEntity.STATUS_FAILED,
        ).sort("createdAt", Sort.ASCENDING).find().map { it.id }

        if (pending.isEmpty()) return Result.success()

        ZillitLog.d(TAG, "Processing ${pending.size} upload(s) for $projectId")

        var anyFailed = false

        // The two phases are deliberately different shapes.
        //
        // Getting the bytes up is the slow part and nothing about it depends on order, so
        // it runs several at a time — picking ten photos used to upload them strictly one
        // after another, which is most of why sending a batch felt slow. The limit is low
        // on purpose: unbounded transfers on a phone starve each other and finish no
        // sooner.
        //
        // Posting is the opposite. The POST is what decides where a message lands in the
        // thread, so it stays sequential and in the order the user picked, whatever order
        // the uploads happened to finish in.
        val snapshots = pending.mapNotNull { id ->
            realm.query<PendingUploadEntity>("id == $0", id).first().find()?.snapshot()
        }

        val uploaded: Set<String> = coroutineScope {
            val gate = Semaphore(MAX_PARALLEL_UPLOADS)
            snapshots.map { snapshot ->
                async {
                    gate.withPermit {
                        val ok = when {
                            isStopped -> false
                            // Nothing to upload: a text message, or a forward whose bytes
                            // are already on storage.
                            snapshot.localPath.isBlank() -> true
                            // Already on storage from an earlier attempt — resume at the POST.
                            snapshot.uploadedUrl.isNotBlank() -> true
                            else -> uploadFile(snapshot)
                        }
                        snapshot.id.takeIf { ok }
                    }
                }
            }.awaitAll().filterNotNull().toSet()
        }

        if (isStopped) return Result.retry()

        for (snapshot in snapshots) {
            if (isStopped) return Result.retry()

            if (snapshot.id !in uploaded) {
                anyFailed = true
                continue
            }

            // Re-read: the upload phase wrote the remote keys this POST has to carry.
            val posted = realm.query<PendingUploadEntity>("id == $0", snapshot.id)
                .first().find()?.snapshot() ?: snapshot

            if (!postMessage(posted)) anyFailed = true
        }

        // Retry rather than failure: WorkManager re-runs with backoff and the rows that
        // already completed are skipped. Rows that exhausted their attempts are marked
        // FAILED and excluded from the query, so one bad file cannot keep the worker
        // retrying forever — it stops blocking and waits for a manual retry.
        return if (anyFailed) Result.retry() else Result.success()
    }

    private suspend fun uploadFile(row: UploadSnapshot): Boolean {
        setStatus(row.id, PendingUploadEntity.STATUS_UPLOADING)

        // Thumbnail first. The message references it, so posting before it exists would
        // give every client a broken preview — and the backend rejects the post outright.
        if (row.thumbnailLocalPath.isNotBlank() && !row.thumbnailUploaded) {
            val thumbUploaded = uploadOne(
                UploadRequest(
                    remoteKey = row.thumbnailRemoteKey,
                    localPath = row.thumbnailLocalPath,
                    fileName = File(row.thumbnailLocalPath).name,
                    module = row.module,
                    linkedMessageId = row.id,
                    contentType = "image/jpeg",
                    isThumbnail = true,
                ),
                onProgress = null,
            )
            if (thumbUploaded == null) {
                markFailed(row.id, "Thumbnail upload failed", retryable = true)
                return false
            }
            markThumbnailUploaded(row.id)
        }

        val request = UploadRequest(
            remoteKey = row.remoteKey,
            localPath = row.localPath,
            fileName = row.fileName,
            module = row.module,
            linkedMessageId = row.id,
            contentType = row.mimeType,
        )

        val completed = uploadOne(request) { fraction ->
            publishProgress(row, fraction)
        }
            ?: run {
                markFailed(row.id, "Upload failed", retryable = true)
                return false
            }

        markUploaded(row.id, completed)
        return true
    }

    /**
     * Runs one transfer to completion.
     *
     * @return the remote key on success, null on failure or cancellation. Shared by the
     *   file and its thumbnail so both behave identically — including the progress
     *   reporting, which the thumbnail simply opts out of.
     */
    private suspend fun uploadOne(
        request: UploadRequest,
        onProgress: (suspend (Float) -> Unit)?,
    ): String? {
        var lastState: TransferState? = null

        // Collected until the transfer reaches a terminal state; progress is written to
        // Realm on the way so the bubble's percentage survives the screen being closed.
        s3.upload(request)
            .transformWhile { state ->
                emit(state)
                state !is TransferState.Complete &&
                    state !is TransferState.Failed &&
                    state !is TransferState.Cancelled
            }
            .collect { state ->
                lastState = state
                if (state is TransferState.InProgress) onProgress?.invoke(state.fraction)
            }

        return (lastState as? TransferState.Complete)?.remoteKey
    }

    private suspend fun markThumbnailUploaded(id: String) {
        realm.write {
            query<PendingUploadEntity>("id == $0", id).first().find()?.thumbnailUploaded = true
        }
    }

    private suspend fun postMessage(row: UploadSnapshot): Boolean {
        // A socket chat has no POST: the message is emitted and the acknowledgement is the
        // only confirmation. Everything before this point — the transfer, the retries, the
        // ordering, surviving the process being killed — is identical, which is why the two
        // kinds share this queue instead of C&C growing one of its own.
        if (row.deliveryKind == PendingUploadEntity.DELIVERY_SOCKET) {
            return if (cncRepository.deliverAttachment(row.toCncAttachmentSend())) {
                setStatus(row.id, PendingUploadEntity.STATUS_DONE)
                true
            } else {
                markFailed(row.id, "Socket send not acknowledged", retryable = true)
                false
            }
        }

        val module = ChatModule.entries.firstOrNull { it.key == row.module } ?: ChatModule.HOME
        val project = SessionStore.ActiveProject(projectId = row.projectId, userId = row.userId)

        // A reply goes to the comments endpoint; everything else is a new message.
        if (row.replyToServerId.isNotBlank()) {
            return when (
                chatRepository.addReply(
                    module = module,
                    project = project,
                    scopeId = row.scopeId,
                    chatServerId = row.replyToServerId,
                    text = row.caption,
                )
            ) {
                is ApiResult.Success -> {
                    setStatus(row.id, PendingUploadEntity.STATUS_DONE)
                    true
                }

                is ApiResult.Failure -> {
                    markFailed(row.id, "Reply failed", retryable = true)
                    false
                }
            }
        }

        val result = chatRepository.sendMessage(
            module = module,
            // The project captured at enqueue time, NOT the active one. Without this a
            // file queued for project A and posted after a switch lands in project B.
            project = project,
            scopeId = row.scopeId,
            senderId = row.userId,
            text = row.caption,
            messageType = row.messageType,
            // Text rows carry no attachment at all — an empty one would make the
            // backend treat the message as a file with no media. A forward has no local
            // file but does have a key, so it still sends one.
            attachment = if (row.localPath.isBlank() && row.remoteKey.isBlank()) {
                null
            } else {
                ChatAttachmentDto(
                    media = row.uploadedUrl.ifBlank { row.remoteKey },
                    name = row.fileName,
                    fileSize = row.sizeBytes.toString(),
                    // Always present, empty when there is nothing to preview.
                    //
                    // The backend requires the key even for audio, which has no thumbnail
                    // at all (`unit_chat_attachment_thumbnail_required`), and
                    // `encodeDefaults = false` drops a null — so an empty string is the
                    // only way to say "no preview" rather than "field missing".
                    thumbnail = row.thumbnailRemoteKey,
                    // Category + extension, not the MIME type — see ChatAttachmentDto.
                    contentType = row.chatMessageType,
                    // Same rule for the rest: sent as empty rather than omitted.
                    contentSubtype = row.fileName.substringAfterLast('.', "").lowercase(),
                    bucket = credentials.current.uploadBucket,
                    region = credentials.current.region,
                    // Always sent, never omitted: the backend rejects an attachment with
                    // no caption field at all (`unit_chat_attachment_caption_required`),
                    // and `encodeDefaults = false` drops a null. A single space is what v2
                    // sends for an uncaptioned file.
                    caption = row.caption.ifBlank { " " },
                    uniqueId = row.remoteKey,
                    // Always sent, even as 0. `encodeDefaults = false` drops a null, and
                    // the backend requires the keys to be present
                    // (`unit_chat_attachment_duration_required`) — a still image simply
                    // has a duration of zero.
                    duration = row.durationMs,
                    width = row.width,
                    height = row.height,
                )
            },
            // The pin, when this row is a location. Without it the backend has an image
            // and no coordinates, and every client draws a picture nobody can open.
            location = row.locationAddress.takeIf { it.isNotBlank() }?.let {
                ChatLocationDto(
                    latitude = row.locationLat,
                    longitude = row.locationLng,
                    address = it,
                )
            },
            replacePreviousChats = row.replacePreviousChats,
            isDistributeAutomatic = row.isDistributeAutomatic.takeIf { it },
            moduleName = row.moduleName.takeIf { it.isNotBlank() },
            uniqueId = row.id,
        )

        return when (result) {
            is ApiResult.Success -> {
                setStatus(row.id, PendingUploadEntity.STATUS_DONE)
                true
            }

            is ApiResult.Failure -> {
                markFailed(row.id, result.error.message, retryable = true)
                false
            }
        }
    }

    private suspend fun setStatus(id: String, status: Int) {
        realm.write {
            query<PendingUploadEntity>("id == $0", id).first().find()?.status = status
        }
    }

    /**
     * Last written fraction and time per row, for the throttle below.
     *
     * A worker instance handles one project's batch and then dies, so this never outlives
     * the transfers it describes.
     */
    private val lastProgressWrite = java.util.concurrent.ConcurrentHashMap<String, Pair<Float, Long>>()

    /**
     * Move the percentage along — on the queue row and on the chat bubble — but not on
     * every callback.
     *
     * S3 reports progress per chunk, which on a fast connection is dozens of times a
     * second, and each report was writing Realm **twice**: once here and once on the chat
     * message the open thread is watching. Every one of those wakes the queries behind the
     * thread and the chat list, so a batch upload spent its time recomposing instead of
     * scrolling. Nobody can read a number that changes fifty times a second anyway.
     *
     * Both writes are gated together, because throttling one and not the other just leaves
     * the expensive one running.
     *
     * Terminal values do not come through here: `markUploaded` writes 1f directly, so a bar
     * always finishes even when the last tick is dropped.
     */
    private suspend fun publishProgress(row: UploadSnapshot, fraction: Float) {
        val now = System.currentTimeMillis()
        val previous = lastProgressWrite[row.id]

        if (previous != null &&
            fraction - previous.first < PROGRESS_STEP &&
            now - previous.second < PROGRESS_INTERVAL_MS
        ) {
            return
        }

        lastProgressWrite[row.id] = fraction to now

        realm.write {
            query<PendingUploadEntity>("id == $0", row.id).first().find()?.progress = fraction
        }

        chatRepository.updateUploadProgress(
            module = ChatModule.entries.firstOrNull { it.key == row.module } ?: ChatModule.HOME,
            projectId = row.projectId,
            scopeId = row.scopeId,
            uniqueId = row.id,
            fraction = fraction,
        )
    }

    private suspend fun markUploaded(id: String, url: String) {
        realm.write {
            query<PendingUploadEntity>("id == $0", id).first().find()?.apply {
                uploadedUrl = url
                progress = 1f
                status = PendingUploadEntity.STATUS_UPLOADED
            }
        }
    }

    private suspend fun markFailed(id: String, reason: String, retryable: Boolean) {
        realm.write {
            query<PendingUploadEntity>("id == $0", id).first().find()?.apply {
                attempts += 1
                lastError = reason
                // A non-retryable cause, or too many attempts, becomes a terminal failure
                // the user can act on — anything else stays queued for the next pass.
                status = if (!retryable || attempts >= MAX_ATTEMPTS) {
                    PendingUploadEntity.STATUS_FAILED
                } else {
                    PendingUploadEntity.STATUS_QUEUED
                }
            }
        }
        ZillitLog.w(TAG, "Upload $id failed: $reason")
    }

    /**
     * A detached copy of the row.
     *
     * Realm objects are live and tied to their thread; reading one across the suspending
     * upload would either throw or observe a value that changed underneath.
     */
    private fun PendingUploadEntity.snapshot() = UploadSnapshot(
        id = id,
        projectId = projectId,
        userId = userId,
        module = module,
        scopeId = scopeId,
        localPath = localPath,
        fileName = fileName,
        mimeType = mimeType,
        remoteKey = remoteKey,
        sizeBytes = sizeBytes,
        caption = caption,
        durationMs = durationMs,
        width = width,
        height = height,
        replacePreviousChats = replacePreviousChats,
        isDistributeAutomatic = isDistributeAutomatic,
        moduleName = moduleName,
        replyToServerId = replyToServerId,
        uploadedUrl = uploadedUrl,
        thumbnailLocalPath = thumbnailLocalPath,
        thumbnailRemoteKey = thumbnailRemoteKey,
        thumbnailUploaded = thumbnailUploaded,
        deliveryKind = deliveryKind,
        isGroup = isGroup,
        receiverDeviceId = receiverDeviceId,
        messageGroup = messageGroup,
        locationLat = locationLat,
        locationLng = locationLng,
        locationAddress = locationAddress,
    )

    /** The queue row as the socket repository needs it. */
    private fun UploadSnapshot.toCncAttachmentSend() = CncAttachmentSend(
        surfaceKey = module,
        uniqueId = id,
        projectId = projectId,
        senderId = userId,
        conversationId = scopeId,
        isGroup = isGroup,
        receiverDeviceId = receiverDeviceId,
        caption = caption,
        messageType = messageType,
        messageGroup = messageGroup,
        media = uploadedUrl.ifBlank { remoteKey },
        thumbnail = thumbnailRemoteKey,
        fileName = fileName,
        fileSize = sizeBytes.toString(),
        mimeType = mimeType,
        durationMs = durationMs,
        width = width,
        height = height,
        replyToServerId = replyToServerId,
    )

    private data class UploadSnapshot(
        val id: String,
        val projectId: String,
        val userId: String,
        val module: String,
        val scopeId: String,
        val localPath: String,
        val fileName: String,
        val mimeType: String,
        val remoteKey: String,
        val sizeBytes: Long,
        val caption: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val replacePreviousChats: Boolean?,
        val isDistributeAutomatic: Boolean,
        val moduleName: String,
        val replyToServerId: String,
        val uploadedUrl: String,
        val thumbnailLocalPath: String,
        val thumbnailRemoteKey: String,
        val thumbnailUploaded: Boolean,
        val deliveryKind: String,
        val isGroup: Boolean,
        val receiverDeviceId: String,
        val messageGroup: Long,
        val locationLat: Double,
        val locationLng: Double,
        val locationAddress: String,
    ) {
        /** Whether this row is a shared pin rather than an ordinary file. */
        val isLocation: Boolean get() = locationAddress.isNotBlank()

        /**
         * The attachment's category — what the wire calls `content_type`.
         *
         * Derived from the MIME, which is right for the attachment itself even when the
         * message is a location: v2 sends a pin as `content_type = "image"` under
         * `message_type = "location"`, because the snapshot *is* a picture.
         */
        val chatMessageType: String
            get() = when {
                // A forward has no local file but does have an object key — the type comes
                // from the mime as usual. Only a row with neither is a text message.
                localPath.isBlank() && remoteKey.isBlank() -> "text"
                mimeType.startsWith("image/") -> "image"
                mimeType.startsWith("video/") -> "video"
                mimeType.startsWith("audio/") -> "audio"
                // See PickedMedia.chatMessageType — "doc" is rejected by the backend.
                else -> "document"
            }

        /**
         * The message's own type — what the wire calls `message_type`.
         *
         * A pin is `location`, not `image`: sent as an image it lands as a picture with
         * coordinates nobody reads, so no client offers to open it in a maps app and the
         * optimistic row on this device is swapped for an image bubble once the server
         * echoes it back.
         */
        val messageType: String
            get() = if (isLocation) MESSAGE_TYPE_LOCATION else chatMessageType
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"

        private const val TAG = "UploadWorker"
        private const val MAX_ATTEMPTS = 5

        /** The wire's type for a shared pin. Same string on every chat surface. */
        private const val MESSAGE_TYPE_LOCATION = "location"

        /**
         * How many files go up at once.
         *
         * Three rather than one so a batch of photos finishes in roughly a third of the
         * time, and three rather than ten because a phone's uplink is the bottleneck —
         * past this they only slow each other down and each individual bar crawls.
         */
        private const val MAX_PARALLEL_UPLOADS = 3

        /** Write the percentage when it has moved this far… */
        private const val PROGRESS_STEP = 0.02f

        /** …or when this long has passed, whichever comes first. */
        private const val PROGRESS_INTERVAL_MS = 200L
    }
}
