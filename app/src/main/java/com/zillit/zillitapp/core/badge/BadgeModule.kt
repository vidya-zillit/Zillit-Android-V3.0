package com.zillit.zillitapp.core.badge

/**
 * Where a notification **navigates to** when tapped.
 *
 * Deliberately separate from [BadgeKey], which is where a notification *counts*. They were
 * one concept early on and it was a mistake: routing needs a small closed set the nav host
 * can exhaust in a `when`, while counting needs the backend's open-ended path. Merging them
 * forced every new tool to add an enum entry before its badges could work at all.
 */
enum class BadgeModule(val key: String) {
    PROJECT("project"),
    HOME_CHAT("home_chat"),
    GROUP_CHAT("group_chat"),
    PRIVATE_CHAT("private_chat"),
    TOOLS("tools"),
    DOC_DISTRIBUTION("doc_distribution"),
    CALENDAR("calendar"),
    EMAIL("email"),
    NOTIFICATION("notification"),
}

/**
 * Which origin a stored count came from.
 *
 * The two are not interchangeable: an API count is a snapshot the server computed, while a
 * realtime count is derived from notifications this device holds and can mark read. They
 * are stored as separate rows so a resync can rebuild one without erasing the other.
 */
enum class BadgeSource(val key: String) {
    /** Derived from stored notifications. Authoritative once any exist. */
    REALTIME("realtime"),

    /** A server snapshot — `device/unread` for the project list. Cold-start fallback. */
    API("api"),
}
