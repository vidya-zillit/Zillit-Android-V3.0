package com.zillit.zillitapp.core.ui.chat.model

import com.zillit.zillitapp.core.common.RelativeDay

/**
 * A chat author. Avatar is drawn from [initials] until real images are wired.
 */
data class ChatAuthor(
    val id: String,
    val name: String,
    /** e.g. "Director", "Driver" — shown in brackets after the name, as in v2. */
    val role: String? = null,
    /** Object key of the author's profile picture — not a URL. See `UserAvatar`. */
    val avatarUrl: String? = null,
    /** Object key of its thumbnail, which is what an avatar actually loads. */
    val avatarThumbnailKey: String? = null,
) {
    val initials: String
        get() = name.trim().split(Regex("\\s+"))
            .mapNotNull { it.firstOrNull { c -> c.isLetter() }?.uppercaseChar() }
            .take(2).joinToString("").ifEmpty { "?" }

    /** "Vidya Pixel (Driver)" — the exact shape v2 renders. */
    val displayName: String
        get() = if (role.isNullOrBlank()) name else "$name ($role)"
}

/**
 * Where a message is between "typed" and "read by everyone".
 *
 * A sealed hierarchy rather than an enum because two of the states carry data or an
 * action: an upload has a percentage, and a failure has a retry. Modelling those as an
 * enum plus loose `progress`/`hasFailed` fields lets a bubble show a tick and a progress
 * ring at the same time, which is exactly the bug this shape prevents.
 */
sealed interface SendState {

    /** File still going up. [progress] is 0f..1f; the bubble shows the percentage. */
    data class Uploading(val progress: Float) : SendState

    /** Uploaded (or text-only), waiting on the server to acknowledge. */
    data object Sending : SendState

    data object Sent : SendState
    data object Delivered : SendState
    data object Read : SendState

    /** Send or upload failed. The bubble becomes tappable to try again. */
    data object Failed : SendState
}

/**
 * One row in the thread.
 *
 * The feed is a list of **posts**, not a flat list of messages: v2 hangs replies off the
 * parent as `comments`, so a reply is never its own row. Modelling it the same way here
 * means the list can't drift into the flat shape by accident — there is no way to add a
 * reply except inside the post it belongs to.
 */
sealed interface ChatFeedItem {

    /**
     * Day divider — "Today", "Yesterday", or a date.
     *
     * Carries the [RelativeDay] rather than finished text: "Today" and "Yesterday" are app
     * copy and must come from `strings.xml`, and a mapper has no locale to resolve them in.
     * [ChatThread] turns this into words at render time.
     */
    data class Day(val day: RelativeDay) : ChatFeedItem {
        val id: String get() = "day-$day"
    }

    /**
     * A root message together with every reply made to it.
     *
     * @param replies rendered inside the same card as [root], below it. Empty for the
     *   common case, which costs nothing.
     */
    /**
     * The "unread from here" divider.
     *
     * v2 marks it on the first message newer than the last-read stamp and not sent by you
     * (`showUnreadHeader`). A separate feed item rather than a flag on the post, because it
     * belongs between two messages, not to either of them.
     */
    data object UnreadDivider : ChatFeedItem {
        const val ID = "unread-divider"
    }

    data class Post(
        val root: ChatMessage,
        /**
         * Files posted in the same batch as [root].
         *
         * v2 groups by `message_group` so that picking five photos produces one row with a
         * grid, not five rows — a batch is one action and reads as one post. Empty for an
         * ordinary single-file or text message.
         */
        val batch: List<ChatMessage> = emptyList(),
        val replies: List<ChatMessage> = emptyList(),
        /**
         * The server's id for [root], or null while the send is still pending.
         *
         * Replying, deleting and archiving all address the server id, so its absence is
         * what tells the UI those actions are not available yet.
         */
        val rootServerId: String? = null,
    ) : ChatFeedItem {
        val id: String get() = root.id
    }
}

/**
 * The content of a message — root or reply.
 *
 * A sealed hierarchy rather than one class with nullable fields for every media kind:
 * the renderer then gets exhaustiveness checking, and adding a type is a compile error
 * at every place that must handle it rather than a silently-blank bubble.
 *
 * Deleted messages are **not** modelled. v2 kept a "Message Deleted" placeholder row;
 * here a deleted message is dropped from the feed entirely, so there is no state for a
 * renderer to get wrong.
 */
sealed interface ChatMessage {
    /** True when tapping should open the media viewer — i.e. there is a file to show. */
    val hasOpenableMedia: Boolean
        get() = this is Image || this is Video || this is Document

    val id: String
    val author: ChatAuthor
    val timestamp: String
    val isOwn: Boolean
    val sendState: SendState?

    /**
     * True once the message has been edited.
     *
     * v2 marks it by comparing `edited > updated` and prefixing the timestamp in red;
     * carried as a flag so the footer decides how to render it and the copy stays in
     * strings.xml.
     */
    val isEdited: Boolean get() = false

    /**
     * When it was posted, epoch millis.
     *
     * Needed for the 30-minute edit/delete window; [timestamp] is already formatted for
     * display and cannot be compared.
     */
    val createdAt: Long get() = 0L

    /**
     * True once this device has translated it.
     *
     * Hides the option — translating a body that is already original-plus-translation
     * would compound it — and tells the bubble to render the two halves differently.
     */
    val isTranslated: Boolean get() = false

    /**
     * Emoji reactions on this message, already tallied.
     *
     * Counted rather than listed: the row under a bubble shows "👍 3", and every client
     * that has ever rendered the raw list has ended up re-tallying it at draw time. One
     * entry per distinct emoji, so the row is the row.
     */
    val reactions: List<ChatReaction> get() = emptyList()

    /**
     * The message this one answers, when it answers one.
     *
     * A socket chat models a reply as an ordinary message carrying a quotation, not as a
     * child of what it answers — which is why [ChatFeedItem.Post.replies] stays empty there.
     * A unit chat is the other way round: a reply really is a comment hanging off a post.
     * Both shapes are real and this is the first one.
     */
    val quoted: QuotedMessage? get() = null

    data class Text(
        override val id: String,
        override val author: ChatAuthor,
        override val timestamp: String,
        override val isOwn: Boolean = false,
        override val sendState: SendState? = null,
        override val isEdited: Boolean = false,
        override val createdAt: Long = 0L,
        override val isTranslated: Boolean = false,
        override val reactions: List<ChatReaction> = emptyList(),
        override val quoted: QuotedMessage? = null,
        val body: String,
        /** Substrings rendered in the brand colour — project codes, links. */
        val highlights: List<String> = emptyList(),
        /**
         * People named in this message.
         *
         * The body on the wire carries `@<userId>`, not `@<name>`: an id survives a rename
         * and is the same on every client, where a name is neither. The substitution back to
         * a readable name happens at render, from these pairs.
         */
        val mentions: List<ChatMention> = emptyList(),
    ) : ChatMessage

    data class Image(
        override val id: String,
        override val author: ChatAuthor,
        override val timestamp: String,
        override val isOwn: Boolean = false,
        override val sendState: SendState? = null,
        override val isEdited: Boolean = false,
        override val createdAt: Long = 0L,
        override val isTranslated: Boolean = false,
        override val reactions: List<ChatReaction> = emptyList(),
        override val quoted: QuotedMessage? = null,
        /** Object key of the full file, for download-on-tap. */
        val remoteKey: String? = null,
        /** Local copy while still uploading — rendered before any network call. */
        val localPath: String? = null,
        /** Null when the backend has no thumbnail yet; the preview area is then hidden. */
        val thumbnail: String? = null,
        val caption: String? = null,
    ) : ChatMessage

    data class Video(
        override val id: String,
        override val author: ChatAuthor,
        override val timestamp: String,
        override val isOwn: Boolean = false,
        override val sendState: SendState? = null,
        override val isEdited: Boolean = false,
        override val createdAt: Long = 0L,
        override val isTranslated: Boolean = false,
        override val reactions: List<ChatReaction> = emptyList(),
        override val quoted: QuotedMessage? = null,
        /** Object key of the full file, for download-on-tap. */
        val remoteKey: String? = null,
        /** Local copy while still uploading — rendered before any network call. */
        val localPath: String? = null,
        val thumbnail: String? = null,
        val duration: String,
        val size: String,
    ) : ChatMessage

    data class Voice(
        override val id: String,
        override val author: ChatAuthor,
        override val timestamp: String,
        override val isOwn: Boolean = false,
        override val sendState: SendState? = null,
        override val isEdited: Boolean = false,
        override val createdAt: Long = 0L,
        override val isTranslated: Boolean = false,
        override val reactions: List<ChatReaction> = emptyList(),
        override val quoted: QuotedMessage? = null,
        /** Object key of the audio file, downloaded before playback. */
        val remoteKey: String? = null,
        /** Local copy — a note recorded here that has not uploaded yet. */
        val localPath: String? = null,
        val duration: String,
        /** Waveform bar heights, 0f..1f. */
        val waveform: List<Float>,
    ) : ChatMessage

    /**
     * Covers v2's `doc` and `attachment` types, which share one view type there too —
     * [fileKind] is what distinguishes them.
     */
    data class Document(
        override val id: String,
        override val author: ChatAuthor,
        override val timestamp: String,
        override val isOwn: Boolean = false,
        override val sendState: SendState? = null,
        override val isEdited: Boolean = false,
        override val createdAt: Long = 0L,
        override val isTranslated: Boolean = false,
        override val reactions: List<ChatReaction> = emptyList(),
        override val quoted: QuotedMessage? = null,
        val fileName: String,
        /** Human-readable, e.g. "1.2 MB". */
        val fileSize: String,
        /** The unformatted byte count; Document Distribution needs the number. */
        val rawSizeBytes: Long = 0,
        val fileKind: String,
        val remoteKey: String? = null,
        val localPath: String? = null,
        /**
         * Page thumbnail. Null for anything the backend can't render a preview of — a
         * spreadsheet, an archive — and the bubble then collapses to the file row alone.
         */
        val thumbnail: String? = null,
        val thumbnailTitle: String? = null,
    ) : ChatMessage

    data class Location(
        override val id: String,
        override val author: ChatAuthor,
        override val timestamp: String,
        override val isOwn: Boolean = false,
        override val sendState: SendState? = null,
        override val isEdited: Boolean = false,
        override val createdAt: Long = 0L,
        override val isTranslated: Boolean = false,
        override val reactions: List<ChatReaction> = emptyList(),
        override val quoted: QuotedMessage? = null,
        val placeName: String,
        val address: String,
        /** Kept so the bubble can hand the pin to a maps app; 0.0 when unknown. */
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        /**
         * The map snapshot. A location **is** an attachment on the wire — the sender's
         * client renders the map once and uploads the picture, and every other client shows
         * that picture rather than drawing its own. Same three keys as [Image], for the same
         * loader: local copy while uploading, thumbnail first, full file when it lands.
         */
        val remoteKey: String? = null,
        val thumbnail: String? = null,
        val localPath: String? = null,
    ) : ChatMessage {
        val hasCoordinates: Boolean get() = latitude != 0.0 || longitude != 0.0
    }
}

/**
 * One emoji on one message, with how many people chose it.
 *
 * [isMine] drives the highlight and the toggle: tapping a reaction you already gave takes
 * it back, which is what every chat app does and what the server's empty-string reaction
 * means. Without the flag the same tap would add a second identical reaction from you.
 */
data class ChatReaction(
    val emoji: String,
    val count: Int,
    val isMine: Boolean,
)

/**
 * The six offered on long-press, in v2's order.
 *
 * Fixed rather than "recently used": a production crew reacting to a call sheet wants the
 * same six in the same places every time, and a row that reorders itself costs a glance.
 * The full keyboard sits behind the last entry for anything else.
 */
val QUICK_REACTIONS = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2F", "\uD83D\uDE22", "\uD83D\uDE4F")

/**
 * One person named in a message.
 *
 * [name] is carried with the id rather than looked up, because the person may have left the
 * project since: v2 falls back to the name it was sent with for exactly that case, so the
 * message still reads correctly instead of showing a raw id.
 */
data class ChatMention(
    val userId: String,
    val name: String,
)

/**
 * What a reply is answering, as much of it as the bubble shows.
 *
 * Carried on the reply rather than looked up: the message being quoted may be older than
 * anything loaded, or deleted since, and a quotation that disappears because the original
 * scrolled out of the window is worse than one that stays.
 */
data class QuotedMessage(
    /** Server id of the message being quoted; what a tap on the quotation goes to. */
    val messageId: String,
    val authorName: String,
    /** The text, or a description of the file when there is no text. */
    val preview: String,
    val isOwn: Boolean = false,
)
