package com.zillit.zillitapp.core.storage

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zillit.zillitapp.core.attachment.PickedMedia
import com.zillit.zillitapp.core.chat.data.ChatIds
import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.PendingUploadEntity
import com.zillit.zillitapp.core.logging.ZillitLog
import dagger.hilt.android.qualifiers.ApplicationContext
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.Sort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The queue every outgoing message and attachment passes through.
 *
 * **Realm is the queue; WorkManager only drives it.** A row is written first and the worker
 * is scheduled second, so a send survives the process being killed between the two — the
 * row is still there on next launch and [resumeAll] picks it up. Holding the queue in
 * WorkManager's own data instead would lose everything the moment the work was cancelled.
 *
 * Rows are keyed by `unique_id`, the same id the chat API uses to de-duplicate, so a
 * message that is retried after a response was lost cannot post twice.
 */
@Singleton
class UploadQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val realmProvider: RealmProvider,
) {

    private val realm get() = realmProvider.realm

    /**
     * Queues one batch of attachments.
     *
     * @param folder the remote prefix, e.g. `home`. Combined with the project id and a
     *   sanitised file name to form the S3 key.
     * @param onRowsQueued called with the new ids **inside** the same suspend call, before
     *   the worker is scheduled, so the caller can render optimistic bubbles that already
     *   match the rows the worker will report progress against.
     * @return the queued ids, in the order given.
     */
    suspend fun enqueue(
        projectId: String,
        userId: String,
        module: String,
        scopeId: String,
        folder: String,
        media: List<PickedMedia>,
        replacePreviousChats: Boolean? = null,
        isDistributeAutomatic: Boolean = false,
        moduleName: String = "",
        onRowsQueued: (List<String>) -> Unit = {},
    ): List<String> {
        val ids = mutableListOf<String>()
        val now = System.currentTimeMillis()

        // One stamp for the whole batch: it is what groups the rows so a failure can offer
        // "retry all 5 files" rather than only the one that failed. See [pendingSiblings].
        val batchStamp = now

        realm.write {
            media.forEachIndexed { index, item ->
                val id = ChatIds.newUniqueId(userId)
                ids += id

                copyToRealm(
                    PendingUploadEntity().apply {
                        this.id = id
                        this.projectId = projectId
                        this.userId = userId
                        this.module = module
                        this.scopeId = scopeId
                        localPath = item.localPath
                        fileName = item.fileName
                        mimeType = item.mimeType
                        sizeBytes = item.sizeBytes
                        caption = item.caption
                        durationMs = item.durationMs
                        width = item.width
                        height = item.height
                        remoteKey = "$projectId/$folder/${item.fileName.storageSafe()}"
                        thumbnailLocalPath = item.thumbnailPath.orEmpty()
                        thumbnailRemoteKey = item.thumbnailPath
                            ?.let { "$projectId/$folder/thumbnail/${File(it).name.storageSafe()}" }
                            .orEmpty()
                        this.replacePreviousChats = replacePreviousChats
                        this.isDistributeAutomatic = isDistributeAutomatic
                        this.moduleName = moduleName
                        status = PendingUploadEntity.STATUS_QUEUED
                        messageGroup = batchStamp
                        // Offset by index so the batch keeps its picked order — several
                        // rows written in the same millisecond would otherwise sort
                        // arbitrarily and the bubbles would appear shuffled.
                        createdAt = now + index
                    },
                    UpdatePolicy.ALL,
                )
            }
        }

        onRowsQueued(ids)
        schedule(projectId, immediate = true)
        return ids
    }

    /**
     * Queues a plain text message.
     *
     * Goes through the same queue as attachments so retry, ordering and offline behaviour
     * are identical — v2 had a separate path for text, which is why a failed text message
     * behaved differently from a failed photo. Status starts at UPLOADING because there is
     * no file to transfer; only the post remains.
     */
    suspend fun enqueueTextMessage(
        id: String,
        projectId: String,
        userId: String,
        module: String,
        scopeId: String,
        text: String,
        replyToServerId: String = "",
    ) {
        realm.write {
            copyToRealm(
                PendingUploadEntity().apply {
                    this.id = id
                    this.projectId = projectId
                    this.userId = userId
                    this.module = module
                    this.scopeId = scopeId
                    caption = text
                    localPath = ""
                    mimeType = MIME_TEXT
                    this.replyToServerId = replyToServerId
                    status = PendingUploadEntity.STATUS_UPLOADING
                    createdAt = System.currentTimeMillis()
                },
                UpdatePolicy.ALL,
            )
        }
        schedule(projectId, immediate = true)
    }

    /**
     * Queues a forward — a message that already exists on the server.
     *
     * The file is not re-uploaded: the existing `remoteKey` is carried over and the row
     * starts as uploaded, so only the post to the target scope happens. Re-uploading would
     * duplicate the object and break the "same file" relationship the backend relies on.
     */
    suspend fun enqueueForward(
        projectId: String,
        userId: String,
        // Defaulted from userId, which is declared above it: a forward is a new message
        // and needs its own unique_id, but no caller has a meaningful one to pass.
        id: String = ChatIds.newUniqueId(userId),
        module: String,
        targetScopeId: String,
        remoteKey: String,
        thumbnailKey: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        durationMs: Long,
        width: Int,
        height: Int,
        caption: String,
        replacePreviousChats: Boolean? = null,
    ) {
        realm.write {
            copyToRealm(
                PendingUploadEntity().apply {
                    this.id = id
                    this.projectId = projectId
                    this.userId = userId
                    this.module = module
                    scopeId = targetScopeId
                    this.remoteKey = remoteKey
                    uploadedUrl = remoteKey
                    thumbnailRemoteKey = thumbnailKey
                    thumbnailUploaded = true
                    this.fileName = fileName
                    this.mimeType = mimeType
                    this.sizeBytes = sizeBytes
                    this.durationMs = durationMs
                    this.width = width
                    this.height = height
                    this.caption = caption
                    this.replacePreviousChats = replacePreviousChats
                    localPath = ""
                    status = PendingUploadEntity.STATUS_UPLOADING
                    createdAt = System.currentTimeMillis()
                },
                UpdatePolicy.ALL,
            )
        }
        schedule(projectId, immediate = true)
    }

    /**
     * Asks WorkManager to drain [projectId]'s queue.
     *
     * Unique work per project, so two sends do not spawn two workers racing on the same
     * rows. [immediate] REPLACEs the existing work — used right after enqueueing, when the
     * new row must be picked up now; otherwise KEEP lets an already-running drain finish.
     */
    fun schedule(projectId: String, immediate: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("project_id" to projectId))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(projectId),
            if (immediate) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Reschedules every project that still has unfinished rows. Call on app start.
     *
     * This is what makes a send survive the app being killed mid-upload: the row outlived
     * the process, and nothing else would ever look at it again.
     */
    suspend fun resumeAll() {
        val projects = realm.query<PendingUploadEntity>("status != $0", PendingUploadEntity.STATUS_DONE)
            .find()
            .map { it.projectId }
            .distinct()

        if (projects.isEmpty()) return

        ZillitLog.d(TAG, "Resuming uploads for ${projects.size} project(s)")
        projects.forEach { schedule(it) }
    }

    /** Unfinished rows for one project, oldest first. */
    fun observePending(projectId: String): Flow<List<PendingUploadEntity>> =
        realm.query<PendingUploadEntity>(
            "projectId == $0 AND status != $1", projectId, PendingUploadEntity.STATUS_DONE,
        )
            .sort("createdAt", Sort.ASCENDING)
            .asFlow()
            .map { it.list.toList() }

    /** Unfinished rows across every project — for a global "sending…" indicator. */
    fun observeAllPending(): Flow<List<PendingUploadEntity>> =
        realm.query<PendingUploadEntity>("status != $0", PendingUploadEntity.STATUS_DONE)
            .sort("createdAt", Sort.ASCENDING)
            .asFlow()
            .map { it.list.toList() }

    /**
     * Puts one failed row back in the queue.
     *
     * A row whose file already uploaded resumes at UPLOADING rather than QUEUED, so the
     * retry posts the message instead of transferring the bytes a second time.
     */
    suspend fun retry(id: String) {
        val row = realm.write {
            query<PendingUploadEntity>("id == $0", id).first().find()?.apply {
                status = if (uploadedUrl.isBlank()) {
                    PendingUploadEntity.STATUS_QUEUED
                } else {
                    PendingUploadEntity.STATUS_UPLOADING
                }
                attempts = 0
                lastError = ""
            }
        } ?: return

        schedule(row.projectId, immediate = true)
    }

    /**
     * The other unfinished rows sent in the same batch as [id].
     *
     * Backs the "this file / all N files" retry prompt: one failure in a five-photo send
     * usually means the whole batch needs another go.
     */
    fun pendingSiblings(id: String): List<String> {
        val row = realm.query<PendingUploadEntity>("id == $0", id).first().find() ?: return emptyList()
        if (row.messageGroup == 0L) return emptyList()

        return realm.query<PendingUploadEntity>(
            "projectId == $0 AND scopeId == $1 AND messageGroup == $2 AND status != $3",
            row.projectId, row.scopeId, row.messageGroup, PendingUploadEntity.STATUS_DONE,
        ).find().map { it.id }
    }

    suspend fun retryAll(ids: List<String>) {
        ids.forEach { retry(it) }
    }

    /** Drops one row — the user abandoned the send. */
    suspend fun cancel(id: String) {
        realm.write { delete(query<PendingUploadEntity>("id == $0", id).find()) }
    }

    suspend fun clearCompleted() {
        realm.write {
            delete(query<PendingUploadEntity>("status == $0", PendingUploadEntity.STATUS_DONE).find())
        }
    }

    suspend fun clearFailed() {
        realm.write {
            delete(query<PendingUploadEntity>("status == $0", PendingUploadEntity.STATUS_FAILED).find())
        }
    }

    /** Strips anything that would break an S3 object key. */
    private fun String.storageSafe(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        /** Marks a queue row that carries a message but no file. */
        const val MIME_TEXT = "text/plain"

        fun workName(projectId: String) = "upload-$projectId"

        private const val TAG = "UploadQueue"
        private const val BACKOFF_SECONDS = 15L
    }
}
