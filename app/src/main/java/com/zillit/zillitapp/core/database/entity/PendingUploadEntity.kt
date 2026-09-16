package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * A queued attachment: upload the file, then post the chat message that references it.
 *
 * **Persisted, not held in memory.** An upload has to survive the app being swiped away,
 * the process being killed for memory, and the device rebooting — a 400 MB rushes file on
 * hotel wifi will outlive all three. A coroutine on an application scope survives none of
 * them, which is why this row exists and why [UploadWorker] drives it rather than a
 * `launch`.
 *
 * **Every row carries its own project.** This is the part that is easy to get wrong: the
 * request signer normally reads the *active* project, so an upload queued for project A
 * and still running after the user switches to project B would sign with B's moduledata
 * and post the message into the wrong project. [projectId] and [userId] are captured at
 * enqueue time and used for both the S3 credentials and the chat POST, so a hundred files
 * uploading for A and a hundred for B stay entirely separate.
 */
class PendingUploadEntity : RealmObject {

    /** Client-generated; also the chat message's `unique_id`. */
    @PrimaryKey
    var id: String = ""

    // -- project context, captured at enqueue and never re-read from the session --

    @Index
    var projectId: String = ""

    var userId: String = ""

    /** Which chat surface the message belongs to — "home", "catering", … */
    var module: String = ""

    /** Unit id for Home; tool or thread id elsewhere. */
    var scopeId: String = ""

    // -- the file --

    var localPath: String = ""
    var fileName: String = ""
    var mimeType: String = ""
    var remoteKey: String = ""
    var sizeBytes: Long = 0

    /** Per-file text, as v2's `description`. */
    var caption: String = ""

    var durationMs: Long = 0
    var width: Int = 0
    var height: Int = 0

    // -- Call Sheet only --

    /** Tells the backend to move what is posted to History and replace it. */
    var replacePreviousChats: Boolean? = null
    var isDistributeAutomatic: Boolean = false
    var moduleName: String = ""

    // -- progress --

    /**
     * 0 queued · 1 uploading · 2 uploaded, posting · 3 done · -1 failed.
     *
     * Uploaded-but-not-posted is its own state because the two halves fail independently:
     * a file that reached S3 must never be re-uploaded just because the chat POST timed
     * out.
     */
    var status: Int = STATUS_QUEUED

    var progress: Float = 0f

    var attempts: Int = 0

    var lastError: String = ""

    var createdAt: Long = 0

    /**
     * Ties the rows of one multi-file send together.
     *
     * v2's `message_group`, stamped once per [UploadQueue.enqueue] call. Used to offer
     * "retry this file" or "retry all N" after a failure, and mirrored onto the chat rows
     * so the thread renders the batch as a single post.
     */
    var messageGroup: Long = 0

    /** Set once the file is on storage, so a retry resumes at the POST. */
    var uploadedUrl: String = ""

    /**
     * The preview that goes up alongside the file.
     *
     * The backend rejects an image or video attachment posted without one
     * (`unit_chat_attachment_thumbnail_required`), so this is part of the transfer rather
     * than something generated later. Blank for types with nothing to preview.
     */
    /**
     * Set when this row is a **reply** rather than a new message.
     *
     * Holds the parent's server id. The worker posts to `chat/comments/{id}` instead of
     * `chat`, so replies get the same durability as everything else — queued offline,
     * retried with backoff, and scoped to the project they were written in.
     */
    var replyToServerId: String = ""

    var thumbnailLocalPath: String = ""
    var thumbnailRemoteKey: String = ""
    var thumbnailUploaded: Boolean = false

    /**
     * How the message is delivered once its bytes are up.
     *
     * A unit chat POSTs; a socket chat emits and waits for the acknowledgement. The upload
     * half is identical either way, which is the whole reason both use this one queue rather
     * than a second one that would need its own retry, ordering and process-death handling.
     *
     * [DELIVERY_UNIT] or [DELIVERY_SOCKET].
     */
    var deliveryKind: String = DELIVERY_UNIT

    /** Socket delivery only: whether [scopeId] names a room rather than a person. */
    var isGroup: Boolean = false

    /** Socket delivery only: the recipient's device, which the server routes on. */
    var receiverDeviceId: String = ""

    /**
     * The pin, when this upload is a location.
     *
     * A location message travels as an image **plus** coordinates: the snapshot is the
     * attachment and these ride alongside it. Zero when the row is an ordinary file.
     */
    var locationLat: Double = 0.0
    var locationLng: Double = 0.0
    var locationAddress: String = ""

    companion object {
        /** Posted to the chat endpoint — Home and every tool chat. */
        const val DELIVERY_UNIT = "unit"

        /** Emitted on the socket and confirmed by its ack — C&C and Budget. */
        const val DELIVERY_SOCKET = "socket"

        const val STATUS_FAILED = -1
        const val STATUS_QUEUED = 0
        const val STATUS_UPLOADING = 1
        const val STATUS_UPLOADED = 2
        const val STATUS_DONE = 3
    }
}
