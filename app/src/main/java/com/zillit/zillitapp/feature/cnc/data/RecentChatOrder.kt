package com.zillit.zillitapp.feature.cnc.data

/**
 * What the Chat tab lists, and in what order.
 *
 * Split out as pure functions over an interface because this is the rule most often got
 * wrong, and the only way to be sure it holds is to be able to test it without a socket,
 * a database or a screen.
 *
 * ### The rule, taken from v2
 * Two separate decisions that are easy to conflate:
 *
 *  - **Who appears** — anyone you have exchanged a message with, *or* anyone with an
 *    unread badge. The badge clause is what makes a first message from someone new show
 *    up at all: there is no conversation yet, so recency alone would hide it.
 *  - **What order** — newest activity first, and nothing else. A badge does **not** lift a
 *    row: a conversation you have not opened stays where its last message put it, so the
 *    list does not reshuffle underneath you as messages arrive elsewhere.
 *
 * Two projects break the first rule on purpose: a personal project lists everyone, and the
 * Contacts tab lists everyone. Both pass [RecentScope] to say so.
 */
object RecentChatOrder {

    /**
     * Apply both decisions.
     *
     * @param scope who is eligible before recency is considered.
     */
    fun <T : RecentChatEntry> arrange(
        entries: List<T>,
        scope: RecentScope = RecentScope.CHATTED_OR_UNREAD,
    ): List<T> = entries
        // The same person can arrive from the recent list and from the project directory in
        // one refresh. Deduplicate before sorting, or they sort next to each other.
        .distinctBy { it.id }
        .filter { scope.includes(it) }
        .sortedByDescending { it.sortingActivity }

    /** True when [entry] belongs on the Chat tab at all. */
    fun isRecent(entry: RecentChatEntry): Boolean =
        entry.sortingActivity > 0 || entry.unread > 0
}

/** Which people a list is allowed to show before ordering them. */
enum class RecentScope {

    /** The Chat tab: anyone talked to, or anyone with something unread. */
    CHATTED_OR_UNREAD,

    /** Favourites: starred, regardless of whether a word has been exchanged. */
    FAVOURITES,

    /**
     * Contacts, and every list inside a personal project.
     *
     * Ordering still applies, so the people you actually talk to sit at the top of an
     * otherwise alphabetical-feeling list.
     */
    EVERYONE,
    ;

    fun includes(entry: RecentChatEntry): Boolean = when (this) {
        CHATTED_OR_UNREAD -> RecentChatOrder.isRecent(entry)
        FAVOURITES -> entry.favourite
        EVERYONE -> true
    }
}

/**
 * The three fields ordering needs, and nothing else.
 *
 * A person and a room both satisfy it, which is what lets one list hold both — the Chat
 * tab interleaves them and they have to sort against each other, not within their kinds.
 */
interface RecentChatEntry {
    val id: String

    /**
     * When this conversation last moved, epoch millis. 0 for one never used.
     *
     * The server seeds it as `sorting_activity`, and every message sent or received
     * afterwards overwrites it with that message's `created`. Keeping the server's name
     * for it is deliberate: the same field, spelled the same way, on every platform.
     */
    val sortingActivity: Long

    /** Unread count from the badge tree. Decides membership, never position. */
    val unread: Int

    val favourite: Boolean
}
