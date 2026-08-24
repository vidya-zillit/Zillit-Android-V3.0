package com.zillit.zillitapp.core.directory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow

/**
 * `user_id` in, user model out — from anywhere in the app, without plumbing the
 * directory through every constructor.
 *
 * Chat, mentions, approvals, call logs and read receipts all arrive as bare user ids and
 * every one of them needs a name and an avatar. v2 solved this with
 * `String.getUserNameByUserId()`, which returned only a name; these return the whole model
 * so a caller that also needs the designation, unit or avatar does not need a second
 * lookup.
 *
 * All lookups are **cache reads**. The directory is loaded in full when the project opens,
 * so an unknown id means the person is genuinely not on this project — firing a request
 * per miss would turn one chat screen's render into a burst of network calls.
 *
 * ```kotlin
 * val author = message.senderId.userDetails()          // ProjectUser?
 * val name   = message.senderId.userDisplayName()      // "Vidya Pixel"
 * val label  = message.senderId.userDisplayNameWithRole() // "Vidya Pixel (Driver)"
 * ```
 */

/**
 * The directory backing the extensions.
 *
 * Set once at application start by [com.zillit.zillitapp.ZillitApplication]. It is a
 * process-wide handle rather than an injected dependency because the call sites are
 * extension functions on `String` — a rendering helper deep in a composable cannot take a
 * constructor parameter. Everything it exposes is a read of a Realm cache keyed by the
 * active project, so there is no hidden per-caller state.
 */
object UserLookup {

    @Volatile
    private var directory: ProjectDirectory? = null

    @Volatile
    private var currentUserIdProvider: (() -> String?)? = null

    fun install(directory: ProjectDirectory, currentUserId: () -> String?) {
        this.directory = directory
        this.currentUserIdProvider = currentUserId
    }

    /**
     * The signed-in user's id, for "is this mine?" checks while rendering.
     *
     * Exposed here rather than injected because the caller is usually a mapper or an
     * extension with no constructor of its own, and every chat row needs it.
     */
    fun currentUserId(): String? = currentUserIdProvider?.invoke()

    internal fun requireDirectory(): ProjectDirectory? = directory

    /**
     * Sender ids that were rendered without a matching directory entry.
     *
     * v2 calls back from the view holder (`userNameNotFound`) and refetches the crew; the
     * same idea, collected instead of dispatched one at a time — a thread full of messages
     * from someone who joined since the last sync would otherwise fire one refresh per row.
     *
     * Drained by whoever refreshes, so an id is only chased once per gap.
     */
    private val unresolved = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    internal fun noteUnresolved(userId: String) {
        if (userId.isNotBlank()) unresolved += userId
    }

    fun drainUnresolved(): Set<String> = synchronized(unresolved) {
        unresolved.toSet().also { unresolved.clear() }
    }
}

/**
 * The full user model for this id, or null when the id is not on the active project.
 *
 * @param projectId defaults to the active project. Pass it explicitly when rendering data
 *   that belongs to a different project than the one currently open — a notification list,
 *   for instance.
 */
fun String.userDetails(projectId: String? = null): ProjectUser? =
    UserLookup.requireDirectory()?.findUser(this, projectId)

/**
 * The name to show, honouring `keep_name_private`.
 *
 * @param fallback used when the id is unknown — a user who has left the project, or data
 *   that arrived before the directory finished loading. Never returns the raw id: showing
 *   a Mongo id where a name belongs is worse than showing nothing.
 */
fun String.userDisplayName(projectId: String? = null, fallback: String = ""): String =
    userDetails(projectId)?.displayName?.takeIf { it.isNotBlank() } ?: fallback

/** "Vidya Pixel (Driver)" — the shape chat headers use. */
fun String.userDisplayNameWithRole(projectId: String? = null, fallback: String = ""): String =
    userDetails(projectId)?.displayNameWithRole?.takeIf { it.isNotBlank() } ?: fallback

/** Initials for the circular avatar, e.g. "VP". */
fun String.userInitials(projectId: String? = null): String =
    userDetails(projectId)?.initials ?: "?"

fun String.userAvatarUrl(projectId: String? = null): String? =
    userDetails(projectId)?.profilePictureUrl

/** Live model, for a header that must follow a profile change without a screen reload. */
fun String.observeUserDetails(projectId: String? = null): Flow<ProjectUser?>? =
    UserLookup.requireDirectory()?.observeUser(this, projectId)

/**
 * Composable lookup.
 *
 * A plain [userDetails] call inside a composable is read once and never re-read, so a
 * profile that updates mid-session would keep rendering the stale name. This subscribes
 * instead, and recomposes when the row changes.
 */
@Composable
fun rememberUser(userId: String, projectId: String? = null): State<ProjectUser?> =
    produceState<ProjectUser?>(initialValue = userId.userDetails(projectId), userId, projectId) {
        userId.observeUserDetails(projectId)?.collect { value = it }
    }

/**
 * Directory available to composables that prefer explicit access over the global.
 * Provided in `MainActivity` alongside the label dictionaries.
 */
val LocalProjectDirectory: ProvidableCompositionLocal<ProjectDirectory?> =
    staticCompositionLocalOf { null }

@Composable
fun ProvideProjectDirectory(directory: ProjectDirectory, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalProjectDirectory provides directory, content = content)
}
