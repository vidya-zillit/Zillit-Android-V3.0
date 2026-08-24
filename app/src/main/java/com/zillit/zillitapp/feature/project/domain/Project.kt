package com.zillit.zillitapp.feature.project.domain

import com.zillit.zillitapp.core.common.initials

/**
 * A project as the UI needs it.
 *
 * Separate from both the API DTO and the Realm entity on purpose: the backend model has
 * ~50 fields, most of which no screen reads, and the UI should not have to know which
 * of them are nullable on the wire.
 */
data class Project(
    val id: String,
    val userId: String,
    val name: String,
    val type: String?,
    val subType: String?,
    val code: String?,
    val companyName: String?,
    /** The production's language. Null when the server did not send one. */
    val language: String?,
    val isAdmin: Boolean,
    val isFavourite: Boolean,
    val enabled: Boolean,
    val enterpriseClientId: String?,
    val unreadCount: Int,
    val createdAt: Long?,
    /** Set when the project is pending deletion; the list shows a countdown. */
    val deletionHoursRemaining: Long?,
) {
    val isPendingDeletion: Boolean get() = deletionHoursRemaining != null

    /**
     * Initials for the avatar tile, e.g. "The Long Night" → "TL".
     *
     * Delegates to the shared extension, which skips non-letters — the previous inline
     * version took each word's first character blindly, so "2 States" rendered as "2S".
     */
    val initials: String get() = name.initials()

    /**
     * Server label KEYS for the type line, in display order.
     *
     * Not joined into a subtitle here on purpose: `type` and `subType` arrive from the
     * backend as label keys (`entertainment_industry_label`), and resolving them needs
     * the label dictionary, which only the composable has. Building the string here
     * would bake in raw keys — and would not follow a language change.
     */
    val typeLabelKeys: List<String>
        get() = listOfNotNull(
            type?.takeIf { it.isNotBlank() },
            subType?.takeIf { it.isNotBlank() },
        )
}
