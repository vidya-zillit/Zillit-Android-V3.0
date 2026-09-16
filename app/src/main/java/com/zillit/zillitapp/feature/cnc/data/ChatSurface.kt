package com.zillit.zillitapp.feature.cnc.data

import com.zillit.zillitapp.BuildConfig

/**
 * A socket chat, identified by its event prefix and its REST base.
 *
 * ### Why this is not [com.zillit.zillitapp.core.chat.data.ChatModule]
 * The app has **two kinds of chat** and they are deliberately kept apart.
 *
 *  - **Unit chat** — Home units, Production Report, Forms. HTTP throughout: `POST /chat` to
 *    send, paged `GET` to read. That is `ChatModule` and `ChatRepository`, and nothing here
 *    touches them.
 *  - **Socket chat** — C&C and Budget. Sends by emitting on the socket and waiting for an
 *    acknowledgement; REST is used only to read history. That is this package.
 *
 * They share how a message *looks* and nothing about how it travels, so the rendering is
 * common and the data layers are separate.
 *
 * ### Why one enum covers C&C and Budget
 * Budget is C&C's protocol with `budget:` in front of every event name and its own REST
 * paths. Nothing else differs. v2 expresses that as two screens of about 2,900 and 2,700
 * lines that have since drifted apart; here it is two values.
 */
enum class ChatSurface(val key: String, private val prefix: String, private val restSegment: String) {

    /** Chat & Calling — the module's own chat. */
    CNC("cnc", prefix = "", restSegment = ""),

    /** The chat attached to a budget. Same protocol, prefixed. */
    BUDGET("budget", prefix = "budget:", restSegment = "budget-"),
    ;

    // ── Socket events ────────────────────────────────────────────────────────

    val privateMessage: String get() = "${prefix}private_chat"
    val privateDelete: String get() = "${prefix}private-chat:delete-messages"
    val privateEdit: String get() = "${prefix}private-chat:edit"
    val privateTyping: String get() = "${prefix}private-chat:typing"
    /**
     * Message expiry.
     *
     * **C&C only.** v2 has no budget-prefixed expiry events, because retention is a C&C
     * setting. The name is still generated for Budget so the subscription is uniform; it
     * simply never fires there.
     */
    val privateExpired: String get() = "${prefix}private-chat:message-expired"

    val groupMessage: String get() = "${prefix}group_chat"
    val groupDelete: String get() = "${prefix}group-chat:delete-messages"
    val groupReadUntil: String get() = "${prefix}group-chat:read-untill"
    val groupEdit: String get() = "${prefix}group-chat:edit"
    val groupTyping: String get() = "${prefix}group-chat:typing"
    val groupExpired: String get() = "${prefix}group-chat:message-expired"

    val roomCreated: String get() = "${prefix}chat-room:create"
    val roomRemoved: String get() = "${prefix}chat-room:remove"
    val roomUpdated: String get() = "${prefix}chat-room:updated"

    val reaction: String get() = "${prefix}update_reaction"

    /**
     * The read watermark on a 1-1 thread.
     *
     * Prefixed like everything else. An earlier version of this file claimed it was the one
     * event that kept its name on both surfaces — that was wrong. v2's budget handler sends
     * `budget:private_chat_message_read_untill`, so leaving it unprefixed would have pushed
     * every Budget read receipt onto the C&C channel, where nothing is listening for it.
     *
     * The mapping has **no** irregularities: prefix everything.
     */
    val privateReadUntil: String get() = "${prefix}private_chat_message_read_untill"

    /**
     * The `chat_tool` every payload carries, which the receiving side filters on.
     *
     * Not a plain property because Budget is two tools, not one: a thread attached to a
     * department is `department_budget_tool` and the project-level one is
     * `main_budget_tool`. Getting this wrong does not fail loudly — the other client simply
     * ignores the event.
     */
    fun chatTool(departmentId: String? = null): String = when (this) {
        CNC -> "cnc_section"
        BUDGET -> if (departmentId.isNullOrBlank()) "main_budget_tool" else "department_budget_tool"
    }

    /** Who this user has chatted with. C&C and Budget name this differently. */
    val recentList: String
        get() = when (this) {
            CNC -> "user:list"
            BUDGET -> "budget:recent:list"
        }

    // ── REST ─────────────────────────────────────────────────────────────────

    /** Chat rooms: list, create and update all hang off this one path. */
    val rooms: String get() = "${BASE}chat-room"
    val roomLeave: String get() = "${BASE}chat-room/leave-group"
    val roomEditAdmin: String get() = "${BASE}chat-room/edit-admin"
    val roomPicture: String get() = "${BASE}chat-room/update-group-picture"

    val privateHistory: String get() = "$BASE${restSegment}private-chat/messages"
    val groupHistory: String get() = "$BASE${restSegment}group-chat/messages"
    val groupReadBy: String get() = "$BASE${restSegment}group-chat/readby"
    val groupArchive: String get() = "$BASE${restSegment}group-chat/archive"
    val groupArchiveStatus: String get() = "$BASE${restSegment}group-chat/archive-status"

    companion object {
        private val BASE = "${BuildConfig.CHAT_BASE_URL}/api/v2/"

        /** Favourites are per user, not per surface — Budget has no starred list. */
        val FAVOURITE_LIST = "${BASE}favourite-user"
        val FAVOURITE_ADD = "${BASE}favourite-user/add"
        val FAVOURITE_REMOVE = "${BASE}favourite-user/remove"

        /** Block, unblock and who may reach whom. */
        val COMMUNICATION_SETTINGS = "${BASE}communication-settings"
        val COMMUNICATION_BLOCK = "${BASE}communication-settings/add"
        val COMMUNICATION_LIST = "${BASE}communication-settings/list"
    }
}

/** Whether a conversation is with one person or a room. */
enum class ChatKind { DIRECT, GROUP }
