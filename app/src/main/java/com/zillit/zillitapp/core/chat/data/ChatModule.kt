package com.zillit.zillitapp.core.chat.data

import com.zillit.zillitapp.BuildConfig

/**
 * A chat surface, identified by the base URL its endpoints hang off.
 *
 * Every chat in v2 exposes the identical five endpoints under a different prefix:
 *
 * ```
 * GET    {base}/chat/{scopeId}/{timestamp}/previous
 * GET    {base}/chat/{scopeId}/{timestamp}/next
 * POST   {base}/chat
 * PUT    {base}/chat/delete/chats            { "chatIds": [...] }
 * POST   {base}/chat/comments/{chatId}
 * PUT    {base}/chat/comments/{chatId}/{commentId}
 * DELETE {base}/chat/comments/{chatId}/{commentId}
 * ```
 *
 * So the module is data, not a class hierarchy: adding Catering later is one entry here,
 * not another repository. This is the same reasoning that put the chat UI in `core/ui/chat`.
 */
enum class ChatModule(val key: String, private val base: String) {

    /** Home units — Bulletin, Call Sheet and any custom unit. */
    HOME("home", "${BuildConfig.UNITS_BASE_URL}api/v2/home"),
    ;

    val listBase: String get() = "$base/chat"
    val send: String get() = "$base/chat"
    val delete: String get() = "$base/chat/delete/chats"
    val readBy: String get() = "$base/chat/readby"
    val archive: String get() = "$base/chat/archive"

    /**
     * Nudges everyone who has not read a message.
     *
     * Scoped to the unit rather than the message because the server resolves the audience
     * from the unit's user list; the message is a query parameter on it.
     */
    fun notify(scopeId: String) = "$base/unit/$scopeId"

    /**
     * The watermarked copy of a Call Sheet document.
     *
     * Call sheets carry the reader's name stamped across them, so opening or saving one
     * goes through here rather than at the raw object key — the point of the watermark is
     * that a leaked copy is traceable, and the un-stamped file defeats it.
     */
    fun watermark(chatServerId: String) = "$base/chat/watermark/$chatServerId"

    fun previousPage(scopeId: String, timestamp: Long) = "$listBase/$scopeId/$timestamp/previous"

    /**
     * History — everything deleted or replaced in this thread.
     *
     * The **same** list endpoint. What makes it history is the `deleted` query parameter,
     * which flips the server from "current messages" to "superseded ones". That is why
     * history needs no module of its own: one repository, one extra parameter.
     */
    fun historyPage(scopeId: String, timestamp: Long) = previousPage(scopeId, timestamp)
    fun nextPage(scopeId: String, timestamp: Long) = "$listBase/$scopeId/$timestamp/next"
    fun addReply(chatId: String) = "$base/chat/comments/$chatId"
    fun editReply(chatId: String, commentId: String) = "$base/chat/comments/$chatId/$commentId"
    fun deleteReply(chatId: String, commentId: String) = "$base/chat/comments/$chatId/$commentId"
}

/**
 * The three Home units that behave differently.
 *
 * Derived from the unit's server `identifier`, not its name — names are label keys that
 * change with language, and custom units have arbitrary ones.
 */
enum class HomeUnitKind {
    /** Notices/Bulletin and every custom unit: the ordinary chat. */
    BULLETIN,

    /**
     * Call Sheet. Documents only, no text and no voice note, and a new upload **replaces**
     * what is already posted after a two-step confirmation.
     */
    CALL_SHEET,

    /** A separate module entirely — not a chat. Not implemented yet. */
    CALENDAR,
    ;

    companion object {
        const val IDENTIFIER_CALENDAR = "home_unit_calendar"
        const val IDENTIFIER_CALL_SHEET = "home_unit_call_sheet"
        const val IDENTIFIER_NOTICES = "home_unit_notices"

        fun from(identifier: String?): HomeUnitKind = when (identifier) {
            IDENTIFIER_CALENDAR -> CALENDAR
            IDENTIFIER_CALL_SHEET -> CALL_SHEET
            // Everything else — Notices and every unit a production creates — is an
            // ordinary chat. Defaulting this way means a new custom unit works without
            // a code change, which is the point of the identifier being open-ended.
            else -> BULLETIN
        }
    }
}
