package com.zillit.zillitapp.feature.home.ui

import com.zillit.zillitapp.core.common.RelativeDay
import com.zillit.zillitapp.core.common.toClockTime
import com.zillit.zillitapp.core.common.toDurationLabel
import com.zillit.zillitapp.core.common.toRelativeDay
import com.zillit.zillitapp.core.database.entity.ChatMessageEntity
import com.zillit.zillitapp.core.database.entity.ChatReplyEntity
import com.zillit.zillitapp.core.directory.userDetails
import com.zillit.zillitapp.core.ui.chat.model.ChatAuthor
import com.zillit.zillitapp.core.ui.chat.model.ChatFeedItem
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.core.ui.chat.model.SendState

/**
 * Turns stored rows into what the chat renders.
 *
 * Kept out of both the repository and the composables: the repository should not know
 * about display types, and a composable should not be resolving authors or formatting
 * timestamps on every recomposition.
 *
 * Authors are resolved **here, by id**, against the local directory. That is what makes a
 * profile change show up on messages already on screen — the row stores a sender id, never
 * a name, so a rename only has to land in one place.
 */
/**
 * History's feed.
 *
 * The same mapper, with the parts that only make sense on a live thread left off: no
 * unread divider (there is nothing new here) and no batch grouping (a replaced call sheet
 * is one document, and grouping would hide which of them was superseded when).
 */
internal fun List<ChatMessageEntity>.toHistoryFeed(): List<ChatFeedItem> {
    if (isEmpty()) return emptyList()

    val items = mutableListOf<ChatFeedItem>()
    var lastDay: RelativeDay? = null

    forEach { entity ->
        val day = entity.created.toRelativeDay()
        if (day != lastDay) {
            items += ChatFeedItem.Day(day)
            lastDay = day
        }

        items += ChatFeedItem.Post(
            root = entity.toMessage(),
            replies = entity.replies.map { it.toMessage(entity.id) },
            rootServerId = entity.serverId.takeIf { it.isNotBlank() },
        )
    }

    return items
}

/**
 * @param lastReadAt the newest message this user had seen when the thread was opened.
 *   Messages after it get the unread divider once, before the first of them.
 */
internal fun List<ChatMessageEntity>.toFeed(lastReadAt: Long = 0): List<ChatFeedItem> {
    if (isEmpty()) return emptyList()

    val items = mutableListOf<ChatFeedItem>()
    var lastDay: RelativeDay? = null
    var dividerPlaced = false
    val selfId = com.zillit.zillitapp.core.directory.UserLookup.currentUserId()

    // Files chosen together share a `message_group`, and v2 renders them as one post with
    // a grid. Grouped up front so the divider and day headers count a batch as one item.
    groupIntoBatches().forEach { batch ->
        val entity = batch.first()

        val day = entity.created.toRelativeDay()
        if (day != lastDay) {
            items += ChatFeedItem.Day(day)
            lastDay = day
        }

        // Only ever placed once, and never above your own message — you have by definition
        // read what you just sent.
        if (!dividerPlaced &&
            lastReadAt > 0 &&
            entity.created > lastReadAt &&
            entity.senderId != selfId
        ) {
            items += ChatFeedItem.UnreadDivider
            dividerPlaced = true
        }

        items += ChatFeedItem.Post(
            root = entity.toMessage(),
            batch = batch.drop(1).map { it.toMessage() },
            replies = entity.replies.map { it.toMessage(entity.id) },
            rootServerId = entity.serverId.takeIf { it.isNotBlank() },
        )
    }

    return items
}

/**
 * Collapses consecutive rows that share a non-zero `messageGroup`.
 *
 * Consecutive only, matching v2's `doGrouping`: a group is a single upload action, so its
 * rows are always adjacent in time, and scanning the whole list for matches would merge
 * two unrelated batches that happened to collide on a millisecond.
 */
private fun List<ChatMessageEntity>.groupIntoBatches(): List<List<ChatMessageEntity>> {
    val batches = mutableListOf<MutableList<ChatMessageEntity>>()

    forEach { entity ->
        val previous = batches.lastOrNull()?.lastOrNull()
        val sameBatch = previous != null &&
            entity.messageGroup != 0L &&
            previous.messageGroup == entity.messageGroup &&
            previous.senderId == entity.senderId &&
            // A message with replies anchors its own post — folding it into a grid would
            // hide the conversation hanging off it.
            previous.replies.isEmpty() &&
            entity.replies.isEmpty()

        if (sameBatch) batches.last() += entity else batches += mutableListOf(entity)
    }

    return batches
}

private fun ChatMessageEntity.toMessage(): ChatMessage {
    val author = senderId.toAuthor()
    val timestamp = created.toClockTime()
    val state = status.toSendState(uploadProgress)
    // v2's test: the server stamps `edited` only when someone actually changed the text.
    val wasEdited = edited > created

    return when (messageType) {
        "image" -> ChatMessage.Image(
            id = uniqueId,
            author = author,
            timestamp = timestamp,
            isOwn = isOwn(),
            sendState = state,
            isEdited = wasEdited,
            createdAt = created,
            isTranslated = isTranslated,
            thumbnail = attachmentThumbnail,
            remoteKey = attachmentUrl,
            // Rendered before any network call while the message is still uploading.
            localPath = localFilePath,
            caption = message.takeIf { it.isNotBlank() },
        )

        "video" -> ChatMessage.Video(
            id = uniqueId,
            author = author,
            timestamp = timestamp,
            isOwn = isOwn(),
            sendState = state,
            isEdited = wasEdited,
            createdAt = created,
            isTranslated = isTranslated,
            thumbnail = attachmentThumbnail,
            remoteKey = attachmentUrl,
            localPath = localFilePath,
            duration = attachmentDuration.toDurationLabel(),
            size = attachmentSize.orEmpty().toSizeLabel(),
        )

        "audio" -> ChatMessage.Voice(
            id = uniqueId,
            author = author,
            timestamp = timestamp,
            isOwn = isOwn(),
            sendState = state,
            isEdited = wasEdited,
            createdAt = created,
            isTranslated = isTranslated,
            remoteKey = attachmentUrl,
            localPath = localFilePath,
            duration = attachmentDuration.toDurationLabel(),
            // A real waveform needs the decoded file; until then a stable pseudo-random
            // shape derived from the id, so a message does not shimmer between frames.
            waveform = uniqueId.toWaveform(),
        )

        "location" -> ChatMessage.Location(
            id = uniqueId,
            author = author,
            timestamp = timestamp,
            isOwn = isOwn(),
            sendState = state,
            isEdited = wasEdited,
            createdAt = created,
            isTranslated = isTranslated,
            placeName = attachmentName ?: locationAddress.orEmpty(),
            address = locationAddress.orEmpty(),
            latitude = locationLatitude,
            longitude = locationLongitude,
        )

        // "document", "attachment", and anything unrecognised. Falling through to a file row
        // rather than dropping the message: an unknown type must still be openable.
        else -> ChatMessage.Document(
            id = uniqueId,
            author = author,
            timestamp = timestamp,
            isOwn = isOwn(),
            sendState = state,
            isEdited = wasEdited,
            createdAt = created,
            isTranslated = isTranslated,
            fileName = attachmentName ?: message.ifBlank { "File" },
            fileSize = attachmentSize.orEmpty().toSizeLabel(),
            rawSizeBytes = attachmentSize?.toLongOrNull() ?: 0,
            fileKind = (attachmentName ?: "").substringAfterLast('.', "")
                .uppercase().take(4).ifBlank { "FILE" },
            thumbnail = attachmentThumbnail,
            thumbnailTitle = attachmentName,
            remoteKey = attachmentUrl,
            localPath = localFilePath,
        )
    }.let { message ->
        // A text message is the common case, so it is checked last rather than first:
        // every media type above carries its own body already.
        if (messageType == "text") {
            ChatMessage.Text(
                id = uniqueId,
                author = author,
                timestamp = timestamp,
                isOwn = isOwn(),
                sendState = state,
                isEdited = wasEdited,
                createdAt = created,
                isTranslated = isTranslated,
                body = this.message,
                highlights = this.message.detectHighlights(),
            )
        } else {
            message
        }
    }
}

/**
 * A reply, rendered with the same message types as a root.
 *
 * v2's reply adapter switches over the identical set (`ReplyAttachmentVH`, `ReplyAudioVH`,
 * `HomeReplyHolder`), so collapsing every attachment to a file row here would lose the
 * image preview and the voice-note player a reply is allowed to carry.
 */
private fun ChatReplyEntity.toMessage(parentId: String): ChatMessage {
    val id = commentId.ifBlank { "$parentId-reply-$created" }
    val author = senderId.toAuthor()
    val time = created.toClockTime()
    val own = com.zillit.zillitapp.core.directory.UserLookup.currentUserId() == senderId
    val edited = edited > created

    if (attachmentUrl.isNullOrBlank()) {
        return ChatMessage.Text(
            id = id,
            author = author,
            timestamp = time,
            isOwn = own,
            isEdited = edited,
            createdAt = created,
            isTranslated = isTranslated,
            body = message,
            highlights = message.detectHighlights(),
        )
    }

    // The reply row stores the category the same way the root does.
    return when (messageType) {
        "image" -> ChatMessage.Image(
            id = id,
            author = author,
            timestamp = time,
            isOwn = own,
            isEdited = edited,
            createdAt = created,
            isTranslated = isTranslated,
            thumbnail = attachmentThumbnail,
            remoteKey = attachmentUrl,
            caption = message.takeIf { it.isNotBlank() },
        )

        "video" -> ChatMessage.Video(
            id = id,
            author = author,
            timestamp = time,
            isOwn = own,
            isEdited = edited,
            createdAt = created,
            isTranslated = isTranslated,
            thumbnail = attachmentThumbnail,
            remoteKey = attachmentUrl,
            duration = 0L.toDurationLabel(),
            size = attachmentSize.orEmpty().toSizeLabel(),
        )

        "audio" -> ChatMessage.Voice(
            id = id,
            author = author,
            timestamp = time,
            isOwn = own,
            isEdited = edited,
            createdAt = created,
            isTranslated = isTranslated,
            remoteKey = attachmentUrl,
            duration = 0L.toDurationLabel(),
            waveform = id.toWaveform(),
        )

        else -> ChatMessage.Document(
            id = id,
            author = author,
            timestamp = time,
            isOwn = own,
            isEdited = edited,
            createdAt = created,
            isTranslated = isTranslated,
            fileName = attachmentName.orEmpty(),
            fileSize = attachmentSize.orEmpty().toSizeLabel(),
            rawSizeBytes = attachmentSize?.toLongOrNull() ?: 0,
            fileKind = (attachmentName ?: "").substringAfterLast('.', "")
                .uppercase().take(4).ifBlank { "FILE" },
            thumbnail = attachmentThumbnail,
            thumbnailTitle = attachmentName,
            remoteKey = attachmentUrl,
        )
    }
}

/**
 * Resolves a sender id to a display author.
 *
 * Falls back to a neutral placeholder rather than showing the raw id: a Mongo id where a
 * name belongs is worse than "Unknown", and it happens legitimately for someone who has
 * since left the project.
 */
private fun String.toAuthor(): ChatAuthor {
    val user = userDetails()
    // An id with no directory entry means the crew list is behind — someone joined, or
    // was added while the app was backgrounded. Noted so the directory can be refetched
    // once, rather than the row showing "Unknown" for the rest of the session.
    if (user == null) com.zillit.zillitapp.core.directory.UserLookup.noteUnresolved(this)
    return ChatAuthor(
        id = this,
        name = user?.displayName?.takeIf { it.isNotBlank() } ?: UNKNOWN_AUTHOR,
        role = user?.designationName,
        avatarUrl = user?.profilePictureUrl,
        avatarThumbnailKey = user?.profileThumbnailKey,
    )
}

private fun ChatMessageEntity.isOwn(): Boolean =
    com.zillit.zillitapp.core.directory.UserLookup.currentUserId()?.let { it == senderId } ?: false

private fun Int.toSendState(progress: Float): SendState? = when (this) {
    ChatMessageEntity.STATUS_FAILED -> SendState.Failed
    ChatMessageEntity.STATUS_QUEUED -> SendState.Sending
    ChatMessageEntity.STATUS_UPLOADING -> SendState.Uploading(progress)
    ChatMessageEntity.STATUS_CONFIRMED -> SendState.Sent
    else -> null
}


/** The server sends bytes as a string; anything unparseable is shown as-is. */
private fun String.toSizeLabel(): String {
    val bytes = toLongOrNull() ?: return this
    return when {
        bytes >= 1_048_576 -> "%.2f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

/** Project codes and links, highlighted inline as in the design. */
private fun String.detectHighlights(): List<String> =
    highlightPattern.findAll(this).map { it.value }.distinct().toList()

/**
 * A stable pseudo-waveform.
 *
 * Derived from the message id so the same voice note always draws the same shape — a
 * random one would change on every recomposition, which reads as a glitch.
 */
private fun String.toWaveform(): List<Float> {
    var seed = hashCode().toLong() and 0x7fffffff
    return List(WAVEFORM_BARS) {
        seed = (seed * 1103515245 + 12345) and 0x7fffffff
        0.25f + (seed % 100) / 100f * 0.75f
    }
}

private const val UNKNOWN_AUTHOR = "Unknown"

private const val WAVEFORM_BARS = 20

/** Project codes (ZL-1234) and URLs. */
private val highlightPattern = Regex("""\b[A-Z]{2}-\d{3,}\b|https?://\S+""")
