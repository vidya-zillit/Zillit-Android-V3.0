package com.zillit.zillitapp.core.calendar

import com.zillit.zillitapp.core.calendar.data.CalendarRepository
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.notification.NotificationRepository
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.socket.EventGate
import com.zillit.zillitapp.core.socket.SocketEvents
import com.zillit.zillitapp.core.socket.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** What changed, and to which event. */
data class CalendarChange(
    val action: Action,
    val eventId: String,
    /**
     * The event itself, when it could be fetched by id.
     *
     * Present for creates and edits, so a screen can show the change immediately instead
     * of waiting for its next window fetch to catch up. Null for deletes and for a fetch
     * that failed.
     */
    val event: CalendarEvent? = null,
) {
    enum class Action { CREATED, EDITED, DELETED }
}

/**
 * Keeps every open calendar screen in step with what other people are doing.
 *
 * Two things happen for each socket message, deliberately:
 *
 *  1. **A targeted fetch of that one event by id.** The events *index* is eventually
 *     consistent — a window refetched a few hundred milliseconds after a create routinely
 *     comes back without the new event, so a screen that only refetched its window would
 *     show nothing until the user did something else. Reading the event by id sees it
 *     immediately. v2 hit exactly this and documents it at length.
 *  2. **A broad refresh signal**, because the grid's per-day counts, the agenda and the
 *     lists all cover more than the one event that changed.
 *
 * Events this device caused are suppressed for a short window: the screen that made the
 * change has already refreshed, and reacting to the echo makes it do it twice.
 */
@Singleton
class CalendarRealtime @Inject constructor(
    private val socket: SocketManager,
    private val repository: CalendarRepository,
    private val session: SessionStore,
    private val notifications: NotificationRepository,
) {

    private val scope = CoroutineScope(SupervisorJob())

    private val _changes = MutableSharedFlow<CalendarChange>(extraBufferCapacity = 8)

    /** Every calendar change from another device or another person. */
    val changes: SharedFlow<CalendarChange> = _changes.asSharedFlow()

    @Volatile
    private var selfActionUntil: Long = 0

    init {
        observe()
        observeNotifications()
    }

    /**
     * Call immediately **before** a local create, edit, delete or invitation answer.
     *
     * The server echoes the change back over the socket, and the screen that made it has
     * already updated — without this it refreshes a second time a moment later, which
     * reads as a flicker.
     */
    fun markSelfAction() {
        selfActionUntil = System.currentTimeMillis() + SELF_ACTION_WINDOW_MS
    }

    private fun observe() {
        scope.launch {
            merge(
                socket.rawEvents(SocketEvents.CALENDAR_EVENT_CREATE)
                    .toChanges(CalendarChange.Action.CREATED),
                socket.rawEvents(SocketEvents.CALENDAR_EVENT_EDIT)
                    .toChanges(CalendarChange.Action.EDITED),
                socket.rawEvents(SocketEvents.CALENDAR_EVENT_DELETE)
                    .toChanges(CalendarChange.Action.DELETED),
            ).collect { change -> _changes.tryEmit(change) }
        }
    }

    private fun kotlinx.coroutines.flow.Flow<JsonObject>.toChanges(
        action: CalendarChange.Action,
    ) = kotlinx.coroutines.flow.flow {
        collect { payload ->
            if (System.currentTimeMillis() < selfActionUntil) {
                ZillitLog.socket("calendar: ignoring own $action echo")
                return@collect
            }

            // Gated here rather than through `observe(...)` because these payloads are
            // read untyped: the same rules, applied to the raw object.
            if (!GATE.allows(payload, session)) {
                ZillitLog.socketWarn(
                    "calendar: $action dropped by gate ${GATE.describe(payload, session)}",
                )
                return@collect
            }

            val eventId = payload.eventId()
            if (eventId.isNullOrBlank()) {
                ZillitLog.socketWarn("calendar: $action carried no event_id")
                return@collect
            }

            // Deletes have nothing to fetch, and the fetch would 404.
            val event = if (action == CalendarChange.Action.DELETED) {
                null
            } else {
                (repository.event(eventId) as? ApiResult.Success)?.data
            }

            ZillitLog.socket("calendar: $action $eventId${if (event != null) " (fetched)" else ""}")
            emit(CalendarChange(action, eventId, event))
        }
    }

    /**
     * The other way a calendar change arrives.
     *
     * Confirmed against a live create: the backend did **not** emit `create:event` at all —
     * it sent `notification:save` carrying the event under `calendar_data`. Reacting to the
     * stored notification rather than only to the socket also makes this channel-agnostic:
     * the same change delivered by FCM while the app was backgrounded lands here too.
     */
    private fun observeNotifications() {
        scope.launch {
            notifications.stored
                .filter { it.eventEndAt > 0 }
                .collect { incoming ->
                    if (System.currentTimeMillis() < selfActionUntil) return@collect

                    val eventId = incoming.referenceId
                    if (eventId.isNullOrBlank()) return@collect

                    val event = (repository.event(eventId) as? ApiResult.Success)?.data
                    ZillitLog.socket("calendar: notification for $eventId → refreshing")
                    // Reported as an edit: the notification says the event changed, not
                    // how, and both cases resolve to the same "re-read and merge".
                    _changes.tryEmit(CalendarChange(CalendarChange.Action.EDITED, eventId, event))
                }
        }
    }

    /**
     * The event id, from the top level or from a nested `data` object.
     *
     * Both shapes are in use — the same divergence the chat events have.
     */
    private fun JsonObject.eventId(): String? {
        (this["event_id"] as? JsonPrimitive)?.contentOrNull?.let { return it }
        val nested = (this["data"] as? JsonObject) ?: return null
        return (nested["event_id"] as? JsonPrimitive)?.contentOrNull
            ?: (nested["_id"] as? JsonPrimitive)?.contentOrNull
    }

    private companion object {
        /**
         * Matches v2's two seconds — long enough to cover the round trip that produced the
         * echo, short enough that a genuine change arriving right after a local one is not
         * swallowed.
         */
        const val SELF_ACTION_WINDOW_MS = 2_000L

        /**
         * Project-scoped, and this device's own echoes dropped.
         *
         * `requireActiveUser` is off: a calendar event carries the id of whoever changed
         * it, which is precisely the person whose changes we want to hear about.
         */
        val GATE = EventGate(requireActiveUser = false)
    }
}
