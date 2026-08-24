package com.zillit.zillitapp.core.directory

import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.socket.SocketEvents
import com.zillit.zillitapp.core.socket.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the project directory current while the app is open.
 *
 * The directory is what almost every screen reads a person from — their name on a message,
 * their photo in Settings, whether they are an admin, which department they belong to. All
 * of it is loaded once when the project opens, so without this it stays as it was at that
 * moment: a colleague who changes their photo, or an admin who grants someone rights, is
 * invisible until the app is restarted.
 *
 * Refreshing the **store** rather than notifying screens is deliberate. Every consumer
 * already observes Realm, so one refresh updates the settings page, the unit strip, every
 * chat bubble's author and the invitee pickers at once — and a screen written tomorrow gets
 * it without being wired up.
 */
@Singleton
class DirectoryRealtime @Inject constructor(
    private val socketManager: SocketManager,
    private val directory: ProjectDirectory,
    private val session: SessionStore,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private var started = false

    /**
     * Coalesces bursts.
     *
     * A department rename arrives as one event per affected user, and an admin bulk-editing
     * people produces a run of them. Each would otherwise be its own round trip for the same
     * list.
     */
    private val userRefreshes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val departmentRefreshes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val accessRefreshes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Idempotent — safe to call from anywhere that needs the directory to stay fresh. */
    fun start() {
        if (started) return
        started = true

        ZillitLog.socket("directory: watching profile, admin-access and department events")
        collectRefreshes()

        // Somebody's profile changed: name, photo, designation, department. Includes this
        // user's own, which is what makes the Settings header update in place.
        //
        // Gated on the project only. These events name the user they are *about* in
        // `user_id`, so the default user gate would drop every one that is not about us —
        // which is most of them, and exactly the ones a directory needs.
        listen(
            events = listOf(
                SocketEvents.PROJECT_USER_PROFILE_UPDATE,
                SocketEvents.PROJECT_USER_PROFILE_CREATED,
                SocketEvents.PROJECT_USER_ACCEPTED,
                SocketEvents.PROJECT_USER_REMOVED,
                SocketEvents.PROJECT_USER_REORDERED,
            ),
            into = userRefreshes,
        )

        // Admin rights granted or withdrawn. Refreshes access as well as the person: what
        // an admin may open differs, so the tools and units they can see change with it.
        listen(events = listOf(SocketEvents.PROJECT_USER_ADMIN_ACCESS), into = accessRefreshes)

        // Departments themselves. A user row carries its department's name, so both lists
        // are refreshed rather than only the one that changed.
        listen(
            events = listOf(
                SocketEvents.DEPARTMENT_CREATE,
                SocketEvents.DEPARTMENT_UPDATE,
                SocketEvents.DEPARTMENT_DELETE,
                SocketEvents.DEPARTMENT_REORDERED,
            ),
            into = departmentRefreshes,
        )
    }

    private fun listen(events: List<String>, into: MutableSharedFlow<Unit>) {
        scope.launch {
            events.map { socketManager.rawEvents(it) }
                .merge()
                // Another project's event must not cost this one a refetch. Absent means
                // account-level, which is allowed — the same rule EventGate applies.
                .filter { payload ->
                    val eventProject = (payload["project_id"] as? JsonPrimitive)?.contentOrNull
                    eventProject.isNullOrEmpty() ||
                        eventProject == session.activeProject.value?.projectId
                }
                .onEach {
                    ZillitLog.socket("directory: change received")
                    into.tryEmit(Unit)
                }
                .launchIn(this)
        }
    }

    private fun collectRefreshes() {
        scope.launch {
            userRefreshes.debounce(QUIET_PERIOD).collect {
                withProject { directory.refreshUsers(it) }
            }
        }

        scope.launch {
            departmentRefreshes.debounce(QUIET_PERIOD).collect {
                withProject {
                    directory.refreshDepartments(it)
                    directory.refreshUsers(it)
                }
            }
        }

        scope.launch {
            accessRefreshes.debounce(QUIET_PERIOD).collect {
                withProject { projectId ->
                    directory.refreshUsers(projectId)
                    // v2 calls `getUserUnitAccess()` on the same events, for the same
                    // reason: rights decide what is on screen, not just what it says.
                    directory.refreshTools(projectId, isPendingUser = false)
                    directory.refreshUnits(projectId)
                }
            }
        }
    }

    private suspend fun withProject(block: suspend (String) -> Unit) {
        val projectId = session.activeProject.value?.projectId ?: return
        runCatching { block(projectId) }
            .onFailure { ZillitLog.socketWarn("directory refresh failed: ${it.message}") }
    }

    private companion object {
        /** Long enough to absorb a burst, short enough to feel immediate. */
        const val QUIET_PERIOD = 400L
    }
}
