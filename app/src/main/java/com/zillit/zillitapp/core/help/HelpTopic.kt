package com.zillit.zillitapp.core.help

/**
 * A thing the app can explain.
 *
 * Every ⓘ in the app names one of these, and the topic — not the screen — decides which
 * tutorial video plays and which part of the documentation site the "More" link opens. That
 * indirection is what lets the same explanation be reached from a settings row, a tool's
 * header and a help index without three copies of the same two URLs.
 *
 * v2 keeps the same mapping in `VideoExtension.kt` as a 859-line `when` over raw string
 * constants, spread across `getVideoUrl()` and `getMoreLinkUrl()`. Here the two live on the
 * topic itself, so adding one is a single entry and a missing link is a compile error rather
 * than a silent fall-through to the placeholder video.
 *
 * @param video path under the tutorial bucket, or null when there is no video.
 * @param adminVideo path to play instead for an admin — several tutorials are recorded twice
 *   because the screen genuinely differs. Null means everyone sees [video].
 * @param docsAnchor the fragment on the documentation site, or null when there is no page.
 */
enum class HelpTopic(
    val video: String? = null,
    val adminVideo: String? = null,
    val docsAnchor: String? = null,
) {
    APP_PREFERENCES(
        video = "Settings/Adminsetting/How%20to%20Manage%20App%20Preferences.mp4",
        docsAnchor = "app-preferences",
    ),
    CONNECT_TO_ZILLIT(
        video = "Settings/How%20to%20connect%20to%20Zillit%20from%20your%20laptop.mp4",
        docsAnchor = "connect-to-zillit",
    ),
    EDIT_MY_PROFILE(
        video = "Settings/Adminsetting/How%20to%20Edit%20My%20Profile%28User%29.mp4",
        adminVideo = "Settings/Adminsetting/How%20to%20Edit%20My%20Profile%20admin.mp4",
        docsAnchor = "edit-my-profile",
    ),
    INVITE_USERS(
        video = "Settings/How%20to%20invite%20users.mp4",
        docsAnchor = "invite-users",
    ),
    PRIVACY_PREFERENCES(docsAnchor = "privacy"),
    RECOVERY_CODE_EMAIL(
        video = "Settings/Adminsetting/recovery%20code.mp4",
        docsAnchor = "recovery-code",
    ),
    UPDATE_APP(
        video = "Settings/How%20to%20Update%20Zillit%20app.mp4",
        docsAnchor = "update-app",
    ),
    ZILLIT_HELP(
        video = "Settings/Zillit%20Help.mp4",
        docsAnchor = "zillit-help",
    ),
    ACCOUNT_SETTINGS(
        video = "Settings/How%20to%20create%20unit%20in%20Accounts.mp4",
        docsAnchor = "accounts",
    ),
    CATERING_SETTINGS(
        video = "Settings/How%20to%20Create%2C%20%20Edit%20%26%20Delete%20%20Catering%20UnitAndroid.mp4",
        docsAnchor = "catering",
    ),
    ADMIN_SETTINGS(docsAnchor = "admin-settings"),
    LEAVE_PROJECT(docsAnchor = "leave-project"),
    ;
}
