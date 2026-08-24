package com.zillit.zillitapp.core.badge

/**
 * The label keys the backend addresses notifications with.
 *
 * These are the top level of the badge path and match v2's `Constants.*_NOTIFICATION_KEY`
 * exactly. They carry the `_label` suffix because the payload sends `"home_label"`, not
 * `"home"` — matching the bare word silently matches nothing.
 */
object BadgeSection {
    const val HOME = "home_label"
    const val TOOLS = "tools_label"
    const val CNC = "cnc_label"
    const val SETTINGS = "settings_label"
    const val SOS = "sos_label"
    const val EMAIL = "email_label"


    /** The whole project. What the Zillit logo badge counts, and what Delete All clears. */
    const val GLOBAL = "global_label"
}

/**
 * Tool keys that need naming outside the module that owns them.
 */
object BadgeTool {
    /**
     * Calendar invitations, which live under Home but are **not** part of any Home unit's
     * badge.
     *
     * v2 filters them out explicitly (`home_label.filter { it.tool != "calendar_label" }`)
     * and counts them separately as pending/expired invitations. The distinction matters:
     * a unit's badge clears when you open the unit, but an invitation stays unread until it
     * is answered — opening the calendar is not the same as replying to everyone in it.
     */
    const val CALENDAR = "calendar_label"
}

/**
 * Where a badge lives, as the **path the backend already sends**.
 *
 * The server addresses every notification as `section → tool → unit → level_1..n`, echoes
 * that same path back on mark-read, and returns unread counts grouped by it. So the path
 * is the key here too, rather than something derived from it.
 *
 * v2 instead flattened that tree into 238 hand-maintained counters on one god-model, which
 * needed 745 lines to decide which counter a notification belonged to and 3,126 more to
 * keep them in step. Every one of those counters is a **prefix sum** over this path — the
 * Tools tab is "everything under `tools_label`", a unit's badge is "everything under that
 * unit" — so keying by path makes them queries instead of state, and a badge can no longer
 * disagree with the notifications behind it.
 *
 * A null component means **any**: [BadgeKey] doubles as a prefix for reads. Fields are
 * ordered outermost-first, and a prefix may not skip a level — see [matchesPrefixOf].
 *
 * @param levels `level_1`, `level_2`, … in order. Open-ended because Drive nests folders
 *   arbitrarily deep and the backend extends the same path with `levels[]` rather than
 *   inventing new fields.
 */
data class BadgeKey(
    val projectId: String? = null,
    val section: String? = null,
    val tool: String? = null,
    val unit: String? = null,
    val levels: List<String> = emptyList(),
) {

    /**
     * The level path as one string, so a prefix match is a single `BEGINSWITH`.
     *
     * Realm cannot prefix-match a list, and comparing `level_1`/`level_2`/`level_3` as
     * separate columns cannot express "anything below this folder" at all once the depth
     * is open-ended.
     *
     * Trailing separator included so `a/b` does not match `a/bc` — without it, a folder
     * named `Set` would swallow the badges of `Set Design`.
     */
    val levelPath: String
        get() = if (levels.isEmpty()) "" else levels.joinToString(SEPARATOR, postfix = SEPARATOR)

    /** A row's identity. Includes the source — see [BadgeSource]. */
    fun rowId(source: String): String =
        listOf(projectId.orEmpty(), section.orEmpty(), tool.orEmpty(), unit.orEmpty(), levelPath, source)
            .joinToString("|")

    companion object {
        const val SEPARATOR = "/"

        /** Everything unread in one project — the project-list row, and the Zillit logo. */
        fun project(projectId: String) = BadgeKey(projectId = projectId)

        /** A whole section within a project, e.g. the Home tab total. */
        fun section(projectId: String, section: String) =
            BadgeKey(projectId = projectId, section = section)

        /** One Home unit — Bulletin, Call Sheet, or a custom unit. */
        fun homeUnit(projectId: String, unitId: String) =
            BadgeKey(projectId = projectId, section = BadgeSection.HOME, unit = unitId)

        /** One tool, and optionally a unit and levels inside it. */
        fun tool(
            projectId: String,
            tool: String,
            unit: String? = null,
            levels: List<String> = emptyList(),
        ) = BadgeKey(
            projectId = projectId,
            section = BadgeSection.TOOLS,
            tool = tool,
            unit = unit,
            levels = levels,
        )
    }
}

/**
 * Whether [this] prefix selects [row].
 *
 * A null component matches anything, and levels match by prefix so a folder's badge
 * includes everything nested under it. Used for in-memory checks; the Realm query in
 * [BadgeManager] applies the same rule.
 */
fun BadgeKey.matchesPrefixOf(row: BadgeKey): Boolean {
    if (projectId != null && projectId != row.projectId) return false
    if (section != null && section != row.section) return false
    if (tool != null && tool != row.tool) return false
    if (unit != null && unit != row.unit) return false
    return row.levelPath.startsWith(levelPath)
}

/**
 * The path as one readable line, for logs.
 *
 * `*` marks a component the prefix left open, so a read that cleared more than intended is
 * obvious at a glance rather than needing the counts compared.
 */
fun BadgeKey.describe(): String = buildString {
    append(projectId ?: "*")
    append(" / ").append(section ?: "*")
    if (tool != null) append(" / ").append(tool)
    if (unit != null) append(" / ").append(unit)
    if (levels.isNotEmpty()) append(" / ").append(levels.joinToString(BadgeKey.SEPARATOR))
}
