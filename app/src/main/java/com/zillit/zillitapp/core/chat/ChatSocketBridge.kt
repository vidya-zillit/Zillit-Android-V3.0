package com.zillit.zillitapp.core.chat

import com.zillit.zillitapp.core.chat.data.ChatDeleteEvent
import com.zillit.zillitapp.core.chat.data.ChatMessageDto
import com.zillit.zillitapp.core.chat.data.ChatModule
import com.zillit.zillitapp.core.chat.data.ChatPage
import com.zillit.zillitapp.core.chat.data.ChatRepository
import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.notification.ACTIONS_HOME_UNIT_LIFECYCLE
import com.zillit.zillitapp.core.socket.EventGate
import com.zillit.zillitapp.core.socket.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns socket events into Realm writes.
 *
 * The single place chat realtime is handled. v2 wires the same events into every chat
 * screen's `onViewCreated`, so a message only lands if the screen that cares happens to be
 * open — and each screen re-implements the same "is this for me?" checks slightly
 * differently.
 *
 * Two things every handler does, in this order:
 *
 * 1. **Verify project and user.** [EventGate] drops anything whose `project_id` is not the
 *    active project or whose `user_id` belongs to a different member. Without both checks
 *    a message from another project writes into the one on screen.
 * 2. **Apply the payload immediately**, then reconcile. The event carries the whole
 *    message, so the thread updates with no round trip; the follow-up fetch only exists to
 *    close gaps from events missed while the socket was down, and is **debounced** so a
 *    burst of twenty messages costs one request instead of twenty.
 */
@OptIn(FlowPreview::class)
@Singleton
class ChatSocketBridge @Inject constructor(
    private val socketManager: SocketManager,
    private val chatRepository: ChatRepository,
    private val directory: ProjectDirectory,
    private val notificationRepository: com.zillit.zillitapp.core.notification.NotificationRepository,
    private val session: SessionStore,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Coalesces reconcile fetches: `(module, scopeId)` in, one fetch out per quiet period. */
    private val reconcileRequests = MutableSharedFlow<Pair<ChatModule, String>>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Same coalescing for the user directory — see [observeDirectoryEvents]. */
    private val directoryRefreshRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Server ids of messages someone just read.
     *
     * Exposed rather than acted on: nothing in the thread renders read state (v2 shows
     * ticks only in C&C), so the one screen that cares — Read By User — subscribes and
     * refetches. Emitting into the void costs nothing when it is closed.
     */
    private val _readByUpdates = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val readByUpdates: SharedFlow<String> = _readByUpdates

    private var started = false

    fun start() {
        if (started) return
        started = true

        observeMessageEvents()
        observeDirectoryEvents()
        observeUnitEvents()
        observeUnitNotifications()
        startReconcileWorker()

        ZillitLog.socket("ChatSocketBridge started")
    }

    private fun observeMessageEvents() {
        // Own-device echoes are NOT dropped here. The server's copy is the authoritative
        // one — it carries the `_id` a message needs before it can be replied to or
        // deleted — so discarding it would leave our optimistic row without one.
        listOf(EVENT_ADDED, EVENT_EDITED, EVENT_DELETED).forEach { event ->
            scope.launch {
                socketManager.rawEvents(event).collect { payload ->
                    if (!EventGate.IncludingOwnDevice.allows(payload, session)) return@collect
                    handleMessagePayload(event, payload)
                }
            }
        }

        scope.launch {
            socketManager.rawEvents(EVENT_DELETED_MULTIPLE).collect { payload ->
                if (!EventGate.IncludingOwnDevice.allows(payload, session)) return@collect
                handleBulkDelete(EVENT_DELETED_MULTIPLE, payload)
            }
        }

        // Call Sheet's replace publishes the same shape as a bulk delete.
        scope.launch {
            socketManager.rawEvents(EVENT_CALLSHEET_DELETED).collect { payload ->
                if (!EventGate.IncludingOwnDevice.allows(payload, session)) return@collect
                handleBulkDelete(EVENT_CALLSHEET_DELETED, payload)
            }
        }

        // Replies arrive as the parent message with its full comment list, so they go
        // through the same path as an edit.
        listOf(EVENT_COMMENT_ADDED, EVENT_COMMENT_EDITED, EVENT_COMMENT_DELETED).forEach { event ->
            scope.launch {
                socketManager.rawEvents(event).collect { payload ->
                    if (!EventGate.IncludingOwnDevice.allows(payload, session)) return@collect
                    handleMessagePayload(event, payload)
                }
            }
        }

        // Archiving rewrites the thread server-side, so the local copy is refetched rather
        // than patched — the event carries no message list to apply.
        scope.launch {
            socketManager.rawEvents(EVENT_CHAT_ARCHIVED).collect { payload ->
                if (!EventGate.IncludingOwnDevice.allows(payload, session)) return@collect
                val unitId = payload.unitId() ?: return@collect
                ZillitLog.socket("Chat archived in $unitId")
                reconcileRequests.tryEmit(ChatModule.HOME to unitId)
            }
        }

        scope.launch {
            socketManager.rawEvents(EVENT_READ_BY).collect { payload ->
                if (!EventGate.IncludingOwnDevice.allows(payload, session)) return@collect
                payload.messageId()?.let { _readByUpdates.tryEmit(it) }
            }
        }
    }

    /** `data.unit_id`, or the top-level one on events that do not nest. */
    private fun JsonObject.unitId(): String? =
        (this["data"] as? JsonObject ?: this)["unit_id"]
            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.messageId(): String? =
        (this["data"] as? JsonObject ?: this).let { it["message_id"] ?: it["_id"] }
            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    /**
     * Keeps the crew directory current.
     *
     * A profile edit, a new joiner or an admin change all mean the names and avatars
     * rendered against existing messages are stale — and chat renders authors by id, so a
     * changed name must show up on messages already on screen. The directory is refreshed
     * and, because it writes to Realm, every bound row re-renders on its own.
     *
     * Refreshes are coalesced: an admin adding ten users emits ten events, and re-fetching
     * the whole crew ten times in a row is wasted work.
     */
    private fun observeDirectoryEvents() {
        listOf(
            EVENT_USER_ADDED,
            EVENT_USER_PROFILE_UPDATED,
            EVENT_USER_PROFILE_CREATED,
            EVENT_USER_ADMIN_ACCESS,
            EVENT_USER_JOIN_ACCEPTED,
            EVENT_PRE_APPROVED_USER_JOINED,
            EVENT_EXTERNAL_USER_UPDATED,
        ).forEach { event ->
            scope.launch {
                socketManager.rawEvents(event).collect { payload ->
                    // Project must match; the user check is off because these events carry
                    // the id of the person who changed, which is precisely not us.
                    if (!DIRECTORY_GATE.allows(payload, session)) return@collect
                    ZillitLog.socket("Directory event $event")
                    directoryRefreshRequests.tryEmit(Unit)
                }
            }
        }
    }

    /** Unit created, renamed, deleted, or access changed — the strip has to follow. */
    private fun observeUnitEvents() {
        listOf(EVENT_UNIT_CREATE, EVENT_UNIT_UPDATE, EVENT_UNIT_DELETE).forEach { event ->
            scope.launch {
                socketManager.rawEvents(event).collect { payload ->
                    if (!DIRECTORY_GATE.allows(payload, session)) return@collect
                    val projectId = session.activeProject.value?.projectId ?: return@collect
                    ZillitLog.socket("Unit event $event")
                    directory.refreshUnits(projectId)
                }
            }
        }
    }

    /**
     * Refreshes the unit strip when a notification says a unit changed.
     *
     * The socket's `home:unit:*` events already do this, but they are not the only way the
     * news arrives — a rename reaches the device by FCM too, and on a flaky connection that
     * may be the only copy that lands. Reacting to the stored notification instead of the
     * socket event makes the strip correct regardless of which channel delivered it, and
     * de-duplication on `uuid` means both channels together still refresh once.
     */
    private fun observeUnitNotifications() {
        scope.launch {
            notificationRepository.stored
                .filter { it.action in ACTIONS_HOME_UNIT_LIFECYCLE }
                .collect { incoming ->
                    ZillitLog.socket("Unit notification ${incoming.action} → refreshing units")
                    directory.refreshUnits(incoming.projectId)
                }
        }
    }

    private fun startReconcileWorker() {
        scope.launch {
            reconcileRequests
                .debounce(RECONCILE_DEBOUNCE_MS)
                .collect { (module, scopeId) ->
                    val projectId = session.activeProject.value?.projectId ?: return@collect
                    chatRepository.fetchPage(module, projectId, scopeId, ChatPage.NEXT)
                }
        }

        scope.launch {
            directoryRefreshRequests
                .debounce(RECONCILE_DEBOUNCE_MS)
                .collect {
                    val projectId = session.activeProject.value?.projectId ?: return@collect
                    directory.refreshUsers(projectId)
                }
        }
    }

    private suspend fun handleMessagePayload(event: String, payload: JsonObject) {
        val projectId = session.activeProject.value?.projectId ?: return

        val messages = extractMessages(payload)
        if (messages.isEmpty()) return

        ZillitLog.socket("$event → ${messages.size} message(s)")

        chatRepository.applyRealtimeMessage(ChatModule.HOME, projectId, messages)

        // Stored regardless of which unit is on screen, then reconciled per unit.
        messages.mapNotNull { it.unitId }.distinct().forEach { unitId ->
            reconcileRequests.tryEmit(ChatModule.HOME to unitId)
        }
    }

    /**
     * A delete, in either shape the backend sends.
     *
     * Two payloads carry the same meaning:
     *
     *  - `{unit_id, chatIds:[…], deleted}` — ids only, no message bodies.
     *  - the **whole message object** with its own `deleted` stamp set, which is what a
     *    single delete actually produces.
     *
     * Only the first was handled, so the second fell through the `chatIds` check and was
     * dropped without a trace — a deleted message simply stayed on screen. The second shape
     * needs no special path: it already carries `deleted`, and the normal store path stamps
     * it while `observeMessages` filters those rows out.
     */
    private suspend fun handleBulkDelete(event: String, payload: JsonObject) {
        val projectId = session.activeProject.value?.projectId ?: return

        val parsed = runCatching {
            json.decodeFromJsonElement(ChatDeleteEvent.serializer(), payload["data"] ?: payload)
        }.getOrNull()

        val ids = parsed?.chatIds.orEmpty()
        val unitId = parsed?.unitId

        if (ids.isEmpty()) {
            ZillitLog.socket("$event carries no chatIds — applying as message payload")
            handleMessagePayload(event, payload)
            return
        }

        if (unitId == null) {
            ZillitLog.socketWarn("$event had chatIds but no unit_id — ignored")
            return
        }

        ZillitLog.socket("Bulk delete: ${ids.size} in $unitId")

        chatRepository.applyRealtimeDelete(
            module = ChatModule.HOME,
            projectId = projectId,
            scopeId = unitId,
            serverIds = ids,
            deletedAt = parsed.deleted ?: System.currentTimeMillis(),
        )
        reconcileRequests.tryEmit(ChatModule.HOME to unitId)
    }

    /**
     * Pulls messages out of a payload.
     *
     * The `data` field is sometimes an object and sometimes an array depending on the
     * event, and v2 papers over that by string-concatenating brackets around it. Handled
     * as a type check here instead, so a shape change surfaces as zero messages rather
     * than a malformed parse.
     */
    private fun extractMessages(payload: JsonObject): List<ChatMessageDto> {
        val data = payload["data"] ?: return emptyList()

        return runCatching {
            when (data) {
                is kotlinx.serialization.json.JsonArray ->
                    data.map { json.decodeFromJsonElement(ChatMessageDto.serializer(), it) }

                else -> listOf(
                    json.decodeFromJsonElement(ChatMessageDto.serializer(), data.jsonObject),
                )
            }
        }.onFailure {
            ZillitLog.socketWarn("Could not parse chat payload: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val EVENT_ADDED = "home:message:added"
        const val EVENT_EDITED = "home:message:edited"
        const val EVENT_DELETED = "home:message:deleted"
        const val EVENT_DELETED_MULTIPLE = "home:message:deleted:multiple"
        const val EVENT_CALLSHEET_DELETED = "home:callsheet:message:deleted"

        // `message:comment`, not `comment`. The server namespaces a reply under the
        // message it belongs to, and the shorter names silently match nothing — replies
        // from other people then only appear on the next refetch.
        const val EVENT_COMMENT_ADDED = "home:message:comment:added"
        const val EVENT_COMMENT_EDITED = "home:message:comment:edited"
        const val EVENT_COMMENT_DELETED = "home:message:comment:deleted"

        /** Someone read a message — the Read By screen refreshes if it is open. */
        const val EVENT_READ_BY = "home:message:readby:update"

        const val EVENT_CHAT_ARCHIVED = "home:chat:archived"

        const val EVENT_UNIT_CREATE = "home:unit:create"
        const val EVENT_UNIT_UPDATE = "home:unit:update"
        const val EVENT_UNIT_DELETE = "home:unit:delete"

        const val EVENT_USER_ADDED = "admin:add:project:user"
        const val EVENT_USER_PROFILE_UPDATED = "project:user:profile:update"
        const val EVENT_USER_PROFILE_CREATED = "project:user:profile:created"
        const val EVENT_USER_ADMIN_ACCESS = "project:user:admin:access"
        const val EVENT_USER_JOIN_ACCEPTED = "project:user:join:request:accepted"
        const val EVENT_PRE_APPROVED_USER_JOINED = "project:pre-approved:user:joined"
        const val EVENT_EXTERNAL_USER_UPDATED = "project:external:user:updated"

        /**
         * Project-scoped, but user-agnostic and including our own device: these events
         * describe *other* people, and an admin change we made ourselves still has to
         * update our copy of the directory.
         */
        val DIRECTORY_GATE = EventGate(
            requireActiveProject = true,
            requireActiveUser = false,
            ignoreOwnDevice = false,
        )

        /** Long enough to swallow a burst, short enough to feel immediate. */
        const val RECONCILE_DEBOUNCE_MS = 400L
    }
}
