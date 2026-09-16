package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.ext.realmListOf
import io.realm.kotlin.types.EmbeddedRealmObject
import io.realm.kotlin.types.RealmList
import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * One socket-chat message — C&C or Budget.
 *
 * ### Why this is not [ChatMessageEntity]
 * A unit chat and a socket chat are different products that happen to look alike. They
 * differ where it counts for storage:
 *
 *  - **Addressing.** A unit message belongs to a *unit*; this one has a `sender` and a
 *    `receiver`, and the receiver is either a person or a room.
 *  - **Status.** Their status columns use the same numbers for different things. A unit
 *    message is `-1` failed, `0` queued, `1` uploading, `2` confirmed. A socket message is
 *    `0` pending, `1` sent, `2` delivered, `3` read — the server echoes these and they
 *    drive the ticks. Sharing one column would silently mean "uploading" on one surface
 *    and "sent" on the other.
 *  - **Delivery.** A unit message is a POST. This one is an emit whose acknowledgement is
 *    the only proof it landed, so the row has to survive with no server id at all.
 *
 * They share how a message is *rendered*, and that is shared — one `ChatThread`, one set of
 * bubbles. Storage is separate on purpose.
 *
 * ### Keying
 * `unique_id` is generated on this device before the send and never changes, so the
 * optimistic row and the acknowledged row are the same row. The primary key namespaces it
 * by project and surface, because Budget and C&C can both be open on the same project.
 */
class CncMessageEntity : RealmObject {

    @PrimaryKey
    var id: String = ""

    /** [com.zillit.zillitapp.feature.cnc.data.ChatSurface] key — "cnc" or "budget". */
    @Index
    var surface: String = ""

    @Index
    var projectId: String = ""

    /**
     * The other side: the person's user id for a direct message, the room id for a group.
     *
     * Indexed because every thread read is "messages for this conversation, newest last",
     * and the recent list asks the same question forty times over.
     */
    @Index
    var conversationId: String = ""

    var isGroup: Boolean = false

    /** Client-generated, stable across the optimistic → acknowledged transition. */
    @Index
    var uniqueId: String = ""

    /** Server id. Empty until the acknowledgement arrives; needed to reply, edit or delete. */
    var serverId: String = ""

    var senderId: String = ""
    var receiverId: String = ""

    /**
     * Which device the message was addressed to.
     *
     * The server routes on it and echoes it back. Kept so a message this device sent from
     * another session is recognisable rather than appearing twice.
     */
    var receiverDeviceId: String = ""
    var senderDeviceId: String = ""

    /** `cnc_section`, `main_budget_tool` or `department_budget_tool`. */
    var chatTool: String = ""

    /** Budget only; empty on C&C. Both travel on every emit, so both are stored. */
    var departmentId: String = ""
    var budgetDocumentId: String = ""

    var message: String = ""
    var messageTranslation: String = ""

    /** `text`, `image`, `video`, `audio`, `document`, `location`, `attachemnt`, … */
    var messageType: String = "text"

    /**
     * `0` pending · `1` sent · `2` delivered · `3` read.
     *
     * The server's own numbering, echoed back on read receipts. Never renumber it.
     */
    var status: Int = STATUS_PENDING

    /**
     * How many people have read this message, as the server reported it.
     *
     * A count rather than the ids: the list only ever renders "Read by N", and the sheet
     * that names them fetches the names for one message when it opens. The count arrives
     * on the message itself, because there is no endpoint that answers it for a whole room.
     */
    var readByCount: Int = 0

    /** 0f..1f while an attachment is uploading. Local only. */
    var uploadProgress: Float = 0f

    /** Groups files picked in one action into a single bubble. */
    var messageGroup: Long = 0

    var created: Long = 0
    var updated: Long = 0
    var edited: Long = 0

    /** Soft delete. Rows are kept so an older page cannot resurrect them. */
    var deleted: Long = 0

    /** When the server expired it under a retention rule. Filtered out like a delete. */
    var expired: Long = 0

    /** Non-zero once filed into the group's archive. */
    var archived: Long = 0

    var isTranslated: Boolean = false

    /** True once confirmed by a history fetch rather than only by a socket event. */
    var isFromApiCall: Boolean = false

    // -- content ---------------------------------------------------------------

    /**
     * Files on this message.
     *
     * A list even for the common single-file case: the wire carries both `attachment` and
     * `attachments[]`, and normalising to one shape here means the renderer never has to
     * ask which of the two a given message used.
     */
    var attachments: RealmList<CncAttachmentEntity> = realmListOf()

    var locationLatitude: Double = 0.0
    var locationLongitude: Double = 0.0
    var locationAddress: String = ""
    var locationName: String = ""

    var reply: CncReplyEntity? = null

    var reactions: RealmList<CncReactionEntity> = realmListOf()

    /** `{search, replacer}` substitutions for server-composed text. */
    var messageElements: RealmList<CncMessageElementEntity> = realmListOf()

    /** The complete server object, for fields without a typed column. */
    var rawJson: String = ""

    /** True while this row has never been successfully emitted. */
    val isPending: Boolean get() = status == STATUS_PENDING

    /** Deleted and expired messages are both simply gone from the thread. */
    val isVisible: Boolean get() = deleted == 0L && expired == 0L

    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_DELIVERED = 2
        const val STATUS_READ = 3

        fun key(surface: String, projectId: String, uniqueId: String) =
            "$surface:$projectId:$uniqueId"
    }
}

/** A file on a socket-chat message. */
class CncAttachmentEntity : EmbeddedRealmObject {
    /** Object key in the project's bucket, not a URL. */
    var media: String = ""
    var name: String = ""
    var thumbnail: String = ""
    var contentType: String = ""
    var contentSubtype: String = ""
    var caption: String = ""
    var fileSize: String = ""
    var duration: Long = 0
    var width: Long = 0
    var height: Long = 0
    var bucket: String = ""
    var region: String = ""

    /** Local copies while the upload is still in flight. */
    var localFilePath: String = ""
    var localThumbnailPath: String = ""
}

/**
 * The message being replied to, copied onto the reply.
 *
 * Denormalised rather than a link: the quoted bar has to render even when the original is
 * older than the page currently loaded, and a link would leave it blank.
 */
class CncReplyEntity : EmbeddedRealmObject {
    var messageId: String = ""
    var senderId: String = ""
    var message: String = ""
    var messageTranslation: String = ""
    var messageType: String = "text"
    var created: Long = 0
    var attachmentMedia: String = ""
    var attachmentName: String = ""
    var attachmentThumbnail: String = ""
}

/** One person's reaction. One per person; a second replaces the first. */
class CncReactionEntity : EmbeddedRealmObject {
    var userId: String = ""
    var reaction: String = ""
    var updated: Long = 0
}

/** A `{search, replacer}` pair for server-composed message text. */
class CncMessageElementEntity : EmbeddedRealmObject {
    var search: String = ""
    var replacer: String = ""
}
