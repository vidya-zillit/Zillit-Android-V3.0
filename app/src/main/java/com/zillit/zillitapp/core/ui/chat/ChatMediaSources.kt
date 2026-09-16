package com.zillit.zillitapp.core.ui.chat

import com.zillit.zillitapp.core.ui.chat.model.ChatFeedItem
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage

/**
 * The two lists every chat screen needs to show media, derived from the feed it already has.
 *
 * Both used to live inside the Home view model, which meant C&C either duplicated thirty
 * lines of per-type extraction or — what actually happened — shipped with the media tap
 * wired to an empty lambda. Same feed, same viewer, same gallery, so one derivation. See
 * the reuse rule: a second copy is a second place for "Docs is empty" to be true in one
 * chat and not the other.
 *
 * Deriving rather than fetching is deliberate and matches v2: the messages are already
 * loaded, and a separate endpoint could disagree with the thread the user is looking at.
 */

/**
 * Every message in the feed, roots and batch members alike.
 *
 * The batch matters: files picked in one action render as a single bubble, and a gallery
 * that only walked the roots would show one photo out of the six that were sent together.
 */
fun List<ChatFeedItem>.chatLibraryMessages(): List<ChatMessage> =
    filterIsInstance<ChatFeedItem.Post>().flatMap { listOf(it.root) + it.batch }

/**
 * Everything the full-screen viewer can page through.
 *
 * Roots only, unlike the gallery. The viewer pages one bubble at a time, and a batch is one
 * bubble.
 */
fun List<ChatFeedItem>.viewableMedia(): List<ViewableMedia> =
    filterIsInstance<ChatFeedItem.Post>()
        .mapNotNull { post -> post.root.toViewableMedia() }

/** Null for anything with no file behind it — text, location, a contact card. */
fun ChatMessage.toViewableMedia(): ViewableMedia? {
    if (!hasOpenableMedia) return null

    return ViewableMedia(
        id = id,
        remoteKey = when (this) {
            is ChatMessage.Image -> remoteKey
            is ChatMessage.Video -> remoteKey
            is ChatMessage.Document -> remoteKey
            else -> null
        },
        thumbnailKey = when (this) {
            is ChatMessage.Image -> thumbnail
            is ChatMessage.Video -> thumbnail
            is ChatMessage.Document -> thumbnail
            else -> null
        },
        localPath = when (this) {
            is ChatMessage.Image -> localPath
            is ChatMessage.Video -> localPath
            is ChatMessage.Document -> localPath
            else -> null
        },
        fileName = (this as? ChatMessage.Document)?.fileName ?: id,
        caption = (this as? ChatMessage.Image)?.caption,
        // Name only. `displayName` appends the designation, which is a raw server label key
        // with no composable in scope here to resolve it.
        authorName = author.name,
        timestamp = timestamp,
        isImage = this is ChatMessage.Image,
    )
}
