package com.zillit.zillitapp.core.socket

import com.zillit.zillitapp.BuildConfig
import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiHeaders
import com.zillit.zillitapp.core.network.NetworkMonitor
import com.zillit.zillitapp.core.network.ZillitCrypto
import com.zillit.zillitapp.core.session.SessionStore
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Handshake payload — v2's `ReqHeaderForSocket`, device id only. */
@Serializable
private data class SocketHandshake(
    @SerialName("device_id") val deviceId: String,
)

sealed interface SocketConnectionState {
    /** Not wanted, or explicitly stopped. */
    data object Idle : SocketConnectionState

    /** Wanted, but there is no usable network yet. Will connect when one appears. */
    data object WaitingForNetwork : SocketConnectionState

    data object Connecting : SocketConnectionState
    data object Connected : SocketConnectionState

    /** Dropped; the client is retrying with backoff. */
    data class Reconnecting(val attempt: Int) : SocketConnectionState

    /** Terminal — retrying will not help (bad key material, rejected handshake). */
    data class Failed(val reason: String) : SocketConnectionState
}

/**
 * Owns *the* Socket.IO connection. There is never more than one.
 *
 * Auth matches v2: the encrypted `moduledata` blob (device id only) goes in the
 * Socket.IO `auth` payload on handshake, not as an HTTP header.
 *
 * ### How the single-connection guarantee is enforced
 * [socket] is created only when it is null, and only inside [lock]. Every path that
 * disposes a socket nulls the field first. [start] is therefore idempotent: calling it
 * ten times from ten screens still yields one connection. A guard on `connected()` alone
 * would not be enough — a socket that is mid-handshake reports `connected() == false`,
 * so guarding on that would spawn a second connection under exactly the conditions
 * (slow network) where reconnects happen most.
 *
 * ### How recovery works
 * Three independent mechanisms, because they catch different failures:
 *
 *  1. **Socket.IO's own reconnection** (`reconnection = true`, exponential backoff to
 *     30s) handles a transport that errors or times out — the ordinary "signal dropped
 *     for a few seconds" case. v2 set `reconnection = false` and re-initialised by hand.
 *  2. **Network-available** — when [NetworkMonitor.isOnline] goes true we re-run
 *     [ensureConnected]. This covers the case where the device was fully offline: the
 *     client may have exhausted a backoff window while there was nothing to connect to.
 *  3. **Network-changed** — on a Wi-Fi↔cellular switch the existing TCP connection is
 *     bound to a network that no longer exists. It is not "slow", it is dead, and the
 *     client will not notice until ping timeout. So that case tears down and rebuilds
 *     rather than waiting.
 *
 * `forceNew = true` is set because socket.io-client caches a `Manager` per URL; reusing a
 * cached Manager after a network switch can hand back a dead transport. Since a socket is
 * always disposed before a new one is created, this creates fresh connections without
 * ever accumulating them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SocketManager @Inject constructor(
    private val session: SessionStore,
    private val crypto: ZillitCrypto,
    private val json: Json,
    private val networkMonitor: NetworkMonitor,
    private val tokenSession: dagger.Lazy<com.zillit.zillitapp.core.auth.TokenSession>,
    private val errorLog: dagger.Lazy<com.zillit.zillitapp.core.errorlog.ErrorLogReporter>,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val lock = Any()

    /** The one and only socket. Non-null means one exists; never replace without disposing. */
    private var socket: Socket? = null
        set(value) {
            field = value
            // Published so [rawEvents] can re-attach. A plain field meant a listener bound
            // to whichever instance existed when collection started — and if that was null,
            // or the socket was later rebuilt by a reconnect, the listener was silently
            // never attached again and the stream went dead for the rest of the session.
            liveSocket.value = value
        }

    /** The current instance, or null while there is none. Drives listener attachment. */
    private val liveSocket = MutableStateFlow<Socket?>(null)

    /** Whether the app *wants* a connection. Reconnect logic is a no-op when false. */
    private var desiredConnected: Boolean = false

    private val _connectionState = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Idle)
    val connectionState: StateFlow<SocketConnectionState> = _connectionState.asStateFlow()

    private var observersStarted = false

    /** Consecutive failed attempts, reset on a successful connect. Drives the backoff. */
    private var reconnectAttempt = 0

    /** The pending rebuild, so drops while waiting do not stack timers. */
    private var rebuildJob: kotlinx.coroutines.Job? = null

    /**
     * Declares that the app wants a live socket, and connects if possible.
     * Safe to call repeatedly and from anywhere.
     */
    fun start() {
        synchronized(lock) {
            desiredConnected = true
            startNetworkObservers()
        }
        ensureConnected()
    }

    /** Tears the connection down and stops all recovery. Call on logout. */
    fun stop() {
        synchronized(lock) {
            desiredConnected = false
            disposeSocketLocked()
            _connectionState.value = SocketConnectionState.Idle
        }
    }

    private fun startNetworkObservers() {
        if (observersStarted) return
        observersStarted = true

        scope.launch {
            networkMonitor.isOnline.collect { online ->
                if (online) {
                    ensureConnected()
                } else {
                    synchronized(lock) {
                        if (desiredConnected) {
                            _connectionState.value = SocketConnectionState.WaitingForNetwork
                        }
                    }
                }
            }
        }

        scope.launch {
            networkMonitor.networkChanges.collect {
                // The transport is bound to a network that is gone. Rebuild rather
                // than waiting for a ping timeout to discover it.
                forceReconnect()
            }
        }
    }

    /**
     * Connects if we want a connection, have a network, and do not already have one.
     * Idempotent by design — this is the only place a socket is created.
     */
    private fun ensureConnected() {
        synchronized(lock) {
            if (!desiredConnected) return
            if (!networkMonitor.isOnline.value) {
                _connectionState.value = SocketConnectionState.WaitingForNetwork
                return
            }

            val existing = socket
            if (existing != null) {
                // Already have one. If it is merely disconnected, nudge the *same*
                // instance rather than creating another.
                if (!existing.connected()) existing.connect()
                return
            }

            // The credential is chosen **now**, at this attempt — never captured earlier.
            // A reconnect hours after startup must present the current token, not the one
            // the process began with; that is the spec's Rule 1, and it is why every
            // rebuild path funnels through here.
            val auth = buildAuthOrNull()
            if (auth == null) {
                _connectionState.value = SocketConnectionState.Failed(
                    "Encryption key material missing for this build flavour",
                )
                return
            }

            ZillitLog.socket("Connecting to ${BuildConfig.CHAT_BASE_URL} (${auth.kind})")
            _connectionState.value = SocketConnectionState.Connecting
            socket = createSocket(auth).also { it.connect() }
        }
    }

    /** Disposes the current socket and builds a fresh one, if we still want one. */
    private fun forceReconnect() {
        synchronized(lock) {
            if (!desiredConnected) return
            disposeSocketLocked()
        }
        ensureConnected()
    }

    /** What the handshake presents: a bearer token in token mode, else moduledata. */
    private class SocketAuth(val kind: String, val payload: Map<String, String>)

    private fun buildAuthOrNull(): SocketAuth? {
        // Device token, not a project token: the socket is one connection per app instance
        // and outlives project switches, so its identity is the device.
        tokenSession.get().currentDeviceToken()?.let { token ->
            return SocketAuth(kind = "token", payload = mapOf("token" to token))
        }

        val handshake = crypto
            .encrypt(json.encodeToString(SocketHandshake.serializer(), SocketHandshake(session.deviceId)))
            .takeIf { it.isNotEmpty() }
            ?: return null
        return SocketAuth(kind = "moduledata", payload = mapOf(ApiHeaders.MODULE_DATA to handshake))
    }

    private fun createSocket(credential: SocketAuth): Socket {
        val options = IO.Options().apply {
            // Off, as v2 has it. socket.io's own retry reuses the `auth` map it was built
            // with, so every attempt replays the same encrypted handshake against a server
            // that may already have rejected it — which is what an endless run of
            // `xhr poll error` looks like. Reconnecting is driven below instead, and each
            // attempt builds a **fresh** socket with a freshly signed handshake.
            reconnection = false
            // Never reuse a cached Manager — see the class doc.
            forceNew = true
            auth = credential.payload
        }

        return IO.socket(BuildConfig.CHAT_BASE_URL, options).apply {
            // Catch-all: logs EVERY inbound emit, including ones nothing subscribes to.
            // Per-event logging only proves what we already listen for, so it cannot
            // answer "is the server sending this at all?" — which is the question that
            // matters when a screen is not updating.
            if (BuildConfig.DEBUG) {
                onAnyIncoming { args ->
                    val name = args.firstOrNull()?.toString().orEmpty()
                    val body = args.drop(1).joinToString(" ") { it?.toString().orEmpty() }
                    ZillitLog.payload(ZillitLog.TAG_SOCKET, "<<< $name", body)
                }
            }

            on(Socket.EVENT_CONNECT) {
                ZillitLog.socket("CONNECTED — joining user")
                reconnectAttempt = 0
                joinUser(this)
            }
            on(Socket.EVENT_DISCONNECT) { args ->
                val reason = args.firstOrNull()?.toString().orEmpty()
                ZillitLog.socket("DISCONNECTED reason=$reason")
                // "io client disconnect" is us hanging up on purpose — not a failure.
                if (reason != "io client disconnect") {
                    errorLog.get().reportSocketEvent(
                        name = "socket_disconnected",
                        errorMessage = reason,
                    )
                }
                scheduleRebuild()
            }
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                val message = args.firstOrNull()?.toString().orEmpty()
                ZillitLog.socketWarn("CONNECT_ERROR $message")
                errorLog.get().reportSocketEvent(
                    name = "socket_connect_failed",
                    errorMessage = message,
                )

                // Rule 3: a handshake rejected for a dead token is fixed by refreshing,
                // not by retrying — a plain rebuild would re-ship the same dead token
                // forever. The refresh is the API's own single-flight one, so a socket
                // rejection and an API 401 arriving together still rotate exactly once.
                if (message.contains(com.zillit.zillitapp.core.auth.TokenErrors.INVALID_TOKEN)) {
                    scope.launch {
                        tokenSession.get().refresh()
                        // Whatever the outcome, reconnect: with a fresh token if the
                        // refresh worked, and by moduledata fallback if the feature died.
                        scheduleRebuild()
                    }
                } else {
                    scheduleRebuild()
                }
            }
        }
    }

    /**
     * Announces this user to the server, and only then reports the socket as connected.
     *
     * **A connected socket is not a joined socket.** Until `user:join` is acknowledged the
     * server has a connection it cannot attribute to anyone, so it routes no events to it:
     * outbound emits still work — they carry their own ids — while nothing ever arrives.
     * That asymmetry is exactly what "the badge updates but the chat does not" looks like,
     * because the badge was coming from FCM.
     *
     * v2 emits this from its own `connect` handler for the same reason. Connected is
     * published only after the ack, so anything that waits on it — the missed-notification
     * replay in particular — cannot run against a socket the server will ignore.
     *
     * A missing ack must not strand the app offline forever, so the state is published
     * anyway after [JOIN_ACK_TIMEOUT_MS].
     */
    private fun joinUser(socket: Socket) {
        val reported = java.util.concurrent.atomic.AtomicBoolean(false)
        fun publishConnected(via: String) {
            // compareAndSet so the ack and the timeout cannot both report.
            if (!reported.compareAndSet(false, true)) return
            ZillitLog.socket("JOINED ($via) — socket is live")
            _connectionState.value = SocketConnectionState.Connected
        }

        socket.emit(SocketEvents.USER_JOIN, null, null, Ack { args ->
            ZillitLog.payload(
                ZillitLog.TAG_SOCKET,
                "<< ${SocketEvents.USER_JOIN} ack",
                args.firstOrNull()?.toString(),
            )
            publishConnected("ack")
        })

        scope.launch {
            delay(JOIN_ACK_TIMEOUT_MS)
            if (socket.connected() && !reported.get()) {
                ZillitLog.socketWarn("${SocketEvents.USER_JOIN} not acknowledged — continuing anyway")
                publishConnected("timeout")
            }
        }
    }

    /**
     * Rebuilds the connection after a drop, with backoff.
     *
     * Rebuild rather than reconnect: the handshake is signed once per socket, so reviving
     * the same instance replays a handshake the server has already seen. Disposing and
     * recreating produces a fresh one — and, because listeners follow the live instance,
     * they re-attach to the new socket automatically.
     *
     * Only one rebuild is ever in flight; a second drop while waiting is ignored rather
     * than stacking timers.
     */
    private fun scheduleRebuild() {
        synchronized(lock) {
            if (!desiredConnected) return
            if (rebuildJob?.isActive == true) return

            val attempt = ++reconnectAttempt
            val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(5)))
                .coerceAtMost(MAX_RECONNECT_DELAY_MS)

            _connectionState.value = SocketConnectionState.Reconnecting(attempt)
            ZillitLog.socket("RECONNECT attempt #$attempt in ${delayMs}ms")

            rebuildJob = scope.launch {
                delay(delayMs)

                // The retry loop goes through the token check (the spec calls this out for
                // Android specifically): if the stored token has expired while we waited,
                // refresh before the attempt — otherwise the loop presents a dead token
                // indefinitely and every attempt fails the same way.
                val tokens = tokenSession.get()
                if (tokens.enabled.value && tokens.currentDeviceToken() == null) {
                    tokens.refresh()
                }

                // A network the client cannot reach makes this pointless; the network
                // observers re-drive it the moment one appears.
                if (networkMonitor.isOnline.value) forceReconnect() else ensureConnected()
            }
        }
    }

    /** Caller must hold [lock]. */
    private fun disposeSocketLocked() {
        if (socket != null) ZillitLog.socket("Disposing socket")
        socket?.apply {
            off()
            runCatching { io().off() }
            disconnect()
            close()
        }
        socket = null
    }

    /**
     * Typed stream for one server event.
     *
     * The listener is attached when collection starts and removed when it stops, so a
     * screen that goes away stops receiving. v2 registered every listener once, globally,
     * for the process lifetime.
     */
    fun <T> observe(
        event: String,
        deserializer: DeserializationStrategy<T>,
        gate: EventGate = EventGate.Default,
    ): Flow<T> = rawEvents(event)
        .mapNotNull { payload ->
            if (!gate.allows(payload, session)) {
                // Named so a dropped event is traceable to the rule that dropped it. The
                // usual one is `ignoreOwnDevice`: a notification caused by this device's
                // own action is discarded by design, which looks identical to "nothing
                // arrived" when only the outbound side is logged.
                ZillitLog.socketWarn("<< $event dropped by gate ${gate.describe(payload, session)}")
                return@mapNotNull null
            }
            runCatching { json.decodeFromJsonElement(deserializer, payload) }
                .onFailure { ZillitLog.socketWarn("<< $event decode failed: ${it.message}") }
                .getOrNull()
        }

    /**
     * Untyped stream. Re-attaches the listener across reconnects, because the [Socket]
     * instance can be replaced underneath a long-lived collector.
     */
    fun rawEvents(event: String): Flow<JsonObject> = liveSocket
        .flatMapLatest { instance ->
            // Null is a legitimate state, not an error: the socket is built lazily once
            // there is a network and key material. An empty flow here simply waits, and
            // the next instance re-subscribes.
            if (instance == null) return@flatMapLatest emptyFlow()

            callbackFlow {
                val listener = Emitter.Listener { args ->
                    // Some emits send the payload as an object, others wrap it in a
                    // one-element array — v2 unwraps with `JSONArray(data)[0]` for exactly
                    // this reason. Accepting only an object silently discarded every
                    // array-shaped event, which is what stopped chat updating live.
                    val first = when (val raw = args.firstOrNull()) {
                        is JSONObject -> raw
                        is JSONArray -> raw.optJSONObject(0)
                        else -> null
                    }
                    if (first == null) {
                        ZillitLog.socketWarn("<< $event ignored — payload was neither object nor array")
                        return@Listener
                    }

                    // Logged before anything can drop it. Without this an inbound event that
                    // the gate rejects is indistinguishable from one that never arrived,
                    // which is exactly the question "why is my badge not moving?" turns into.
                    ZillitLog.payload(ZillitLog.TAG_SOCKET, "<< $event", first.toString())

                    runCatching { json.parseToJsonElement(first.toString()).jsonObject }
                        .onFailure { ZillitLog.socketWarn("<< $event unparseable: ${it.message}") }
                        .getOrNull()
                        ?.let { trySend(it) }
                }

                instance.on(event, listener)
                awaitClose { instance.off(event, listener) }
            }
        }

    /**
     * Fire-and-forget emit.
     *
     * @return false when there was no live connection and nothing was sent. Callers with a
     *   REST equivalent — mark-read, delete — must check it and fall back, the way v2 does:
     *   silently dropping a read means the badge comes back on the next sync.
     */
    fun emit(event: String, payload: JsonObject): Boolean = synchronized(lock) {
        val live = socket?.takeIf { it.connected() }
        if (live == null) {
            ZillitLog.socketWarn(">> $event dropped — socket not connected")
            return false
        }
        ZillitLog.payload(ZillitLog.TAG_SOCKET, ">> $event", payload.toString())
        live.emit(event, JSONObject(payload.toString()))
        true
    }

    /**
     * Emit and wait for the server's acknowledgement.
     *
     * Some writes only exist as socket calls — v2 clears the whole notification list this
     * way and has no REST equivalent — so the caller genuinely needs to know whether the
     * server took it before updating the screen.
     *
     * Returns null when there is no live connection or the ack does not arrive within
     * [timeoutMs]; a null is "unknown", not "failed", and callers should re-read rather
     * than assume either outcome.
     */
    suspend fun emitWithAck(
        event: String,
        payload: JsonObject,
        timeoutMs: Long = ACK_TIMEOUT_MS,
    ): JsonObject? {
        val live = synchronized(lock) { socket?.takeIf { it.connected() } }
        if (live == null) {
            ZillitLog.socketWarn(">> $event dropped — socket not connected")
            return null
        }

        ZillitLog.payload(ZillitLog.TAG_SOCKET, ">> $event", payload.toString())

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                live.emit(event, JSONObject(payload.toString()), Ack { args ->
                    // The ack arrives on the socket's own thread; resuming more than once
                    // would crash, and a server that double-acks is not hypothetical.
                    if (!continuation.isActive) return@Ack

                    val first = args.firstOrNull()
                    val parsed = runCatching {
                        json.parseToJsonElement(first.toString()).jsonObject
                    }.getOrNull()

                    ZillitLog.payload(ZillitLog.TAG_SOCKET, "<< $event ack", first.toString())
                    continuation.resume(parsed)
                })
            }
        }
    }

    private companion object {
        const val TAG = "SocketManager"
        const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L

        /** Long enough for a slow server, short enough that the UI is not stuck. */
        const val ACK_TIMEOUT_MS = 10_000L

        /** How long to wait for the join ack before proceeding regardless. */
        const val JOIN_ACK_TIMEOUT_MS = 3_000L
    }
}

/**
 * The checks v2 applied by hand inside each listener, made declarative and mandatory.
 *
 * Missing either caused real bug classes in v2: events from another project leaking into
 * the current one, and a device reacting to the echo of its own write.
 */
data class EventGate(
    /** Drop payloads whose `project_id` is not the active project. */
    val requireActiveProject: Boolean = true,
    /**
     * Drop payloads addressed to a different **user** on this project.
     *
     * The same person can hold accounts on several projects, and the backend fans some
     * events out per user. Matching the project alone is not enough: an event carrying a
     * `user_id` for a different member would otherwise be applied as if it were ours. Only
     * enforced when the payload actually carries a `user_id` — most events are project-wide
     * and carry none.
     */
    val requireActiveUser: Boolean = true,
    /** Drop payloads this device originated, identified by `device_id`. */
    val ignoreOwnDevice: Boolean = true,
) {
    fun allows(payload: JsonObject, session: SessionStore): Boolean {
        if (requireActiveProject) {
            // Absent means "not project-scoped", which is allowed — v2 gates the same way
            // (`if (pid.isNotEmpty() && active != pid)`). Rejecting on absence instead
            // discarded every chat event whose project_id sits inside `data`, so messages
            // from other people never appeared until a refetch.
            val eventProject = payload.idOrNull("project_id")
            val activeProject = session.activeProject.value?.projectId
            if (!eventProject.isNullOrEmpty() && eventProject != activeProject) return false
        }
        if (requireActiveUser) {
            // Absent means project-wide, which is allowed. Present and different means the
            // event belongs to someone else's session on this project.
            val eventUser = payload.idOrNull("user_id")
            val activeUser = session.activeProject.value?.userId
            if (!eventUser.isNullOrEmpty() && !activeUser.isNullOrEmpty() && eventUser != activeUser) {
                return false
            }
        }
        if (ignoreOwnDevice) {
            val originDevice = payload.idOrNull("device_id")
            if (!originDevice.isNullOrEmpty() && originDevice == session.deviceId) return false
        }
        return true
    }

    /**
     * Which rule rejected a payload, for the log.
     *
     * Returns the first failing rule with both values, because "dropped" on its own says
     * nothing — the answer is almost always that the ids differ by something specific.
     */
    fun describe(payload: JsonObject, session: SessionStore): String {
        if (requireActiveProject) {
            val eventProject = payload.idOrNull("project_id")
            val activeProject = session.activeProject.value?.projectId
            if (!eventProject.isNullOrEmpty() && eventProject != activeProject) {
                return "requireActiveProject (event=$eventProject active=$activeProject)"
            }
        }
        if (requireActiveUser) {
            val eventUser = payload.idOrNull("user_id")
            val activeUser = session.activeProject.value?.userId
            if (!eventUser.isNullOrEmpty() && !activeUser.isNullOrEmpty() && eventUser != activeUser) {
                return "requireActiveUser (event=$eventUser active=$activeUser)"
            }
        }
        if (ignoreOwnDevice) {
            val originDevice = payload.idOrNull("device_id")
            if (!originDevice.isNullOrEmpty() && originDevice == session.deviceId) {
                return "ignoreOwnDevice (this device raised it)"
            }
        }
        return "none"
    }

    companion object {
        /** Both gates on. Correct for any project-scoped event. */
        val Default = EventGate()

        /** For account-level events that legitimately arrive outside a project. */
        val AccountLevel = EventGate(
            requireActiveProject = false,
            requireActiveUser = false,
            ignoreOwnDevice = true,
        )

        /**
         * Project-scoped, accepts this device's own echo, and ignores `user_id`.
         *
         * Chat uses this, and both relaxations are load-bearing:
         *
         *  - **Own echo kept.** The server's copy of a message we sent is the
         *    authoritative one — it carries the server `_id` the row needs before it can
         *    be replied to or deleted — so discarding it leaves the local row without one.
         *  - **`user_id` not checked.** On a chat event that field is the **sender**, not
         *    the addressee. Requiring it to match the signed-in user therefore dropped
         *    every message from another person — which is precisely the traffic the screen
         *    exists to show. v2 gates these on `project_id` alone, and so does this.
         */
        val IncludingOwnDevice = EventGate(
            requireActiveUser = false,
            ignoreOwnDevice = false,
        )

        /** No filtering. Only when the event carries no project or device field. */
        val Unfiltered = EventGate(
            requireActiveProject = false,
            requireActiveUser = false,
            ignoreOwnDevice = false,
        )
    }
}

private fun kotlinx.serialization.json.JsonElement.contentOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return if (primitive is JsonNull) null else primitive.content
}

/**
 * Reads an id the backend may place in any of several shapes.
 *
 * Across the socket API the same field turns up at the payload root, nested under `data`,
 * and occasionally as a single-element array. Every call site that read it one way was
 * therefore right for some events and wrong for others — and "wrong" meant the event was
 * silently dropped. This is the one place that knows all of them.
 */
private fun JsonObject.idOrNull(key: String): String? {
    fun kotlinx.serialization.json.JsonElement.scalar(): String? = when (this) {
        is JsonPrimitive -> if (this is JsonNull) null else content.takeIf { it.isNotEmpty() }
        // Single-element arrays appear on a few emits; anything longer is not an id.
        is kotlinx.serialization.json.JsonArray -> singleOrNull()?.scalar()
        else -> null
    }

    this[key]?.scalar()?.let { return it }
    return (this["data"] as? JsonObject)?.get(key)?.scalar()
}
