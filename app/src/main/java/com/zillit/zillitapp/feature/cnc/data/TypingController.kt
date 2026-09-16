package com.zillit.zillitapp.feature.cnc.data

import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.socket.SocketManager
import com.zillit.zillitapp.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Rudra is typing…", both directions.
 *
 * ### The wire contract, matched to v2
 * One event per kind — `private-chat:typing` and `group-chat:typing`, prefixed `budget:`
 * on the Budget surface — carrying:
 *
 * ```
 * { receiver, sender, status: "start" | "end",
 *   chat_tool, department_id, budget_document_id, platform: "android" }
 * ```
 *
 * A receiver accepts the event only when `chat_tool` matches its own and the sender or
 * receiver is the thread it has open, which is why those fields travel on every emit even
 * when they are null.
 *
 * ### Where this deliberately differs from v2
 * v2 throttles to one `start` a second and **never sends `end`** — its `Typing.End` branch
 * has no caller. Every other client therefore waits out the full four-second timeout after
 * someone stops, so the indicator lingers on a thread nobody is writing in.
 *
 * This sends `end` when typing actually stops. It is the same event with the status the
 * contract already defines, and v2 and web both clear immediately on it, so nothing has to
 * change anywhere else for it to work.
 *
 * ### One instance, or nothing arrives
 * `CncRealtime` feeds remote events in and `CncThreadViewModel` reads [typing] out. Without
 * the singleton scope Hilt hands each of them its own controller, so the realtime layer
 * updates a flow nobody observes and the thread watches one nobody writes — outgoing typing
 * works (the view model's own copy emits) while incoming never shows. That was live.
 */
@Singleton
class TypingController @Inject constructor(
    private val socket: SocketManager,
    private val session: SessionStore,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _typing = MutableStateFlow<List<TypingUser>>(emptyList())

    /** Who is typing in the open thread, newest first. Empty when nobody is. */
    val typing: StateFlow<List<TypingUser>> = _typing.asStateFlow()

    /** Fires `end` once the keystrokes stop. */
    private var stopJob: Job? = null

    /** Suppresses repeat `start` emits inside one window. */
    private var startedAt = 0L

    /** Per-sender expiry timers, so one person going quiet cannot clear another. */
    private val expiryJobs = mutableMapOf<String, Job>()

    /**
     * Call on every keystroke.
     *
     * Cheap to call: at most one emit per [START_INTERVAL_MS] regardless of typing speed,
     * and the stop timer is simply rescheduled.
     */
    fun onTyping(target: TypingTarget) {
        val now = System.currentTimeMillis()
        if (now - startedAt >= START_INTERVAL_MS) {
            startedAt = now
            emit(target, START)
        }

        stopJob?.cancel()
        stopJob = scope.launch {
            delay(STOP_AFTER_MS)
            stop(target)
        }
    }

    /**
     * Call when the composer is cleared, sent, or the thread is left.
     *
     * Leaving must emit too: a thread closed mid-word otherwise shows this user typing for
     * four more seconds on every other device.
     */
    fun stop(target: TypingTarget) {
        stopJob?.cancel()
        stopJob = null
        if (startedAt == 0L) return
        startedAt = 0L
        emit(target, END)
    }

    private fun emit(target: TypingTarget, status: String) {
        val sender = session.activeProject.value?.userId ?: return
        val event = if (target.isGroup) target.surface.groupTyping else target.surface.privateTyping
        socket.emit(event, payload(target, sender, status))
    }

    private fun payload(target: TypingTarget, sender: String, status: String): JsonObject =
        buildJsonObject {
            put("receiver", target.conversationId)
            put("sender", sender)
            put("status", status)
            put("chat_tool", target.chatTool)
            put("department_id", target.departmentId)
            put("budget_document_id", target.budgetDocumentId)
            put("platform", PLATFORM)
        }

    /**
     * Feed in an incoming typing event, already filtered to the open thread.
     *
     * @param senderId who is typing
     * @param senderName how to name them — a group says who, a 1-1 does not need to
     * @param isStart false for an explicit `end`
     */
    fun onRemoteTyping(senderId: String, senderName: String, isStart: Boolean) {
        expiryJobs.remove(senderId)?.cancel()

        if (!isStart) {
            _typing.value = _typing.value.filterNot { it.id == senderId }
            return
        }

        _typing.value = listOf(TypingUser(senderId, senderName)) +
            _typing.value.filterNot { it.id == senderId }

        // The safety net for a client that sends `start` and never `end` — which is every
        // v2 device in the field. Per sender, so two people typing expire independently.
        expiryJobs[senderId] = scope.launch {
            delay(REMOTE_EXPIRY_MS)
            expiryJobs.remove(senderId)
            _typing.value = _typing.value.filterNot { it.id == senderId }
        }
    }

    /** Drop everything on leaving a thread, so the next one does not open mid-sentence. */
    fun clear() {
        expiryJobs.values.forEach(Job::cancel)
        expiryJobs.clear()
        _typing.value = emptyList()
        stopJob?.cancel()
        stopJob = null
        startedAt = 0L
    }

    private companion object {
        const val START = "start"
        const val END = "end"
        const val PLATFORM = "android"

        /** At most one `start` per second, matching v2's throttle. */
        const val START_INTERVAL_MS = 1_000L

        /** Quiet for this long and we are no longer typing. */
        const val STOP_AFTER_MS = 2_000L

        /**
         * How long a remote `start` survives without an `end`.
         *
         * v2's timeout, kept exactly: shortening it would blink the indicator against v2
         * senders, which only emit once a second.
         */
        const val REMOTE_EXPIRY_MS = 4_000L
    }
}

/** Somebody typing in the open thread. */
data class TypingUser(val id: String, val name: String)

/**
 * Everything an emit needs to address one thread.
 *
 * Carried as a value rather than read from a controller field, because a socket chat can
 * have C&C and a Budget thread open in different back-stack entries and a shared mutable
 * "current thread" is how v2's typing ends up on the wrong conversation.
 */
data class TypingTarget(
    val surface: ChatSurface,
    /** The other person's user id, or the room id. */
    val conversationId: String,
    val isGroup: Boolean,
    /** The receiving side filters on this; it must match what the thread was opened with. */
    val chatTool: String,
    val departmentId: String? = null,
    val budgetDocumentId: String? = null,
)
