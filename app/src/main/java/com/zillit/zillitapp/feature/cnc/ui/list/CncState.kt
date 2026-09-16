package com.zillit.zillitapp.feature.cnc.ui.list

import androidx.compose.runtime.Immutable
import com.zillit.zillitapp.feature.cnc.data.RecentChatEntry

/** The three things the C&C tab shows. */
enum class CncTab { CHAT, CALL, CONTACTS }

/**
 * What the Chat list is filtered to.
 *
 * Unread and Favourites are filters over the same list rather than separate fetches — the
 * server returns one recent list and these narrow it, which is why switching is instant.
 */
enum class ChatFilter { ALL, UNREAD, MEMBERS, GROUPS, DEPARTMENTS, FAVOURITES }

/** Recent calls, or only the ones nobody answered. */
enum class CallFilter { RECENT, MISSED }

/** Whether a row is a person or a room. */
enum class ConversationKind { DIRECT, GROUP }

/**
 * One row in the Chat list.
 *
 * Carries both the person's own fields and a group's, because the list mixes them and the
 * row draws the same shape either way — a group's "role" line is its member count.
 */
@Immutable
data class ConversationRow(
    override val id: String,
    val name: String,
    val kind: ConversationKind,
    /** Designation for a person, "22 members in this group" for a room. */
    val subtitle: String,
    val initials: String,
    val pictureKey: String? = null,
    val thumbnailKey: String? = null,
    /**
     * The other person's device id, for presence.
     *
     * Presence is keyed by device rather than by user, so a row cannot show a dot without
     * one. Null for a group, which never shows presence.
     */
    val deviceId: String? = null,
    /** Already worded for display; [sortingActivity] is what actually orders the list. */
    val lastActivity: String = "",
    /** When this conversation last moved, epoch millis. See [RecentChatEntry]. */
    override val sortingActivity: Long = 0L,
    override val unread: Int = 0,
    val online: Boolean = false,
    /** A department's own room, which belongs under Departments rather than Groups. */
    val isDepartmentRoom: Boolean = false,
    override val favourite: Boolean = false,
    /** Appended after the name, as v2 does: "Sanjeev Khanna · Admin". */
    val isProjectAdmin: Boolean = false,
) : RecentChatEntry

/** Which way a call went, and whether it connected. */
enum class CallDirection { INCOMING, OUTGOING, MISSED }

@Immutable
data class CallRow(
    val id: String,
    val name: String,
    val subtitle: String,
    val initials: String,
    val pictureKey: String? = null,
    val thumbnailKey: String? = null,
    val direction: CallDirection,
    val timestamp: String,
    val kind: ConversationKind = ConversationKind.DIRECT,
)

/**
 * One call, opened from its row.
 *
 * Built once when the sheet opens rather than carried on every [CallRow]: a log page is
 * fifty rows and only one of them is ever looked at in detail.
 */
@Immutable
data class CallDetails(
    val id: String,
    val title: String,
    val initials: String,
    val pictureKey: String? = null,
    val thumbnailKey: String? = null,
    val direction: CallDirection,
    val isVideo: Boolean,
    val isGroup: Boolean,
    /** "12 Aug at 09:18". */
    val startedAt: String,
    /** "4 min 12 sec", or empty for a call nobody answered. */
    val duration: String,
    val participants: List<CallParticipant> = emptyList(),
)

/** One person's part in a call, already worded. */
@Immutable
data class CallParticipant(
    val userId: String,
    val name: String,
    val initials: String,
    val pictureKey: String? = null,
    val thumbnailKey: String? = null,
    /** "Joined", "Declined", "Missed", "Started the call". */
    val outcome: CallOutcome,
    /** How long they were connected. Empty when they never were. */
    val duration: String = "",
    /**
     * When they answered — "05:40 PM". Null for anyone who never did.
     *
     * The clock time rather than an offset from the start: a call's log is read alongside
     * everything else that happened that afternoon, and "05:40 PM" lines up with the rest
     * of it where "+43s" does not.
     */
    val joinedAt: String? = null,
)

/** Why a person's row reads the way it does. Drives the colour as well as the words. */
enum class CallOutcome { CALLER, JOINED, DECLINED, MISSED, NO_ANSWER }

/**
 * What a long press on a group row offers.
 *
 * Everything here is also reachable by opening the group, which is the point: a long press
 * is a shortcut to the two actions somebody came to the list to perform, not a second place
 * where the rules live. The gating is the same as the details screen's.
 */
@Immutable
data class ConversationRowActions(
    val conversationId: String,
    val name: String,
    val canLeave: Boolean,
    val canDelete: Boolean,
)

/** A person in the Contacts tab, where every row carries its own actions. */
@Immutable
data class ContactRow(
    val id: String,
    val name: String,
    val designation: String,
    val initials: String,
    val pictureKey: String? = null,
    val thumbnailKey: String? = null,
    /** For the presence dot. See [ConversationRow.deviceId]. */
    val deviceId: String? = null,
    val online: Boolean = false,
    val isProjectAdmin: Boolean = false,
    val favourite: Boolean = false,
)

@Immutable
data class CncUiState(
    val tab: CncTab = CncTab.CHAT,
    val chatFilter: ChatFilter = ChatFilter.ALL,
    /**
     * Which chips this project actually offers.
     *
     * Not every project has every list: a personal project has no departments and no
     * favourites, and a member still waiting to be accepted has neither groups nor
     * favourites yet. v2 decides the same thing by adding tabs conditionally; stating it as
     * data means the screen renders what it is given instead of repeating the rules.
     */
    val availableChatFilters: List<ChatFilter> = ChatFilter.entries,
    val callFilter: CallFilter = CallFilter.RECENT,
    val query: String = "",
    val conversations: List<ConversationRow> = emptyList(),
    val calls: List<CallRow> = emptyList(),
    val contacts: List<ContactRow> = emptyList(),
    val loading: Boolean = true,
    /** Unread totals per filter, shown on the chips. */
    val unreadTotal: Int = 0,
    val missedTotal: Int = 0,
    /** Group creation is gated on admin rights in a personal project, and on not being pending. */
    val canCreateGroup: Boolean = true,
) {
    val hasQuery: Boolean get() = query.isNotBlank()
}
