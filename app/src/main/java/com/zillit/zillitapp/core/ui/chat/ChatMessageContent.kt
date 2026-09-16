package com.zillit.zillitapp.core.ui.chat

import com.zillit.zillitapp.core.ui.chat.model.ChatMessage

/**
 * The two questions every chat surface asks of a message: what text does it carry, and what
 * file is behind it.
 *
 * Both were private copies inside the Home view model, which is why the C&C long-press menu
 * offered Copy and Save and neither did anything — there was nothing to reuse, so nothing
 * was wired. Shared here so the second surface gets the same answers as the first.
 */

/** What Copy copies and Edit edits. Empty where there is no text to act on. */
fun ChatMessage.editableBody(): String = when (this) {
    is ChatMessage.Text -> body
    is ChatMessage.Image -> caption.orEmpty()
    else -> ""
}

/** The object key of the file behind a message, or null for one with no file. */
fun ChatMessage.attachmentKey(): String? = when (this) {
    is ChatMessage.Image -> remoteKey
    is ChatMessage.Video -> remoteKey
    is ChatMessage.Voice -> remoteKey
    is ChatMessage.Document -> remoteKey
    else -> null
}

/** What to call the file on disk. Documents carry a real name; the rest take the key's tail. */
fun ChatMessage.attachmentFileName(): String =
    (this as? ChatMessage.Document)?.fileName
        ?: attachmentKey()?.substringAfterLast('/').orEmpty()

/**
 * A media type for a message's file, good enough to file it under the right folder.
 *
 * Taken from the message's own kind rather than the file name: a document sent without an
 * extension still needs somewhere to go, and the kind is never missing.
 */
fun ChatMessage.mimeTypeGuess(): String = when (this) {
    is ChatMessage.Image -> "image/*"
    is ChatMessage.Video -> "video/*"
    is ChatMessage.Voice -> "audio/*"
    else -> "application/octet-stream"
}
