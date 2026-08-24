package com.zillit.zillitapp.core.calendar.data

import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.calendar.model.EditScope
import com.zillit.zillitapp.core.calendar.model.Invitation
import com.zillit.zillitapp.core.calendar.model.OccurrenceStatuses
import com.zillit.zillitapp.core.calendar.model.Timezone
import com.zillit.zillitapp.core.common.toApiDate
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The calendar's data layer.
 *
 * Network-backed rather than Realm-first, unlike chat, and deliberately so: the backend
 * **expands recurring series server-side**, so what comes back for a window is not a set of
 * stored rows but a computed view of them. Caching the expansion would mean re-implementing
 * recurrence rules on the client to keep it honest, which is the part that is genuinely
 * hard to get right. The window is small and the fetch is fast, so it is re-read instead.
 *
 * Events still render instantly on revisit because the ViewModel keeps the last window in
 * memory; what is not promised is offline access to a month never opened.
 */
@Singleton
class CalendarRepository @Inject constructor(
    private val api: ZillitApi,
) {

    /**
     * Every event overlapping a window, with recurring series already expanded.
     *
     * @param hideRejected drops invitations this user declined. The Declined screen passes
     *   false to show exactly those.
     */
    suspend fun events(
        startMs: Long,
        endMs: Long,
        hideRejected: Boolean = true,
    ): ApiResult<List<CalendarEvent>> = api.get<EventListResponse>(
        url = ApiEndpoints.Calendar.EVENTS,
        module = ModuleData.WITH_PROJECT_USER_ID,
        query = mapOf(
            "start_date" to startMs.toString(),
            "end_date" to endMs.toString(),
            "hide_rejected" to hideRejected.toString(),
        ),
    ).map { response ->
        // Cancelled occurrences are deliberately kept: the backend returns them flagged
        // rather than omitted, and v2 renders them struck through. Filtering them out
        // would make a called-off shoot day look like it was never scheduled.
        response.data
            .map { it.toDomain() }
            .sortedBy { it.startDatetime }
    }

    /**
     * One event by id.
     *
     * This is what a socket `event_id` is resolved through. It matters that this is a
     * per-event read rather than a window refetch: the events index is eventually
     * consistent, so a just-created event is frequently missing from a window fetched
     * milliseconds later, while reading it by id sees it immediately.
     *
     * @param occurrenceDate identifies which occurrence of a recurring series is wanted.
     */
    suspend fun event(eventId: String, occurrenceDate: String? = null): ApiResult<CalendarEvent?> =
        api.get<EventResponse>(
            url = "${ApiEndpoints.Calendar.EVENT}$eventId",
            module = ModuleData.WITH_PROJECT_USER_ID,
            query = occurrenceDate?.let { mapOf("occurrence_date" to it) }.orEmpty(),
        ).map { it.data?.toDomain() }

    /**
     * Every occurrence of a recurring series, for the expandable card.
     *
     * There is no "occurrences of X" endpoint — the window fetch is the only thing that
     * expands a series, so this asks for the series' own span and keeps the rows belonging
     * to it. Matching runs against the whole **chain** of master ids, not just this one:
     * editing "this and following" splits a series into several masters, and matching one
     * would silently stop the list at the split.
     *
     * A series with no end date is bounded at a year — an unbounded window would ask the
     * server to expand forever.
     */
    suspend fun occurrences(event: CalendarEvent): ApiResult<List<CalendarEvent>> {
        val start = event.recurrence.from ?: event.startDatetime
        val end = event.recurrence.until ?: (start + OPEN_ENDED_WINDOW_MS)
        // From the master, not `id`: an expanded occurrence has no `_id` of its own,
        // so keying off it would match nothing.
        val chain = setOf(event.effectiveMasterEventId) + event.siblingIds

        return events(start, end).map { window ->
            window.filter { (it.masterEventId ?: it.id) in chain }
        }
    }

    /**
     * Invitations addressed to this user — one page of the Received list.
     *
     * Paged rather than fetched whole: a long-running production accumulates hundreds of
     * invitations, and the screen only ever shows a screenful.
     *
     * @param status which tab: pending, accepted or expired.
     * @param cursor null for the first page, then whatever the previous page returned.
     * @param date filters to a single day, as epoch millis at its start.
     */
    suspend fun invitations(
        status: String,
        cursor: String? = null,
        limit: Int = PAGE_SIZE,
        search: String? = null,
        date: Long? = null,
    ): ApiResult<Page<InvitationWithEvent>> =
        api.get<PagedResponse<InvitationWithEventDto>>(
            url = ApiEndpoints.Calendar.INVITES,
            module = ModuleData.WITH_PROJECT_USER_ID,
            query = pageQuery(cursor, limit, search, date) + ("status" to status),
        ).map { response ->
            Page(
                // A row whose event the server could not attach is dropped rather than
                // rendered as a blank card.
                items = response.data?.items.orEmpty().mapNotNull { it.toDomain() },
                cursor = response.data?.cursor,
                hasMore = response.data?.hasMore ?: false,
            )
        }

    /**
     * Events this user created.
     *
     * @param filter `members`, `personal` or `expired` — the three tabs.
     */
    suspend fun createdEvents(
        filter: String,
        cursor: String? = null,
        limit: Int = PAGE_SIZE,
        search: String? = null,
        date: Long? = null,
    ): ApiResult<Page<CalendarEvent>> = api.get<PagedResponse<EventDto>>(
        url = ApiEndpoints.Calendar.CREATED_EVENTS,
        module = ModuleData.WITH_PROJECT_USER_ID,
        query = pageQuery(cursor, limit, search, date) + ("filter" to filter),
    ).toEventPage()

    /** This user's events that someone declined. */
    suspend fun declinedCreatedEvents(
        scope: String = SCOPE_ACTIVE,
        cursor: String? = null,
        limit: Int = PAGE_SIZE,
        search: String? = null,
        date: Long? = null,
    ): ApiResult<Page<CalendarEvent>> = api.get<PagedResponse<EventDto>>(
        url = ApiEndpoints.Calendar.DECLINED_CREATED,
        module = ModuleData.WITH_PROJECT_USER_ID,
        query = pageQuery(cursor, limit, search, date) + ("scope" to scope),
    ).toEventPage()

    /**
     * Events with a joinable call on [date].
     *
     * The only list that is **not** paged and takes no search — the server returns the
     * whole day's set, and the screen filters titles locally.
     */
    suspend fun joinCallEvents(date: Long): ApiResult<List<CalendarEvent>> =
        api.get<EventListResponse>(
            url = ApiEndpoints.Calendar.JOIN_CALL,
            module = ModuleData.WITH_PROJECT_USER_ID,
            query = mapOf("date" to date.toString()),
        ).map { response -> response.data.map { it.toDomain() } }

    /**
     * Accepts an invitation.
     *
     * @param eventId must be the **master** id — see
     *   [CalendarEvent.effectiveMasterEventId]. An occurrence is addressed by its master
     *   plus [occurrenceDate], never by its own id.
     * @param scope how much of a series this answers for. Sent even for a one-off, where
     *   the server expects `all`.
     * @param occurrenceDate epoch millis of the occurrence being answered for. Omitted for
     *   [EditScope.ALL], where it would contradict the scope.
     */
    suspend fun accept(
        eventId: String,
        scope: EditScope = EditScope.ALL,
        occurrenceDate: Long? = null,
    ): ApiResult<Unit> = api.put<JsonObject, EventResponse>(
        url = "${ApiEndpoints.Calendar.INVITE_ACCEPT}$eventId",
        body = invitationBody(scope, occurrenceDate),
        module = ModuleData.WITH_PROJECT_USER_ID,
    ).map { }

    /** Declines an invitation. [reason] is shown to the organiser. */
    suspend fun reject(
        eventId: String,
        scope: EditScope = EditScope.ALL,
        occurrenceDate: Long? = null,
        reason: String = "",
    ): ApiResult<Unit> = api.put<JsonObject, EventResponse>(
        url = "${ApiEndpoints.Calendar.INVITE_REJECT}$eventId",
        body = invitationBody(scope, occurrenceDate, reason),
        module = ModuleData.WITH_PROJECT_USER_ID,
    ).map { }

    /**
     * The accept/reject body.
     *
     * `occurrence_date` is a **number**, not the `yyyy-MM-dd` string the query parameters
     * elsewhere use — the two endpoints genuinely differ, and sending the string form here
     * is accepted with a 200 while answering for the whole series.
     */
    private fun invitationBody(
        scope: EditScope,
        occurrenceDate: Long?,
        reason: String = "",
    ): JsonObject = buildJsonObject {
        put("scope", scope.apiValue)
        if (scope != EditScope.ALL) {
            occurrenceDate?.let { put("occurrence_date", it) }
        }
        if (reason.isNotBlank()) put("rejection_reason", reason)
    }

    /**
     * Every invitee on an event, with their own status.
     *
     * Separate from the invitations embedded in an event because the event response caps
     * them — a 40-person call comes back with a count and a handful of names, and the
     * attendee list needs all of them.
     */
    suspend fun eventInvitations(
        eventId: String,
        search: String? = null,
        occurrenceDate: String? = null,
    ): ApiResult<List<Invitation>> = api.get<InvitationListResponse>(
        url = ApiEndpoints.Calendar.INVITATION_LIST,
        module = ModuleData.WITH_PROJECT_USER_ID,
        query = buildMap {
            put("event_id", eventId)
            search?.takeIf { it.isNotBlank() }?.let { put("search", it) }
            occurrenceDate?.let { put("occurrence_date", it) }
        },
    ).map { response -> response.data.map { it.toDomain() } }

    /**
     * How **this user** answered each occurrence of a recurring series.
     *
     * Scoped to the caller by the server, so the result belongs to the signed-in user only
     * — see [com.zillit.zillitapp.core.calendar.model.InvitationStatusResolver] for why it
     * must not be applied to anyone else's row.
     */
    suspend fun occurrenceStatuses(masterEventId: String): ApiResult<OccurrenceStatuses> =
        api.get<OccurrenceStatusesResponse>(
            url = ApiEndpoints.Calendar.OCCURRENCE_STATUSES,
            module = ModuleData.WITH_PROJECT_USER_ID,
            query = mapOf("master_event_id" to masterEventId),
        ).map { it.data?.toDomain() ?: OccurrenceStatuses() }

    /**
     * The timezone list for the event form.
     *
     * Server-provided rather than built from `ZoneId.getAvailableZoneIds()` so the app
     * offers exactly the zones the backend accepts, labelled the way the web client
     * labels them.
     */
    suspend fun timezones(): ApiResult<List<Timezone>> = api.get<TimezoneListResponse>(
        url = ApiEndpoints.Calendar.TIMEZONES,
        module = ModuleData.WITH_PROJECT_USER_ID,
    ).map { response -> response.data.map { it.toDomain() } }

    /** Creates an event from a payload built by [CalendarFormPayload]. */
    suspend fun createEvent(payload: JsonObject): ApiResult<CalendarEvent?> =
        api.post<JsonObject, EventResponse>(
            url = ApiEndpoints.Calendar.CREATE_EVENT,
            body = payload,
            module = ModuleData.WITH_PROJECT_USER_ID,
        ).map { it.data?.toDomain() }

    /**
     * Saves an edit.
     *
     * The payload must already carry `edit_type` and, for a scoped edit, the occurrence
     * fields — see [CalendarFormPayload.forEdit]. The server rejects an edit with no
     * `edit_type` outright.
     */
    suspend fun editEvent(eventId: String, payload: JsonObject): ApiResult<CalendarEvent?> =
        api.put<JsonObject, EventResponse>(
            url = "${ApiEndpoints.Calendar.EDIT_EVENT}$eventId",
            body = payload,
            module = ModuleData.WITH_PROJECT_USER_ID,
        ).map { it.data?.toDomain() }

    /**
     * Deletes an event, or one occurrence of a series.
     *
     * The scope goes out as **`delete_type`**, not `scope` — the invitation endpoints use
     * `scope`, this one does not, and the wrong key deletes the whole series.
     *
     * @param occurrenceDate epoch millis of the occurrence, sent as `yyyy-MM-dd`. The
     *   server matches it against a date-keyed exception list; an epoch fails to match and
     *   the delete is silently ignored — a 200 that changes nothing.
     */
    suspend fun delete(
        eventId: String,
        scope: EditScope = EditScope.SINGLE,
        occurrenceDate: Long? = null,
    ): ApiResult<Unit> = api.delete<EventResponse>(
        url = "${ApiEndpoints.Calendar.DELETE_EVENT}$eventId",
        module = ModuleData.WITH_PROJECT_USER_ID,
        query = buildMap {
            put("delete_type", scope.apiValue)
            occurrenceDate?.let { put("occurrence_date", it.toApiDate()) }
        },
    ).map { }

    /**
     * Moves one occurrence of a series to another date, leaving the rest in place.
     */
    suspend fun moveOccurrence(
        eventId: String,
        sourceDate: Long,
        targetDate: Long,
    ): ApiResult<CalendarEvent?> = api.put<JsonObject, EventResponse>(
        url = "${ApiEndpoints.Calendar.MOVE_OCCURRENCE}$eventId",
        body = buildJsonObject {
            put("source_date", sourceDate)
            put("target_date", targetDate)
        },
        module = ModuleData.WITH_PROJECT_USER_ID,
    ).map { it.data?.toDomain() }

    private inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
        is ApiResult.Success -> ApiResult.Success(transform(data))
        is ApiResult.Failure -> {
            ZillitLog.w(TAG, "Calendar request failed: ${error.message}")
            ApiResult.Failure(error)
        }
    }

    private fun ApiResult<PagedResponse<EventDto>>.toEventPage(): ApiResult<Page<CalendarEvent>> =
        map { response ->
            Page(
                items = response.data?.items.orEmpty().map { it.toDomain() },
                cursor = response.data?.cursor,
                hasMore = response.data?.hasMore ?: false,
            )
        }

    /**
     * The query every paged list shares.
     *
     * Blank search and null date are omitted rather than sent empty — the backend treats
     * `search=` as a filter for the empty string on some routes and returns nothing.
     */
    private fun pageQuery(
        cursor: String?,
        limit: Int,
        search: String?,
        date: Long?,
    ): Map<String, String> = buildMap {
        put("limit", limit.toString())
        cursor?.let { put("cursor", it) }
        search?.takeIf { it.isNotBlank() }?.let { put("search", it) }
        date?.let { put("date", it.toString()) }
    }

    private companion object {
        const val TAG = "CalendarRepository"

        /** What v2 asks for on every list. */
        const val PAGE_SIZE = 20

        /** How far ahead to expand a series that never ends. */
        const val OPEN_ENDED_WINDOW_MS = 365L * 24 * 60 * 60 * 1000

        const val SCOPE_ACTIVE = "active"
    }
}
