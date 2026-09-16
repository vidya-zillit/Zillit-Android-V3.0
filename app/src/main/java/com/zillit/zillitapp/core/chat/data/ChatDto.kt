package com.zillit.zillitapp.core.chat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the chat endpoints, shared by every chat module.
 *
 * Everything is nullable with a default, for the same reason as the directory DTOs: the
 * backend omits fields freely, and one missing key must not cost the whole page.
 */

@Serializable
data class ChatListResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: List<ChatMessageDto>? = null,
)

@Serializable
data class ChatSingleResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: ChatMessageDto? = null,
)

@Serializable
data class ChatMessageDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("unique_id") val uniqueId: String? = null,
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("sender") val sender: String? = null,
    /** AES-encrypted hex. Decrypted once, at write time. */
    @SerialName("message") val message: String? = null,
    @SerialName("message_translation") val messageTranslation: String? = null,
    @SerialName("message_type") val messageType: String? = null,
    @SerialName("message_group") val messageGroup: Long? = null,
    @SerialName("attachment") val attachment: ChatAttachmentDto? = null,
    @SerialName("location") val location: ChatLocationDto? = null,
    @SerialName("comments") val comments: List<ChatCommentDto>? = null,
    @SerialName("created") val created: Long? = null,
    @SerialName("updated") val updated: Long? = null,
    @SerialName("deleted") val deleted: Long? = null,
    @SerialName("edited") val edited: Long? = null,
    /**
     * When the message was superseded, for a Call Sheet replace.
     *
     * Only history rows carry it, and it is the cursor history pages by — v2's
     * `getLastMessageTime()` reads `archived`, not `created`.
     */
    @SerialName("archived") val archived: Long? = null,
    @SerialName("status") val status: Int? = null,
)

@Serializable
data class ChatAttachmentDto(
    @SerialName("media") val media: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("file_size") val fileSize: String? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
    /**
     * The **category** — "image", "video", "audio", "doc" — not the MIME type.
     *
     * Sending `image/png` here is rejected with
     * `unit_chat_attachment_content_type_required`; the backend pairs this with
     * [contentSubtype] for the extension, which is the split v2 uses too.
     */
    @SerialName("content_type") val contentType: String? = null,
    /** File extension without the dot, e.g. "png". Note: `content_subtype`, no underscore. */
    @SerialName("content_subtype") val contentSubtype: String? = null,
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("region") val region: String? = null,
    @SerialName("caption") val caption: String? = null,
    @SerialName("unique_id") val uniqueId: String? = null,
    @SerialName("duration") val duration: Long? = null,
    @SerialName("width") val width: Int? = null,
    @SerialName("height") val height: Int? = null,
)

/**
 * A shared pin.
 *
 * **The field names are the server's and are not symmetrical**: latitude is `lat` but
 * longitude is `long`, not `lng` or `longitude`. This class previously used `latitude` and
 * `longitude`, which serialise to keys the backend ignores — a location sent that way is
 * accepted and comes back empty on every client.
 */
@Serializable
data class ChatLocationDto(
    @SerialName("lat") val latitude: Double? = null,
    @SerialName("long") val longitude: Double? = null,
    @SerialName("address") val address: String? = null,
    /** Static map preview the backend renders. Sent empty; filled in on the way back. */
    @SerialName("imageLink") val imageLink: String? = null,
    @SerialName("height") val height: Long? = null,
    @SerialName("width") val width: Long? = null,
)

@Serializable
data class ChatCommentDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("sender") val sender: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("message_translation") val messageTranslation: String? = null,
    @SerialName("message_type") val messageType: String? = null,
    @SerialName("attachment") val attachment: ChatAttachmentDto? = null,
    @SerialName("created") val created: Long? = null,
    @SerialName("updated") val updated: Long? = null,
    @SerialName("deleted") val deleted: Long? = null,
    @SerialName("edited") val edited: Long? = null,
)

/**
 * POST body for a new message.
 *
 * `encodeDefaults = false` is on globally, so every property that must always be sent is
 * non-null without a default — the trap that silently dropped `project_language` and
 * `number_of_users` from the create-project request.
 */
@Serializable
data class SendMessageRequest(
    @SerialName("unit_id") val unitId: String,
    @SerialName("unique_id") val uniqueId: String,
    @SerialName("message") val message: String,
    @SerialName("message_translation") val messageTranslation: String,
    @SerialName("message_type") val messageType: String,
    @SerialName("message_group") val messageGroup: Long,
    @SerialName("attachment") val attachment: ChatAttachmentDto? = null,
    @SerialName("location") val location: ChatLocationDto? = null,
    /**
     * Call Sheet only. True tells the backend to move everything currently posted in the
     * unit to History and replace it with this upload. Null for every other unit —
     * sending `false` would be a different instruction than not asking at all.
     */
    @SerialName("replacePreviousChats") val replacePreviousChats: Boolean? = null,
    /** Call Sheet uploads are auto-distributed to Document Distribution, as in v2. */
    @SerialName("isDistributeAutomatic") val isDistributeAutomatic: Boolean? = null,
    /** Resolved unit name, used by the backend when it distributes the document. */
    @SerialName("moduleName") val moduleName: String? = null,
)

/** `.../chat/watermark/{id}` — one attachment, not a message. */
@Serializable
data class ChatSingleAttachmentResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("data") val data: ChatAttachmentDto? = null,
)

/**
 * What a delete actually returns.
 *
 * `data` is an **object** naming what was removed — not a list of messages. Declaring it
 * as a message list made the response fail to parse, so a delete that had already
 * succeeded server-side was reported as a failure and never applied locally: the row
 * stayed on screen until the next full refetch.
 *
 * Shared by message and comment deletes, which answer with the same shape.
 */
@Serializable
data class DeleteResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: DeleteResult? = null,
)

@Serializable
data class DeleteResult(
    @SerialName("chatIds") val chatIds: List<String> = emptyList(),
    @SerialName("commentIds") val commentIds: List<String> = emptyList(),
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("updated") val updated: Long? = null,
    @SerialName("deleted") val deleted: Long? = null,
)

@Serializable
data class DeleteMessagesRequest(
    @SerialName("chatIds") val chatIds: List<String>,
)

@Serializable
data class ReplyRequest(
    @SerialName("message") val message: String,
    @SerialName("message_translation") val messageTranslation: String,
)

/** `home:message:deleted:multiple` — ids only, not whole messages. */
@Serializable
data class ChatDeleteEvent(
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("chatIds") val chatIds: List<String>? = null,
    @SerialName("deleted") val deleted: Long? = null,
    @SerialName("updated") val updated: Long? = null,
)
