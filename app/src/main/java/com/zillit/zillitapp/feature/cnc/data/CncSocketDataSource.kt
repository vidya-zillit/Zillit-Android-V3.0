package com.zillit.zillitapp.feature.cnc.data

import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.socket.EventGate
import com.zillit.zillitapp.core.socket.SocketManager
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every socket call a socket chat makes, typed, for both C&C and Budget.
 *
 * Nothing above this knows an event name, and nothing here knows about Realm or a screen.
 * The surface is passed in per call rather than held as state, because C&C and a Budget
 * thread can be open in different back-stack entries at once and a shared "current surface"
 * field is how v2's emits end up on the wrong one.
 *
 * ### Sends wait for the acknowledgement
 * A socket chat has no POST to tell it a message landed — the ack is the only proof. So
 * [sendMessage] suspends until the server answers, and the repository uses that answer to
 * turn the optimistic row into a confirmed one. A null return is "unknown", never "failed":
 * the row stays pending and the queue tries it again.
 */
@Singleton
class CncSocketDataSource @Inject constructor(
    private val socket: SocketManager,
    private val session: SessionStore,
    private val json: Json,
) {

    /**
     * Messages emitted in the last [IN_FLIGHT_WINDOW_MS], keyed by `unique_id`.
     *
     * Several things flush the pending queue at once after a reconnect — the connect sweep,
     * the background worker, and every push that wakes the app. In v2 each pass re-emitted
     * the same row before the first ack landed, and QA saw one offline message delivered
     * three times. This is that fix, carried over deliberately: any number of concurrent
     * flushers produce exactly one send, while a genuinely lost emit still retries once the
     * window expires.
     */
    private val inFlight = ConcurrentHashMap<String, Long>()

    /**
     * How an outgoing message is written for the wire.
     *
     * Two settings, both load-bearing, and together they reproduce exactly what v2 puts on
     * the socket:
     *
     * - `encodeDefaults = true`, because the app-wide instance drops any property still
     *   holding its default — and `platform` is declared with one. The field silently never
     *   left the device, while every other emit that builds its JSON by hand sends it.
     * - `explicitNulls = false`, so the unset half of the model does not go out as a wall of
     *   `"field": null`. Defaults are sent; absent values stay absent.
     */
    private val wireJson = Json { encodeDefaults = true; explicitNulls = false }

    // ── Sending ──────────────────────────────────────────────────────────────

    /**
     * Emit one message and wait for the server to take it.
     *
     * @return the stored message as the server echoes it, or null when the socket was down,
     *   the ack timed out, or this message is already in flight.
     */
    suspend fun sendMessage(
        surface: ChatSurface,
        message: CncMessageDto,
        isGroup: Boolean,
    ): CncMessageDto? {
        val uniqueId = message.uniqueId
        if (!claimInFlight(uniqueId)) {
            ZillitLog.d(TAG, "suppressed duplicate send for $uniqueId")
            return null
        }

        val event = if (isGroup) surface.groupMessage else surface.privateMessage
        // Sent, as far as this device is concerned — the row is on the wire. v2 stamps the
        // same value before emitting, and the server stores what it is given, so leaving
        // the pending `0` on the payload is how a delivered message arrives at the other
        // end still looking unsent.
        val ack = socket.emitWithAck(event, message.copy(status = STATUS_SENT).asJson())
            ?: run {
                // Let it be retried: the send may simply not have reached the server.
                release(uniqueId)
                return null
            }

        return ack.decode(CncMessageEnvelope.serializer())?.detail
    }

    /** Change a message's text. Already encrypted by the caller, like a send. */
    suspend fun editMessage(
        surface: ChatSurface,
        serverId: String,
        message: String,
        translation: String,
        isGroup: Boolean,
    ): Boolean {
        val event = if (isGroup) surface.groupEdit else surface.privateEdit
        val payload = buildJsonObject {
            put("_id", serverId)
            put("message", message)
            put("message_translation", translation)
        }
        return socket.emitWithAck(event, payload)?.decode(CncMessageEnvelope.serializer())?.success == true
    }

    suspend fun deleteMessages(
        surface: ChatSurface,
        serverIds: List<String>,
        isGroup: Boolean,
    ): Boolean {
        val event = if (isGroup) surface.groupDelete else surface.privateDelete
        val payload = buildJsonObject {
            put("message_ids", json.encodeToJsonElement(serverIds))
            put("project_id", projectId())
        }
        return socket.emitWithAck(event, payload)
            ?.decode(CncMessageListEnvelope.serializer())?.success == true
    }

    /** Add, change or clear one reaction. An empty [reaction] removes it. */
    suspend fun react(surface: ChatSurface, serverId: String, reaction: String): CncMessageDto? {
        val payload = buildJsonObject {
            put("_id", serverId)
            put("reaction", reaction)
        }
        return socket.emitWithAck(surface.reaction, payload)
            ?.decode(CncMessageEnvelope.serializer())?.detail
    }

    /**
     * Move the read watermark: everything up to [serverId] is now at [status].
     *
     * Fire and forget rather than awaited. Nothing on screen depends on the answer, and
     * making the caller wait would stall opening a thread behind a round trip.
     */
    fun markRead(
        surface: ChatSurface,
        serverId: String,
        status: Int,
        conversationId: String,
        isGroup: Boolean,
        departmentId: String? = null,
    ): Boolean {
        val event = if (isGroup) surface.groupReadUntil else surface.privateReadUntil
        val payload = buildJsonObject {
            put("_id", serverId)
            put("status", status)
            put("project_id", projectId())
            put("chat_tool", surface.chatTool(departmentId))
            put("department_id", departmentId)
            if (isGroup) {
                put("room_id", conversationId)
                put("user_id", session.activeProject.value?.userId)
            } else {
                put("user_id", conversationId)
            }
        }
        return socket.emit(event, payload)
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /**
     * Which people and rooms this user has a conversation with.
     *
     * Answers membership only — the ids, not their order. Ordering comes from
     * `sorting_activity` on the project users endpoint, and unread from the badge tree.
     */
    suspend fun recentConversations(surface: ChatSurface): CncRecentDetail? =
        session.activeProject.value?.let { project ->
            val payload = buildJsonObject {
                put("user_id", project.userId)
                put("project_id", project.projectId)
            }
            val ack = socket.emitWithAck(surface.recentList, payload) ?: return@let null

            // Budget answers with `detail: [room, …]` where C&C answers with
            // `detail: { usersList: [...] }`. Try the object shape, then the array, rather
            // than assuming — one decode failure here is an empty tab, not an error.
            ack.decode(CncRecentListEnvelope.serializer())?.detail
                ?: ack.decode(CncRecentRoomsEnvelope.serializer())
                    ?.detail
                    ?.let { rooms -> CncRecentDetail(chatRooms = rooms) }
        }

    /**
     * Ask the server for everything newer than [since].
     *
     * How a socket chat catches up: the connection has been down, pushes were dropped under
     * doze, and whatever happened in between has to arrive somehow. The messages come back
     * on the `pending-messages:get` stream rather than in the acknowledgement.
     */
    fun requestMissedMessages(since: Long): Boolean {
        val payload = buildJsonObject {
            put("timestamp", since)
            put("project_id", projectId())
            put("platform", CncMessageDto.PLATFORM)
        }
        return socket.emit(PENDING_MESSAGES_GET, payload)
    }

    // ── Live events ──────────────────────────────────────────────────────────

    /** New messages on this surface, direct and group alike. */
    fun incomingMessages(surface: ChatSurface, isGroup: Boolean): Flow<CncMessageEnvelope> =
        socket.observe(
            event = if (isGroup) surface.groupMessage else surface.privateMessage,
            deserializer = CncMessageEnvelope.serializer(),
            // Own-device echoes are kept, unlike most events: a message sent from this
            // user's other phone still has to appear in this one's thread. The user gate is
            // off because a chat message addresses people as `sender` and `receiver` — it
            // carries no `user_id`, so the gate would only ever be a no-op or a surprise.
            gate = CHAT_GATE,
        )

    /**
     * Everything the server pushes after a [requestMissedMessages].
     *
     * Arrives in chunks: several events, each with a batch under `detail`, and only the
     * last carrying `last_chunk`. Every one of them was being read as a single message and
     * thrown away on a decode error, so nothing missed while offline ever arrived.
     */
    fun missedMessages(): Flow<CncMessageListEnvelope> =
        socket.observe(PENDING_MESSAGES_GET, CncMessageListEnvelope.serializer())

    fun readReceipts(surface: ChatSurface, isGroup: Boolean): Flow<CncReadReceiptEnvelope> =
        socket.observe(
            event = if (isGroup) surface.groupReadUntil else surface.privateReadUntil,
            deserializer = CncReadReceiptEnvelope.serializer(),
            // The user gate MUST stay off here. A read receipt's `user_id` is whoever did
            // the reading — the other person — so requiring it to be this user would drop
            // every receipt there is and no tick would ever turn blue.
            gate = CHAT_GATE,
        )

    /** The deleted messages themselves. Their ids are what the caller wants. */
    fun deletions(surface: ChatSurface, isGroup: Boolean): Flow<CncMessageListEnvelope> =
        socket.observe(
            event = if (isGroup) surface.groupDelete else surface.privateDelete,
            deserializer = CncMessageListEnvelope.serializer(),
        )

    fun edits(surface: ChatSurface, isGroup: Boolean): Flow<CncMessageEnvelope> =
        socket.observe(
            event = if (isGroup) surface.groupEdit else surface.privateEdit,
            deserializer = CncMessageEnvelope.serializer(),
        )

    /** One expired message per event, unlike a delete. */
    fun expiries(surface: ChatSurface, isGroup: Boolean): Flow<CncMessageEnvelope> =
        socket.observe(
            event = if (isGroup) surface.groupExpired else surface.privateExpired,
            deserializer = CncMessageEnvelope.serializer(),
        )

    fun reactions(surface: ChatSurface): Flow<CncMessageEnvelope> =
        socket.observe(surface.reaction, CncMessageEnvelope.serializer())

    fun typing(surface: ChatSurface, isGroup: Boolean): Flow<CncTypingEnvelope> =
        socket.observe(
            event = if (isGroup) surface.groupTyping else surface.privateTyping,
            deserializer = CncTypingEnvelope.serializer(),
            // Nothing gated: the typing payload carries neither project_id nor user_id in
            // the shape the gate reads, and its `sender` is by definition someone else. The
            // repository narrows it to the open thread.
            gate = EventGate(
                requireActiveProject = false,
                requireActiveUser = false,
                ignoreOwnDevice = false,
            ),
        )

    // ── Rooms ────────────────────────────────────────────────────────────────

    fun roomCreated(surface: ChatSurface): Flow<JsonObject> = socket.rawEvents(surface.roomCreated)
    fun roomUpdated(surface: ChatSurface): Flow<JsonObject> = socket.rawEvents(surface.roomUpdated)
    fun roomRemoved(surface: ChatSurface): Flow<JsonObject> = socket.rawEvents(surface.roomRemoved)

    /** Someone blocked or unblocked this user. */
    fun blocked(): Flow<JsonObject> = socket.rawEvents(CHAT_BLOCKED)
    fun unblocked(): Flow<JsonObject> = socket.rawEvents(CHAT_UNBLOCKED)

    // ── Internals ────────────────────────────────────────────────────────────

    /** True when this message may be emitted now. See [inFlight]. */
    private fun claimInFlight(uniqueId: String?): Boolean {
        if (uniqueId.isNullOrBlank()) return true
        val now = System.currentTimeMillis()
        // Prune here rather than on a timer: the map only grows while sends are happening.
        inFlight.entries.removeAll { now - it.value > IN_FLIGHT_WINDOW_MS }
        return inFlight.putIfAbsent(uniqueId, now) == null
    }

    private fun release(uniqueId: String?) {
        uniqueId?.let(inFlight::remove)
    }

    /** Called once a send is durably recorded, so a later edit or resend is not blocked. */
    fun clearInFlight(uniqueId: String?) = release(uniqueId)

    private fun projectId(): String? = session.activeProject.value?.projectId

    private fun CncMessageDto.asJson(): JsonObject =
        wireJson.encodeToJsonElement(CncMessageDto.serializer(), this) as JsonObject

    /**
     * Decode an acknowledgement, treating a shape we do not recognise as "no answer".
     *
     * The serializer is passed rather than reified: this needs the private [json], and an
     * inline reified helper cannot reach it.
     */
    private fun <T> JsonObject.decode(deserializer: DeserializationStrategy<T>): T? =
        runCatching { json.decodeFromJsonElement(deserializer, this) }
            .onFailure { ZillitLog.socketWarn("cnc decode failed: ${it.message}") }
            .getOrNull()

    private companion object {
        /** Status stamped on a message as it goes out. Mirrors v2. */
        const val STATUS_SENT = 1

        /**
         * Project-scoped, but neither user- nor device-scoped.
         *
         * Chat events name people with `sender` and `receiver`, and the one place a
         * `user_id` does appear — a read receipt — it belongs to the other person. Leaving
         * the default gate on would silently drop the events the thread is built from.
         */
        val CHAT_GATE = EventGate(
            requireActiveProject = true,
            requireActiveUser = false,
            ignoreOwnDevice = false,
        )

        const val TAG = "CncSocket"

        /** Not per-surface: the resync stream is shared by every chat on the socket. */
        const val PENDING_MESSAGES_GET = "pending-messages:get"

        const val CHAT_BLOCKED = "chat-communication:blocked"
        const val CHAT_UNBLOCKED = "chat-communication:un-blocked"

        const val IN_FLIGHT_WINDOW_MS = 30_000L
    }
}
