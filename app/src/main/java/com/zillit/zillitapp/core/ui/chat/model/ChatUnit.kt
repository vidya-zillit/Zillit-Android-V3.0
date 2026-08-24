package com.zillit.zillitapp.core.ui.chat.model

import com.zillit.zillitapp.core.chat.data.HomeUnitKind

/**
 * One unit in the Home strip. Each unit is a **separate chat thread** — switching tabs
 * swaps the whole message list, it is not a filter over one stream.
 *
 * @param name already resolved from the server label key. Units are named server-side
 *   with keys like `home_unit_notices`, so the raw value is never shown.
 * @param badgeCount unread messages in this unit. Drives both the per-tab badge and the
 *   red dot on the overflow arrow.
 * @param canPost v2's `posting_access`. When false the composer is replaced by a notice —
 *   read-only units are common (Notices, Call Sheets).
 * @param isPrivate v2's `private_unit`. Visible only to its team members.
 * @param canDownload v2's `download_access`. Save is refused without it, per unit — a
 *   crew member can often read a call sheet without being allowed to take it off set.
 */
data class ChatUnit(
    val id: String,
    val name: String,
    val badgeCount: Int = 0,
    val canPost: Boolean = true,
    val canDownload: Boolean = true,
    val isPrivate: Boolean = false,
    /**
     * How this unit behaves. Derived from the server `identifier`, so a unit a production
     * creates is an ordinary chat without any code change.
     */
    val kind: HomeUnitKind = HomeUnitKind.BULLETIN,
)
