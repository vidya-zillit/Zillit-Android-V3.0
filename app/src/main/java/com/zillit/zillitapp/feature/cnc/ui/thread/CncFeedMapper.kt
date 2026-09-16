package com.zillit.zillitapp.feature.cnc.ui.thread

import com.zillit.zillitapp.core.common.RelativeDay
import com.zillit.zillitapp.core.common.toClockTime
import com.zillit.zillitapp.core.common.toDurationLabel
import com.zillit.zillitapp.core.common.toRelativeDay
import com.zillit.zillitapp.core.database.entity.CncMessageEntity
import com.zillit.zillitapp.core.ui.chat.model.ChatAuthor
import com.zillit.zillitapp.core.ui.chat.model.ChatFeedItem
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.core.ui.chat.model.SendState
import com.zillit.zillitapp.core.ui.chat.model.ChatReaction
import com.zillit.zillitapp.core.ui.chat.model.ChatMention
import com.zillit.zillitapp.core.ui.chat.model.QuotedMessage

/**
 * Storage rows → what the shared thread renders.
 *
 * Separate from the Home mapper because the entities differ, and identical in shape because
 * the *rendering* is shared: both produce `ChatFeedItem`s that `ChatThread` draws with the
 * same bubbles. That is the split agreed for this module — separate data, shared rendering.
 *
 * The one real difference is status. A socket message's `0..3` are pending, sent, delivered
 * and read, and the last two are what make the ticks mean anything; a unit message has no
 * equivalent, which is why the two status columns cannot be shared.
 */
internal fun List<CncMessageEntity>.toCncFeed(
    currentUserId: String,
    nameOf: (String) -> String,
    designationOf: (String) -> String = { "" },
    /**
     * Queue state for files still going up, keyed by message id.
     *
     * Without it every unsent message looks the same: a 200MB clip at 3% and a text message
     * the server rejected both showed the plain "sending" clock, so there was no way to tell
     * patience from a problem, and nothing to retry or cancel.
     */
    uploadStateOf: (String) -> UploadProgress? = { null },
    pictureOf: (String) -> Pair<String?, String?> = { null to null },
    isGroup: Boolean = false,
): List<ChatFeedItem> {
    if (isEmpty()) return emptyList()

    val items = mutableListOf<ChatFeedItem>()
    var lastDay: RelativeDay? = null

    // Files picked in one action carry the same `messageGroup`, so they become a single post
    // with a grid rather than one row per file.
    groupIntoBatches().forEach { batch ->
        val root = batch.first()

        val day = root.created.toRelativeDay()
        if (day != lastDay) {
            items += ChatFeedItem.Day(day)
            lastDay = day
        }

        items += ChatFeedItem.Post(
            root = root.toMessage(currentUserId, nameOf, designationOf, pictureOf, isGroup, uploadStateOf),
            batch = batch.drop(1)
                .map { it.toMessage(currentUserId, nameOf, designationOf, pictureOf, isGroup, uploadStateOf) },
            rootServerId = root.serverId.takeIf { it.isNotBlank() },
        )
    }

    return items
}

/** Consecutive messages from one sender sharing a `messageGroup` form one post. */
private fun List<CncMessageEntity>.groupIntoBatches(): List<List<CncMessageEntity>> {
    val batches = mutableListOf<MutableList<CncMessageEntity>>()

    forEach { message ->
        val open = batches.lastOrNull()
        val head = open?.firstOrNull()
        val joins = head != null &&
            message.messageGroup != 0L &&
            message.messageGroup == head.messageGroup &&
            message.senderId == head.senderId

        if (joins) open!! += message else batches += mutableListOf(message)
    }

    return batches
}

private fun CncMessageEntity.toMessage(
    currentUserId: String,
    nameOf: (String) -> String,
    designationOf: (String) -> String,
    pictureOf: (String) -> Pair<String?, String?>,
    isGroup: Boolean,
    uploadStateOf: (String) -> UploadProgress? = { null },
): ChatMessage {
    val own = senderId == currentUserId
    val pictures = pictureOf(senderId)

    val author = ChatAuthor(
        id = senderId,
        name = nameOf(senderId),
        // Only a group needs the role: in a one-to-one thread there is exactly one other
        // person and their job title beside every line is noise.
        role = designationOf(senderId).takeIf { isGroup && it.isNotBlank() },
        avatarUrl = pictures.first,
        avatarThumbnailKey = pictures.second,
    )

    val timestamp = created.toClockTime()
    val state = if (own) status.toSendState(uploadStateOf(uniqueId)) else null
    val wasEdited = edited > 0
    val file = attachments.firstOrNull()

    // What this message answers, when it answers one. Built from the copy stored on the
    // message rather than by finding the original: the original may be older than anything
    // loaded, or deleted since, and a quotation that vanishes is worse than a stale one.
    val quotedMessage = reply?.takeIf { it.messageId.isNotBlank() }?.let { source ->
        QuotedMessage(
            messageId = source.messageId,
            authorName = nameOf(source.senderId),
            preview = source.message.takeIf { it.isNotBlank() }
                ?: source.attachmentName.takeIf { it.isNotBlank() }
                ?: source.messageType,
            isOwn = source.senderId == currentUserId,
        )
    }

    // Tallied once here rather than at draw time. The server stores one row per person, so
    // the same emoji from four people is four rows; the bubble shows one chip and a count.
    val tallied = reactions
        .groupBy { it.reaction }
        .map { (emoji, rows) ->
            ChatReaction(
                emoji = emoji,
                count = rows.size,
                isMine = rows.any { it.userId == currentUserId },
            )
        }
        .sortedByDescending { it.count }

    return when (messageType) {
        "image" -> ChatMessage.Image(
            id = uniqueId,
            author = author,
            timestamp = timestamp,
            isOwn = own,
            sendState = state,
            isEdited = wasEdited,
            createdAt = created,
            reactions = tallied,
            quoted = quotedMessage,
            remoteKey = file?.media,
            localPath = file?.localFilePath?.takeIf { it.isNotBlank() },
            thumbnail = file?.thumbnail,
            caption = file?.caption?.takeIf { it.isNotBlank() } ?: message.takeIf { it.isNotBlank() },
        )

        "video" -> ChatMessage.Video(
            id = uniqueId,
            author = author,
            timestamp = timestamp,
            isOwn = own,
            sendState = state,
            isEdited = wasEdited,
            createdAt = created,
            reactions = tallied,
            quoted = quotedMessage,
            remoteKey = file?.media,
            localPath = file?.localFilePath?.takeIf { it.isNotBlank() },
            thumbnail = file?.thumbnail,
            duration = (file?.duration ?: 0L).toDurationLabel(),
            size = file?.fileSize.orEmpty(),
        )

        "audio" -> ChatMessage.Voice(
            id = uniqueId,
            author = author,
            timestamp = timestamp,
            isOwn = own,
            sendState = state,
            isEdited = wasEdited,
            createdAt = created,
            reactions = tallied,
            quoted = quotedMessage,
            remoteKey = file?.media,
            localPath = file?.localFilePath?.takeIf { it.isNotBlank() },
            duration = (file?.duration ?: 0L).toDurationLabel(),
            waveform = uniqueId.toWaveform(),
        )

        "location" -> ChatMessage.Location(
            id = uniqueId,
            author = author,
            timestamp = timestamp,
            isOwn = own,
            sendState = state,
            isEdited = wasEdited,
            createdAt = created,
            reactions = tallied,
            quoted = quotedMessage,
            placeName = locationAddress.takeIf { it.isNotBlank() } ?: message,
            address = locationAddress,
            latitude = locationLatitude,
            longitude = locationLongitude,
            // The map snapshot travels as the message's attachment, as on every surface.
            remoteKey = file?.media,
            localPath = file?.localFilePath?.takeIf { it.isNotBlank() },
            thumbnail = file?.thumbnail,
        )

        "text" -> ChatMessage.Text(
            id = uniqueId,
            author = author,
            timestamp = timestamp,
            isOwn = own,
            sendState = state,
            isEdited = wasEdited,
            createdAt = created,
            reactions = tallied,
            quoted = quotedMessage,
            body = message,
            // The body carries `@<userId>`; these are what turn it back into `@Name`. The
            // stored name is the fallback for somebody who has since left the project.
            mentions = messageElements.mapNotNull { element ->
                val id = element.search.trim('{', '}').takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ChatMention(userId = id, name = nameOf(id).ifBlank { element.replacer })
            },
        )

        // "document", "attachemnt" and anything unrecognised. A file row rather than a
        // dropped message: an unknown type must still be openable.
        else -> ChatMessage.Document(
            id = uniqueId,
            author = author,
            timestamp = timestamp,
            isOwn = own,
            sendState = state,
            isEdited = wasEdited,
            createdAt = created,
            reactions = tallied,
            quoted = quotedMessage,
            fileName = file?.name.orEmpty(),
            fileSize = file?.fileSize.orEmpty(),
            fileKind = file?.name?.substringAfterLast('.', "").orEmpty(),
            remoteKey = file?.media,
            localPath = file?.localFilePath?.takeIf { it.isNotBlank() },
            thumbnail = file?.thumbnail?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * The socket chat's own status numbering.
 *
 * Only ever shown on your own messages — the caller passes null for everyone else's, since
 * "delivered" describes a message's journey to you, not from you.
 */
private fun Int.toSendState(upload: UploadProgress?): SendState = when (this) {
    // Only a message the server has not taken can still be uploading or failed, so the
    // queue's opinion is consulted here and nowhere else. A row the queue has finished with
    // is back to plain "sending": its bytes are up and the acknowledgement is outstanding.
    CncMessageEntity.STATUS_PENDING -> when {
        upload == null -> SendState.Sending
        upload.hasFailed -> SendState.Failed
        upload.isFinished -> SendState.Sending
        else -> SendState.Uploading(upload.fraction)
    }

    CncMessageEntity.STATUS_SENT -> SendState.Sent
    CncMessageEntity.STATUS_DELIVERED -> SendState.Delivered
    CncMessageEntity.STATUS_READ -> SendState.Read
    else -> SendState.Sent
}

/**
 * What the thread needs to know about a queued file, without the entity.
 *
 * A small translation rather than passing the Realm object through the mapper: the mapper
 * runs on every rebuild and a managed object read off the main thread there is a crash
 * waiting for the first slow upload.
 */
data class UploadProgress(
    val fraction: Float,
    val hasFailed: Boolean,
    val isFinished: Boolean,
)

/**
 * A stable pseudo-random waveform derived from the id.
 *
 * A real one needs the decoded audio; until then this at least does not shimmer between
 * frames the way a random shape would.
 */
private fun String.toWaveform(): List<Float> {
    if (isEmpty()) return List(28) { 0.4f }
    var seed = hashCode()
    return List(28) {
        seed = seed * 1_103_515_245 + 12_345
        0.25f + ((seed ushr 16) and 0x7FFF) / 32_767f * 0.75f
    }
}
