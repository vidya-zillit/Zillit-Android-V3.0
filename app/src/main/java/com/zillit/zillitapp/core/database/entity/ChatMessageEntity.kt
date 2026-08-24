package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.ext.realmListOf
import io.realm.kotlin.types.EmbeddedRealmObject
import io.realm.kotlin.types.RealmList
import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * One chat message, for **every** chat in the app.
 *
 * v2 has a separate Realm model and DB manager per chat surface (`HomeChatModelDB`,
 * plus the account / confidential / info / catering / form / production-report copies),
 * all with the same columns and the same bugs fixed at different times. The endpoints
 * only differ by base URL — `{base}/chat`, `{base}/chat/comments/{id}`,
 * `{base}/chat/delete/chats` — so one table with a [module] discriminator serves all of
 * them.
 *
 * Keyed on `module:projectId:scopeId:uniqueId`:
 *  - `uniqueId` is the client-generated id, present before the server has assigned `_id`.
 *    Keying on the server id would make an optimistic row impossible to update in place
 *    when the POST response arrives, which is what produces duplicated messages.
 *  - `scopeId` is the unit id for Home, or the tool/thread id elsewhere.
 */
class ChatMessageEntity : RealmObject {

    @PrimaryKey
    var id: String = ""

    /** Which chat surface this belongs to — "home", "catering", "confidential"… */
    @Index
    var module: String = ""

    @Index
    var projectId: String = ""

    /** Unit id for Home; tool or thread id for the other chats. */
    @Index
    var scopeId: String = ""

    /** Client-generated, stable across the optimistic → confirmed transition. */
    var uniqueId: String = ""

    /** Server id. Empty until the POST comes back; required to reply or delete. */
    var serverId: String = ""

    var senderId: String = ""

    /** Decrypted at write time — see ChatRepository for why. */
    var message: String = ""
    var messageTranslation: String = ""

    /** "text", "image", "video", "doc", "audio", "attachment", "location". */
    var messageType: String = "text"

    /**
     * v2's status, kept verbatim because the server echoes it:
     * 0 queued locally · 1 uploading · 2 confirmed by server · -1 failed.
     */
    var status: Int = STATUS_QUEUED

    /** 0f..1f while [status] is [STATUS_UPLOADING]. Local only. */
    var uploadProgress: Float = 0f

    /** Groups attachments posted together into one bubble, as in v2. */
    var messageGroup: Long = 0

    var created: Long = 0
    var updated: Long = 0

    /**
     * Soft delete. Rows are kept rather than removed so a later `previous` page cannot
     * resurrect them, but they are filtered out of every read — v3 shows no
     * "Message Deleted" placeholder.
     */
    var deleted: Long = 0

    var edited: Long = 0

    /**
     * True once Translate has been run on this message.
     *
     * Local only — the server never sends it. It both hides the option (translating twice
     * is pointless) and tells the renderer that `message` now holds the composed
     * original-plus-translation while `messageTranslation` holds what was originally said.
     */
    var isTranslated: Boolean = false

    /** True once the row has been confirmed by a list fetch, not just a socket event. */
    var isFromApiCall: Boolean = false

    // -- attachment ------------------------------------------------------------

    var attachmentUrl: String? = null
    var attachmentName: String? = null
    var attachmentSize: String? = null
    var attachmentThumbnail: String? = null
    var attachmentContentType: String? = null
    var attachmentDuration: Long = 0
    var attachmentWidth: Int = 0
    var attachmentHeight: Int = 0

    /** Local file path while uploading, so the bubble can render before the URL exists. */
    var localFilePath: String? = null

    // -- location --------------------------------------------------------------

    var locationLatitude: Double = 0.0
    var locationLongitude: Double = 0.0
    var locationAddress: String? = null

    // -- replies ---------------------------------------------------------------

    /**
     * v2's `comments`. Embedded rather than a separate table with a foreign key: a reply
     * has no life of its own, and embedding means one write keeps a message and its
     * replies consistent instead of two tables that can disagree mid-transaction.
     */
    var replies: RealmList<ChatReplyEntity> = realmListOf()

    /** The complete server object, for fields without a typed column. */
    var rawJson: String = ""

    companion object {
        const val STATUS_FAILED = -1
        const val STATUS_QUEUED = 0
        const val STATUS_UPLOADING = 1
        const val STATUS_CONFIRMED = 2

        fun key(module: String, projectId: String, scopeId: String, uniqueId: String) =
            "$module:$projectId:$scopeId:$uniqueId"
    }
}

/** A reply hanging off a message. Embedded — see [ChatMessageEntity.replies]. */
class ChatReplyEntity : EmbeddedRealmObject {
    var commentId: String = ""
    var senderId: String = ""
    var message: String = ""
    var messageTranslation: String = ""
    var messageType: String = "text"
    var created: Long = 0
    var updated: Long = 0
    var deleted: Long = 0
    var edited: Long = 0
    var isTranslated: Boolean = false
    var attachmentUrl: String? = null
    var attachmentName: String? = null
    var attachmentSize: String? = null
    var attachmentThumbnail: String? = null
}
