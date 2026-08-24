package com.zillit.zillitapp.core.notification

import com.zillit.zillitapp.core.badge.BadgeKey
import com.zillit.zillitapp.core.badge.describe
import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.NetworkMonitor
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.socket.EventGate
import com.zillit.zillitapp.core.socket.SocketConnectionState
import com.zillit.zillitapp.core.socket.SocketEvents
import com.zillit.zillitapp.core.socket.SocketManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.eventFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class NotificationSyncResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("data") val data: List<NotificationPayload> = emptyList(),
)

/**
 * Keeps badges correct across the gaps where realtime delivery cannot be trusted.
 *
 * Pushes and socket events are the fast path, but neither is guaranteed: FCM drops
 * messages under doze, the socket is not connected while the app is dead, and a device
 * that was offline for an hour has missed everything in between. So badges are also
 * **reconciled** at the moments where they are most likely to be wrong:
 *
 *  1. **Network restored** — after an outage, whatever arrived while offline is missing.
 *  2. **Socket (re)connected** — an emit asks the server to replay anything not delivered.
 *  3. **A project became active** — including the first selection after launch.
 *
 * Both mechanisms are used together on purpose: the REST call is authoritative and
 * paginated, while the socket emit is cheap and catches the short gaps.
 */
@Singleton
class BadgeSyncCoordinator @Inject constructor(
    private val api: ZillitApi,
    private val socketManager: SocketManager,
    private val session: SessionStore,
    private val notificationRepository: NotificationRepository,
    private val networkMonitor: NetworkMonitor,
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Serialises syncs so a network-restore and a project switch cannot overlap. */
    private val syncMutex = Mutex()

    private var started = false

    /**
     * Begins watching for the conditions that require a resync. Idempotent — safe to call
     * from every screen that cares.
     */
    fun start() {
        if (started) return
        started = true

        // 1. Network came back. `isOnline` only turns true for a VALIDATED connection, so
        //    this does not fire for a Wi-Fi network that cannot reach the internet.
        scope.launch {
            var wasOnline = networkMonitor.isOnline.value
            networkMonitor.isOnline.collectLatest { online ->
                if (online && !wasOnline) syncNow()
                wasOnline = online
            }
        }

        // 2. Socket (re)connected — ask the server to replay anything we missed.
        scope.launch {
            socketManager.connectionState.collectLatest { state ->
                if (state is SocketConnectionState.Connected) requestMissedNotifications()
            }
        }

        // 3. The app came back to the foreground.
        //
        // v2 refetches badges every time its main screen is created, and this is why: the
        // socket is dead while the app is backgrounded, pushes are dropped under doze, and
        // anything the user read on web in between is invisible here. Without it, badges
        // are only ever reconciled by switching project or losing the network — neither of
        // which is what "I opened the app" looks like.
        //
        // ON_START, not ON_RESUME: a dialog or a permission prompt resumes the app again
        // and would otherwise re-sync for nothing.
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.eventFlow
                .filter { it == Lifecycle.Event.ON_START }
                .collectLatest {
                    requestMissedNotifications()
                    syncNow()
                }
        }

        // 4. A project became active.
        //
        // Needed because of an ordering the log exposed: the socket connects during
        // splash, long before any project is selected, so the connect-triggered replay
        // above returns early with no project to scope to and never fires again. Watching
        // the project itself covers both that first selection and every later switch.
        scope.launch {
            session.activeProject
                .filterNotNull()
                .distinctUntilChangedBy { it.projectId }
                .collectLatest {
                    requestMissedNotifications()
                    syncNow()
                }
        }

        // Inbound realtime events.
        scope.launch { observeIncoming(SocketEvents.NOTIFICATION_SAVE) }
        scope.launch { observeIncoming(SocketEvents.NOTIFICATION_SILENT) }
        scope.launch { observeBadgeCleared() }
        scope.launch { observeReadElsewhere() }
    }

    private suspend fun syncNow() = syncMutex.withLock {
        val projectId = session.activeProject.value?.projectId ?: return@withLock

        // Incremental, as v2's `getTimeStamp(projectId, NEXT_PARAM)` is: from the newest
        // notification already held, forwards. Only a device with nothing stored asks for
        // history, and it asks backwards from now.
        //
        // The path shape is `.../{timestamp}/{direction}`. Always sending
        // `now/previous` — which this used to do — re-downloads the entire history on
        // every app entry, network blip and project switch.
        val newest = notificationRepository.newestSyncedAt(projectId)
        val url = if (newest > 0) {
            "${ApiEndpoints.Notification.ALL_FOR_BADGE}$newest/next"
        } else {
            "${ApiEndpoints.Notification.ALL_FOR_BADGE}${System.currentTimeMillis()}/previous"
        }

        when (val result = api.get<NotificationSyncResponse>(url, ModuleData.WITH_PROJECT_USER_ID)) {
            is ApiResult.Failure -> Unit // Cached badges stay; the next trigger retries.
            is ApiResult.Success -> {
                // Recorded as one batch: recomputing per notification rebuilds every
                // badge in the app once per row, which flickered the counts on open.
                notificationRepository.recordAll(
                    result.data.data
                        .mapNotNull { it.toIncoming(json) }
                        .filter { it.projectId == projectId }
                        // Marked as the feed's own so they, and only they, move the cursor.
                        .map { it.copy(fromSync = true) },
                )
            }
        }

        // Read rows older than the retention window are dropped here rather than on a
        // timer: sync is the only moment the store grows, so it is the right moment to
        // trim it. Recomputes badges on the way out.
        notificationRepository.pruneRead()

        // Belt and braces: rebuild counters from what is actually stored, so a badge can
        // never drift away from the notifications backing it.
        notificationRepository.recomputeBadges()
    }

    /** The socket half — asks the server to resend anything undelivered. */
    private fun requestMissedNotifications() {
        val projectId = session.activeProject.value?.projectId ?: return
        socketManager.emit(
            event = SocketEvents.NOTIFICATION_MISSING,
            payload = buildJsonObject {
                put("project_id", projectId)
                put("device_id", session.deviceId)
            },
        )
    }

    private suspend fun observeIncoming(event: String) {
        socketManager.observe(
            event = event,
            deserializer = NotificationPayload.serializer(),
            // Account-level: badge events legitimately arrive for the active project but
            // the payload gate is applied below against our own project id.
            gate = EventGate.AccountLevel,
        ).filterNotNull().collectLatest { payload ->
            payload.toIncoming(json)?.let { notificationRepository.record(it) }
        }
    }

    /** Server says badges were cleared elsewhere — another device read them. */
    /**
     * Badges cleared for this user, or for this whole device.
     *
     * `:user` names the project in its payload — read from there rather than from the
     * active project, because the clear may well be for one the user is not currently in.
     * `:device` means everything on this device, so it clears without a project at all.
     */
    private suspend fun observeBadgeCleared() {
        scope.launch {
            socketManager.rawEvents(SocketEvents.BADGES_CLEARED_USER).collect { payload ->
                val projectId = payload.projectIdOrNull()
                    ?: session.activeProject.value?.projectId
                    ?: return@collect
                notificationRepository.markRead(BadgeKey.project(projectId))
            }
        }

        scope.launch {
            socketManager.rawEvents(SocketEvents.BADGES_CLEARED_DEVICE).collect {
                // No prefix at all — every project this device holds.
                notificationRepository.markRead(BadgeKey())
            }
        }
    }

    /**
     * Another of this user's sessions read or deleted notifications.
     *
     * The event says *that* something was read, not *what* — so the only correct response is
     * to ask the server again. v2 does exactly this (`updateBadges()`, a refetch); an earlier
     * version here marked the whole project read locally instead, which was wrong twice
     * over: it cleared far more than was actually read, and this device's **own** read echo
     * comes back on the same channel — so opening any unit wiped every badge in the project,
     * calendar invitations included.
     *
     * The refetch is cheap and self-correcting: a read bumps the notification's `updated`,
     * so the incremental sync returns exactly the rows that changed and merges their read
     * state.
     */
    private suspend fun observeReadElsewhere() {
        listOf(
            SocketEvents.NOTIFICATION_READ_SYNC,
            SocketEvents.NOTIFICATION_DELETE_SYNC,
            SocketEvents.NOTIFICATION_DELETE_GLOBAL_SYNC,
        ).forEach { event ->
            scope.launch {
                socketManager.rawEvents(event).collect {
                    ZillitLog.socket("$event → resyncing badges")
                    syncNow()
                }
            }
        }
    }

    /** The project a sync payload refers to, wherever the backend put it. */
    private fun JsonObject.projectIdOrNull(): String? =
        (this["project_id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: ((this["data"] as? JsonObject)?.get("project_id") as? JsonPrimitive)
                ?.content?.takeIf { it.isNotBlank() }

    /**
     * Marks everything under a badge path read — locally, then on the server.
     *
     * The single mark-read entry point for the whole app. v2 had twenty module-specific
     * variants; a prefix expresses all of them, because the backend addresses reads by the
     * same path it addresses notifications with.
     *
     * Local first, so the badge clears on the frame the user opens the thing. Then the
     * server, so the count does not return on the next sync and the user's other devices
     * clear too — clearing only the local store would do neither, since
     * [NotificationRepository.recomputeBadges] rebuilds counters from the stored rows.
     */
    fun markRead(prefix: BadgeKey, excludeTool: String? = null) {
        scope.launch {
            ZillitLog.badge("markRead requested for ${prefix.describe()}")
            notificationRepository.markRead(prefix, excludeTool)
            echoRead(segment = prefix.readSegment(), prefix = prefix)
        }
    }

    /**
     * Marks everything about one thing read — an event, a document — wherever it sits.
     *
     * Separate from the path form because some reads are not a place but a subject: v2
     * answers a calendar invitation with `segment=calendar_invite_users` plus the event's
     * `reference_id`, and the server clears that event's notifications wherever they were
     * filed. A path read cannot express "this event only".
     */
    fun markReadForReference(referenceId: String, segment: String) {
        if (referenceId.isBlank()) return
        scope.launch {
            val marked = notificationRepository.markReadByReference(referenceId)
            ZillitLog.badge("markRead reference=$referenceId → $marked notification(s)")
            echoRead(segment = segment, referenceId = referenceId)
        }
    }

    /**
     * The `segment` the backend expects for a read.
     *
     * v2 sends the most specific identifier it has, and `global_label` when the read covers
     * the whole project — see `Constants.GLOBAL_LABEL`.
     */
    private fun BadgeKey.readSegment(): String =
        levels.lastOrNull()?.takeIf { it.isNotBlank() }
            ?: unit?.takeIf { it.isNotBlank() }
            ?: tool?.takeIf { it.isNotBlank() }
            ?: section?.takeIf { it.isNotBlank() }
            ?: GLOBAL_SEGMENT

    /**
     * Tells the server a segment was read — over the socket, or over REST when it is down.
     *
     * The fallback is not optional. v2 has it for a reason: the socket is disconnected
     * exactly when the app has just come back from the background, which is also when the
     * user is most likely to be clearing notifications. Without it the read is lost and the
     * badge returns on the next sync, which reads as the app ignoring them.
     */
    private suspend fun echoRead(
        segment: String,
        prefix: BadgeKey? = null,
        referenceId: String? = null,
    ) {
        val sent = socketManager.emit(
            event = SocketEvents.NOTIFICATION_READ,
            payload = buildJsonObject {
                put("project_id", session.activeProject.value?.projectId.orEmpty())
                put("device_id", session.deviceId)
                put("segment", segment)
                put("timeStamp", System.currentTimeMillis())
                // The rest of the path, as v2 sends it. Without these the server can only
                // guess what the segment meant, and a read of one unit reads the section.
                referenceId?.let { put("reference_id", it) }
                prefix?.section?.let { put("section", it) }
                prefix?.tool?.let { put("tool", it) }
                prefix?.unit?.let { put("unit", it) }
                prefix?.levels?.forEachIndexed { index, level ->
                    put("level_${index + 1}", level)
                }
            },
        )
        if (sent) return

        // v2's `MARK_READ_URL + "$segment/$timeStamp"`, body-less.
        val url = "${ApiEndpoints.Notification.MARK_READ}$segment/${System.currentTimeMillis()}"
        api.put<Unit?, Unit>(url = url, body = null, module = ModuleData.WITH_PROJECT_USER_ID)
    }

    private companion object {
        /** v2's `Constants.GLOBAL_LABEL` — the whole project rather than one scope. */
        const val GLOBAL_SEGMENT = "global_label"
    }
}
