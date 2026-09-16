package com.zillit.zillitapp.feature.cnc.data

import com.zillit.zillitapp.core.database.entity.CncAttachmentEntity
import com.zillit.zillitapp.core.database.entity.CncMessageElementEntity
import com.zillit.zillitapp.core.database.entity.CncMessageEntity
import com.zillit.zillitapp.core.database.entity.CncReactionEntity
import com.zillit.zillitapp.core.database.entity.CncReplyEntity
import com.zillit.zillitapp.core.network.ZillitCrypto
import io.realm.kotlin.ext.realmListOf
import io.realm.kotlin.ext.toRealmList

/**
 * Wire ⇄ storage, in one place.
 *
 * Deliberately not a member of either type. A DTO that knows how to write itself into
 * Realm ends up carrying a Realm dependency into every layer that touches the network, and
 * an entity that knows the wire gets renamed the moment the backend does.
 *
 * ### Message text is encrypted on the wire
 * Every chat in the app sends ciphertext and decrypts before showing it — unit chat and
 * socket chat alike. `message`, `message_translation` and an attachment's `caption` are
 * the encrypted fields; ids, timestamps and file keys are not.
 *
 * Both directions are done **here**, and [ZillitCrypto] is a required parameter rather than
 * something a caller may remember to apply. That is the whole point: a send path that
 * forgets to encrypt puts readable text on the wire, and a store path that forgets to
 * decrypt shows the user a hex blob. Neither is possible if the only way to build a payload
 * or fill a row goes through these functions.
 *
 * Storage holds **plaintext**. v2 keeps ciphertext in Realm and decrypts on every bind,
 * which means decrypting the same message once per scroll and leaves search matching
 * against hex. Decrypting once, on the way in, costs nothing afterwards.
 */

/**
 * Fold a server message into a row.
 *
 * Writes into an existing row rather than returning a new one, because the identity that
 * matters — `unique_id` — already exists locally for anything this device sent, and
 * replacing the row would lose the local-only columns (upload progress, local file paths)
 * that the bubble is rendering from while the send is still in flight.
 *
 * The caller owns the primary key: pass a row that was queried by it, or one created
 * with it already set. This function never writes `id`.
 *
 * @param preserveLocal true while a send is in flight: the server has no idea about this
 *   device's file paths or upload progress, so its blanks must not overwrite them.
 */
fun CncMessageEntity.applyFrom(
    dto: CncMessageDto,
    surface: ChatSurface,
    projectId: String,
    conversationId: String,
    isGroup: Boolean,
    currentUserId: String,
    crypto: ZillitCrypto,
    preserveLocal: Boolean = false,
) {
    val entity = this

    // No `entity.id` here. The key is the caller's — it is how this row was found or
    // created — and Realm refuses to reassign a primary key on a managed object.
    entity.surface = surface.key
    entity.projectId = projectId
    entity.conversationId = conversationId
    entity.isGroup = isGroup
    // Part of that key, so it must not move under an existing row. Blank only on a row
    // that was just created carrying nothing but its id.
    if (entity.uniqueId.isBlank()) entity.uniqueId = dto.uniqueId.orEmpty()
    dto.id?.takeIf { it.isNotBlank() }?.let { entity.serverId = it }

    entity.senderId = dto.sender.orEmpty()
    entity.receiverId = dto.receiver.orEmpty()
    entity.senderDeviceId = dto.senderDeviceId.orEmpty()
    entity.receiverDeviceId = dto.receiverDeviceId.orEmpty()

    entity.chatTool = dto.chatTool.orEmpty()
    entity.departmentId = dto.departmentId.orEmpty()
    entity.budgetDocumentId = dto.budgetDocumentId.orEmpty()

    // Decrypted on the way in, so everything above storage works in readable text.
    entity.message = crypto.decryptOrBlank(dto.message)
    entity.messageTranslation = crypto.decryptOrBlank(dto.messageTranslation)
    entity.messageType = dto.messageType ?: "text"
    entity.messageGroup = dto.messageGroup ?: 0

    // Never move a status backwards. Receipts and the message itself arrive on separate
    // events with no ordering guarantee, so a late echo of the original send would
    // otherwise turn a read message back into a sent one and the tick would un-blue.
    val incoming = dto.status ?: CncMessageEntity.STATUS_SENT
    if (incoming > entity.status) entity.status = incoming

    entity.created = dto.created ?: entity.created
    entity.updated = dto.updated ?: entity.updated
    entity.edited = dto.edited ?: entity.edited
    entity.deleted = dto.deleted ?: entity.deleted
    entity.expired = dto.expired ?: entity.expired
    entity.archived = dto.archived ?: entity.archived
    entity.isTranslated = dto.isTranslated ?: entity.isTranslated

    dto.location?.let { location ->
        entity.locationLatitude = location.latitude ?: 0.0
        entity.locationLongitude = location.longitude ?: 0.0
        entity.locationAddress = location.address.orEmpty()
    }

    // Attachments only when the server actually sent some. A confirmation echo for a
    // message whose files are still uploading carries none, and clearing the list would
    // blank the bubble the user is watching.
    // Never let a later payload that omits the field erase a count already known: the
    // delete and edit echoes carry a trimmed message.
    if (dto.readby.isNotEmpty()) entity.readByCount = dto.readby.size

    val files = dto.allAttachments
    if (files.isNotEmpty()) {
        val local = entity.attachments.associateBy { it.media }
        entity.attachments = files.map { file ->
            CncAttachmentEntity().apply {
                media = file.media.orEmpty()
                name = file.name.orEmpty()
                thumbnail = file.thumbnail.orEmpty()
                contentType = file.contentType.orEmpty()
                contentSubtype = file.contentSubtype.orEmpty()
                caption = crypto.decryptOrBlank(file.caption)
                fileSize = file.fileSize.orEmpty()
                duration = file.duration ?: 0
                width = file.width ?: 0
                height = file.height ?: 0
                bucket = file.bucket.orEmpty()
                region = file.region.orEmpty()
                if (preserveLocal) {
                    localFilePath = local[file.media]?.localFilePath.orEmpty()
                    localThumbnailPath = local[file.media]?.localThumbnailPath.orEmpty()
                }
            }
        }.toRealmList()
    }

    dto.reply?.let { source ->
        entity.reply = CncReplyEntity().apply {
            messageId = source.messageId.orEmpty()
            senderId = source.sender.orEmpty()
            message = crypto.decryptOrBlank(source.message)
            messageTranslation = crypto.decryptOrBlank(source.messageTranslation)
            messageType = source.messageType ?: "text"
            created = source.created ?: 0
            attachmentMedia = source.attachment?.media.orEmpty()
            attachmentName = source.attachment?.name.orEmpty()
            attachmentThumbnail = source.attachment?.thumbnail.orEmpty()
        }
    }

    dto.reactions?.let { list ->
        entity.reactions = list.map { source ->
            CncReactionEntity().apply {
                userId = source.userId.orEmpty()
                reaction = source.reaction.orEmpty()
                updated = source.updated ?: 0
            }
        }.toRealmList()
    }

    dto.messageElements?.let { list ->
        entity.messageElements = list.map { source ->
            CncMessageElementEntity().apply {
                search = source.search.orEmpty()
                replacer = source.replacer.orEmpty()
            }
        }.toRealmList()
    }

    // A message this user sent from another device is still theirs. Recomputed from the
    // sender rather than trusted from the payload, which has no such field.
    if (entity.senderId == currentUserId && entity.status == CncMessageEntity.STATUS_PENDING) {
        entity.status = CncMessageEntity.STATUS_SENT
    }
}

/**
 * Which conversation a message belongs to, from this user's point of view.
 *
 * A group message names the room in `receiver`. A direct message names *the other person* —
 * which is the receiver on something sent and the sender on something received. Getting
 * this wrong files replies into a thread of their own, which is v2's "my own messages are
 * in a separate chat" bug.
 */
fun CncMessageDto.conversationIdFor(currentUserId: String): String {
    if (type == CncMessageDto.TYPE_GROUP) return receiver.orEmpty()
    return if (sender == currentUserId) receiver.orEmpty() else sender.orEmpty()
}

/** True when the message addresses a room rather than a person. */
val CncMessageDto.isGroupMessage: Boolean
    get() = type == CncMessageDto.TYPE_GROUP

/** Build the payload for a new outgoing message. */
fun newOutgoingMessage(
    surface: ChatSurface,
    uniqueId: String,
    projectId: String,
    senderId: String,
    conversationId: String,
    isGroup: Boolean,
    body: String,
    translation: String = "",
    messageType: String = "text",
    receiverDeviceId: String? = null,
    senderDeviceId: String? = null,
    departmentId: String? = null,
    budgetDocumentId: String? = null,
    messageGroup: Long = 0,
    attachments: List<CncAttachmentDto> = emptyList(),
    reply: CncReplyDto? = null,
    /** Mentions: `{search: userId, replacer: name}`. Never encrypted — they are not text. */
    messageElements: List<CncMessageElementDto> = emptyList(),
    location: CncLocationDto? = null,
    createdAt: Long = System.currentTimeMillis(),
    /** Required, not optional: see the note at the top of this file. */
    crypto: ZillitCrypto,
): CncMessageDto = CncMessageDto(
    projectId = projectId,
    uniqueId = uniqueId,
    type = if (isGroup) CncMessageDto.TYPE_GROUP else CncMessageDto.TYPE_PRIVATE,
    messageType = messageType,
    sender = senderId,
    receiver = conversationId,
    senderDeviceId = senderDeviceId,
    receiverDeviceId = receiverDeviceId,
    chatTool = surface.chatTool(departmentId),
    departmentId = departmentId,
    budgetDocumentId = budgetDocumentId,
    // Ciphertext on the wire, in the same cipher every other chat in the app uses. The
    // caller hands this function readable text and never sees the encrypted form.
    message = crypto.encryptOrBlank(body).takeIf { it.isNotEmpty() },
    // Falls back to the body rather than going out empty. Every other client sends the
    // two together — a server-side translation replaces this later, and until then the
    // readable copy is the original. A null here reaches other platforms as a message
    // with no translated text at all.
    messageTranslation = crypto.encryptOrBlank(translation.ifBlank { body })
        .takeIf { it.isNotEmpty() },
    messageGroup = messageGroup.takeIf { it != 0L },
    // A caption travels with the file and is encrypted like any other message text.
    attachments = attachments
        .map { it.copy(caption = crypto.encryptOrBlank(it.caption).takeIf(String::isNotEmpty)) }
        .takeIf { it.isNotEmpty() },
    messageElements = messageElements.takeIf { it.isNotEmpty() },
    reply = reply?.copy(
        message = crypto.encryptOrBlank(reply.message).takeIf { it.isNotEmpty() },
        messageTranslation = crypto.encryptOrBlank(reply.messageTranslation)
            .takeIf { it.isNotEmpty() },
    ),
    location = location,
    status = CncMessageEntity.STATUS_PENDING,
    created = createdAt,
)

/** A fresh row for a message this device is about to send. */
fun optimisticRow(
    dto: CncMessageDto,
    surface: ChatSurface,
    projectId: String,
    conversationId: String,
    isGroup: Boolean,
    /**
     * What the user actually typed.
     *
     * Taken separately because [dto] carries the encrypted form destined for the wire, and
     * the bubble has to show the message the instant it is sent — reading it back out of
     * the DTO would mean decrypting text this device encrypted a line earlier.
     */
    plainBody: String,
    plainTranslation: String = "",
    plainCaptions: List<String> = emptyList(),
    localPaths: List<String> = emptyList(),
    /** The reply block in readable form, when this message quotes another. */
    replySource: CncReplyDto? = null,
): CncMessageEntity = CncMessageEntity().apply {
    id = CncMessageEntity.key(surface.key, projectId, dto.uniqueId.orEmpty())
    this.surface = surface.key
    this.projectId = projectId
    this.conversationId = conversationId
    this.isGroup = isGroup
    uniqueId = dto.uniqueId.orEmpty()
    senderId = dto.sender.orEmpty()
    receiverId = dto.receiver.orEmpty()
    senderDeviceId = dto.senderDeviceId.orEmpty()
    receiverDeviceId = dto.receiverDeviceId.orEmpty()
    chatTool = dto.chatTool.orEmpty()
    departmentId = dto.departmentId.orEmpty()
    budgetDocumentId = dto.budgetDocumentId.orEmpty()
    message = plainBody
    messageTranslation = plainTranslation
    messageType = dto.messageType ?: "text"
    messageGroup = dto.messageGroup ?: 0
    status = CncMessageEntity.STATUS_PENDING
    created = dto.created ?: System.currentTimeMillis()
    updated = created

    dto.location?.let {
        locationLatitude = it.latitude ?: 0.0
        locationLongitude = it.longitude ?: 0.0
        locationAddress = it.address.orEmpty()
    }

    // The quoted text is already readable here: it was copied off a stored row, which
    // holds plaintext, before the outgoing payload encrypted its own copy.
    replySource?.let { source ->
        reply = CncReplyEntity().apply {
            messageId = source.messageId.orEmpty()
            senderId = source.sender.orEmpty()
            message = source.message.orEmpty()
            messageType = source.messageType ?: "text"
            created = source.created ?: 0
            attachmentMedia = source.attachment?.media.orEmpty()
            attachmentName = source.attachment?.name.orEmpty()
            attachmentThumbnail = source.attachment?.thumbnail.orEmpty()
        }
    }

    // The local copies are what the bubble renders from until the upload finishes, which is
    // what makes sending a photo feel instant rather than blank-then-photo.
    attachments = dto.allAttachments.mapIndexed { index, file ->
        CncAttachmentEntity().apply {
            media = file.media.orEmpty()
            name = file.name.orEmpty()
            thumbnail = file.thumbnail.orEmpty()
            contentType = file.contentType.orEmpty()
            caption = plainCaptions.getOrElse(index) { "" }
            fileSize = file.fileSize.orEmpty()
            duration = file.duration ?: 0
            localFilePath = localPaths.getOrElse(index) { "" }
        }
    }.toRealmList().ifEmpty { realmListOf() }
}
