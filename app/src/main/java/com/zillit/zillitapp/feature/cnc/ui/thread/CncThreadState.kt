package com.zillit.zillitapp.feature.cnc.ui.thread

import androidx.compose.runtime.Immutable
import com.zillit.zillitapp.core.ui.chat.model.ChatFeedItem
import com.zillit.zillitapp.core.ui.chat.model.ChatMessage
import com.zillit.zillitapp.feature.cnc.ui.list.ConversationKind
import com.zillit.zillitapp.core.ui.chat.model.ChatMention

/**
 * Everything one C&C conversation shows.
 *
 * Direct and group are the same state, not two — they differ in three details (the
 * subtitle, whether read counts appear, and who may be typing), and modelling them apart
 * would mean two screens to keep in step for the sake of those three.
 */
@Immutable
data class CncThreadUiState(
    val id: String,
    val kind: ConversationKind,
    val title: String,
    val initials: String,
    val pictureKey: String? = null,
    val thumbnailKey: String? = null,
    /** Designation and last-seen for a person; member count for a group. */
    val subtitle: String = "",
    val online: Boolean = false,
    val feed: List<ChatFeedItem> = emptyList(),
    val isLoading: Boolean = false,
    /**
     * Who is typing right now, by display name.
     *
     * A list rather than a flag because a group can have several at once, and the line
     * then reads "3 people are typing…" instead of flickering between names.
     */
    val typing: List<String> = emptyList(),
    /**
     * How many people have read each of your own messages, keyed by message id.
     *
     * Only populated for a group. A direct message has the read tick for this, and a
     * "Read by 1" beside it would say the same thing twice.
     */
    val readCounts: Map<String, Int> = emptyMap(),
    val draft: String? = null,
    val replyingTo: ChatMessage? = null,
    val editing: ChatMessage? = null,
    /** Why you cannot post, or null when you can. */
    val block: ThreadBlock? = null,
    /**
     * Everyone who can be named in this thread.
     *
     * The group's own members, not the whole project: an `@` list offering two hundred
     * people when four are in the room is unusable, and naming somebody who is not in the
     * group would notify nobody.
     */
    val mentionCandidates: List<ChatMention> = emptyList(),
) {
    val isGroup: Boolean get() = kind == ConversationKind.GROUP

    val canPost: Boolean get() = block == null
}

/**
 * The reasons a C&C thread stops accepting messages.
 *
 * Each needs its own sentence — "you blocked them" and "they blocked you" leave the
 * composer in the same state but are not the same situation, and only one of them is
 * yours to undo.
 */
enum class ThreadBlock {
    /** You blocked them. The banner offers to undo it. */
    BLOCKED_BY_ME,

    /** They blocked you. Nothing to offer; the thread is read-only. */
    BLOCKED_ME,

    /** You are no longer in this group. History stays readable. */
    REMOVED_FROM_GROUP,
}
