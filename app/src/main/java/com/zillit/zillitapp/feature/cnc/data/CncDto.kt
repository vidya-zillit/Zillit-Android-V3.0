package com.zillit.zillitapp.feature.cnc.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The socket-chat wire model.
 *
 * **Field names are the server's and cannot be renamed.** The same object is the send
 * payload, the receive payload and the history row, on web, iOS and Android alike — which
 * is also why it is one flat class rather than a sealed hierarchy per message type. It is
 * mapped into something typed at the repository boundary; nothing above that sees it.
 *
 * `attachemnt` (in [messageType]) is spelled that way on the wire. It is not a typo here.
 */
@Serializable
data class CncMessageDto(
    @SerialName("project_id") val projectId: String? = null,

    /** Client-generated before the send. The identity of the message, before and after. */
    @SerialName("unique_id") val uniqueId: String? = null,

    /** Server id. Absent until the acknowledgement. */
    @SerialName("_id") val id: String? = null,

    /** `private` or `group`. */
    val type: String? = null,

    @SerialName("message_type") val messageType: String? = null,

    val sender: String? = null,
    val receiver: String? = null,

    @SerialName("sender_device_id") val senderDeviceId: String? = null,
    @SerialName("receiver_device_id") val receiverDeviceId: String? = null,

    @SerialName("chat_tool") val chatTool: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("budget_document_id") val budgetDocumentId: String? = null,

    val message: String? = null,
    @SerialName("message_translation") val messageTranslation: String? = null,
    @SerialName("isTranslated") val isTranslated: Boolean? = null,

    /** Files picked in one action share this, so they render as one bubble. */
    @SerialName("message_group") val messageGroup: Long? = null,

    val attachment: CncAttachmentDto? = null,
    val attachments: List<CncAttachmentDto>? = null,
    @SerialName("message_elements") val messageElements: List<CncMessageElementDto>? = null,

    val location: CncLocationDto? = null,
    val reply: CncReplyDto? = null,
    val reactions: List<CncReactionDto>? = null,

    /** `0` pending · `1` sent · `2` delivered · `3` read. */
    val status: Int? = null,

    val created: Long? = null,
    val updated: Long? = null,
    val edited: Long? = null,
    val deleted: Long? = null,
    val expired: Long? = null,
    val archived: Long? = null,
    @SerialName("last_activity") val lastActivity: Long? = null,

    /**
     * Who has read this message, on every message the server sends.
     *
     * This is where a group's "Read by N" comes from. There is no endpoint that answers it
     * per room — the one read-by route takes a single message id and is for the sheet that
     * names the people — so counting from here is the only way to label a list of bubbles
     * without one request per bubble.
     */
    val readby: List<String> = emptyList(),

    /** Identifies the sending platform to the backend. Always "android" from here. */
    val platform: String = PLATFORM,
) {
    /** Every file on the message, however the server chose to send them. */
    val allAttachments: List<CncAttachmentDto>
        get() = buildList {
            attachment?.let(::add)
            attachments?.let(::addAll)
        }.distinctBy { it.media ?: it.name }

    companion object {
        const val PLATFORM = "android"
        const val TYPE_PRIVATE = "private"
        const val TYPE_GROUP = "group"
    }
}

@Serializable
data class CncAttachmentDto(
    /** Object key in the project's bucket, never a URL. */
    val media: String? = null,
    val name: String? = null,
    val thumbnail: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("content_subtype") val contentSubtype: String? = null,
    val caption: String? = null,
    @SerialName("file_size") val fileSize: String? = null,
    val duration: Long? = null,
    val width: Long? = null,
    val height: Long? = null,
    val bucket: String? = null,
    val region: String? = null,
    @SerialName("_id") val id: String? = null,
    val created: Long? = null,
)

@Serializable
data class CncReplyDto(
    @SerialName("message_id") val messageId: String? = null,
    val sender: String? = null,
    val message: String? = null,
    @SerialName("message_translation") val messageTranslation: String? = null,
    @SerialName("message_type") val messageType: String? = null,
    val created: Long? = null,
    val attachment: CncAttachmentDto? = null,
    @SerialName("message_elements") val messageElements: List<CncMessageElementDto>? = null,
)

@Serializable
data class CncReactionDto(
    @SerialName("user_id") val userId: String? = null,
    val reaction: String? = null,
    val updated: Long? = null,
)

@Serializable
data class CncMessageElementDto(
    val search: String? = null,
    val replacer: String? = null,
)

/**
 * A shared pin — the same shape a unit chat uses.
 *
 * `ChatAndGroupModel.location` in v2 is the very same `LocationInfo` the Home chat sends,
 * so the two surfaces agree. Note the asymmetry the server imposes: `lat` but `long`.
 *
 * v2 also has a GeoJSON `Location(coordinates)` class, which is **not** this and is not
 * what a chat message carries; building a chat location from it produces a pin the backend
 * ignores.
 */
@Serializable
data class CncLocationDto(
    @SerialName("lat") val latitude: Double? = null,
    @SerialName("long") val longitude: Double? = null,
    val address: String? = null,
    /** Static map preview the backend renders. Sent empty; filled in on the way back. */
    @SerialName("imageLink") val imageLink: String? = null,
    val height: Long? = null,
    val width: Long? = null,
)

/** What the server sends back around a message — an ack, or a live event. */
@Serializable
data class CncMessageEnvelope(
    val success: Boolean? = null,
    val detail: CncMessageDto? = null,
    val message: String? = null,
)

/**
 * The envelope whose `detail` is a **list**.
 *
 * Three things answer in this shape and all three were being decoded as a single object,
 * so all three failed and silently did nothing:
 *
 * - `pending-messages:get`, the catch-up stream, which also chunks: it arrives as several
 *   events and only the last one carries `last_chunk: true`.
 * - the delete event and the delete acknowledgement, which return the **messages** that
 *   were deleted, not a list of ids.
 *
 * v2 calls this `ChatAndGroupDetailModelWithList` and uses it for exactly those.
 */
@Serializable
data class CncMessageListEnvelope(
    val success: Boolean? = null,
    /** False on every chunk but the last. Only meaningful on the catch-up stream. */
    @SerialName("last_chunk") val lastChunk: Boolean? = null,
    val detail: List<CncMessageDto> = emptyList(),
) {
    /** Server ids of everything in the batch, for the events that only need identity. */
    val serverIds: List<String> get() = detail.mapNotNull { it.id }
}

/** A read watermark: everything up to [id] is now at [status] for [userId]. */
@Serializable
data class CncReadReceiptEnvelope(
    val success: Boolean? = null,
    val detail: CncReadReceipt? = null,
)

/**
 * What comes back is not what goes out.
 *
 * The emit names a room and a user; the event names a **conversation** through `sender` and
 * `receiver`, and can promote a named set of messages through `chat_message_ids` instead of
 * a watermark. Modelling it after the request left every field but `status` unread, so a
 * receipt for a message this device had not stored moved nothing at all.
 */
@Serializable
data class CncReadReceipt(
    @SerialName("_id") val id: String? = null,
    val updated: Long? = null,
    val sender: String? = null,
    @SerialName("emitted_sender") val emittedSender: String? = null,
    val receiver: String? = null,
    /** `private` or `group`. */
    val type: String? = null,
    val status: Int? = null,
    @SerialName("emitted_status") val emittedStatus: Int? = null,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("chat_tool") val chatTool: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("budget_document_id") val budgetDocumentId: String? = null,
    /** When present, exactly these messages move — no watermark is implied. */
    @SerialName("chat_message_ids") val chatMessageIds: List<String>? = null,
)

/** The typing event, in the shape v2 both sends and expects. */
@Serializable
data class CncTypingEnvelope(
    val success: Boolean? = null,
    val detail: CncTyping? = null,
)

@Serializable
data class CncTyping(
    val sender: String? = null,
    val receiver: String? = null,
    /** `start` or `end`. */
    val status: String? = null,
    @SerialName("chat_tool") val chatTool: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("budget_document_id") val budgetDocumentId: String? = null,
)

/**
 * The recent-conversation list, as `user:list` really returns it.
 *
 * **Ids, not objects.** An earlier version of this file modelled `detail` as a list of rows
 * carrying `sorting_activity` and `unread`; that was a guess and it decoded to nothing, so
 * every conversation stayed at order key 0 and the Chat tab filtered them all out and said
 * "No conversations yet".
 *
 * What the event actually answers is narrow: *which people have I exchanged messages with*.
 * The ordering comes from `sorting_activity` on the project users endpoint, and the unread
 * count from the badge tree.
 */
@Serializable
data class CncRecentListEnvelope(
    val success: Boolean? = null,
    val detail: CncRecentDetail? = null,
)

/**
 * The two surfaces answer this event **differently**, which is not a prefix thing.
 *
 * C&C's `user:list` returns ids under `usersList`. Budget's `budget:recent:list` returns
 * whole room objects under `detail` instead. Both shapes are accepted here so one model
 * covers both — decoding Budget with the C&C shape yields an empty list and a blank tab,
 * which is what would have happened.
 */
@Serializable
data class CncRecentDetail(
    /** User ids this person has a conversation with. */
    val usersList: List<String> = emptyList(),
    /** Present on some responses; the same ids under another name. */
    @SerialName("user_list") val userList: List<String> = emptyList(),
    /** Rooms come back whole here, unlike people. */
    @SerialName("chat_rooms_list") val chatRooms: List<CncRoomDto> = emptyList(),
) {
    /** Whichever spelling the server used. */
    val allUserIds: List<String> get() = (usersList + userList).distinct()
}

/** Budget's shape for the same event: rooms directly under `detail`. */
@Serializable
data class CncRecentRoomsEnvelope(
    val success: Boolean? = null,
    val detail: List<CncRoomDto> = emptyList(),
)
