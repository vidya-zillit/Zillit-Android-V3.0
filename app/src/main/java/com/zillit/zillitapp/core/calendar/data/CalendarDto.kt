package com.zillit.zillitapp.core.calendar.data

import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.calendar.model.CallType
import com.zillit.zillitapp.core.calendar.model.DeclinedOccurrence
import com.zillit.zillitapp.core.calendar.model.Decliner
import com.zillit.zillitapp.core.calendar.model.EventType
import com.zillit.zillitapp.core.calendar.model.Invitation
import com.zillit.zillitapp.core.calendar.model.InvitationStatus
import com.zillit.zillitapp.core.calendar.model.RecurrenceFrequency
import com.zillit.zillitapp.core.calendar.model.RecurrenceRule
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The calendar wire format.
 *
 * Every field carries a default. The backend trims its responses depending on the endpoint
 * — accept/reject answer with `{event_id}` alone — and a required field there turns a
 * successful 200 into a decode failure. v2 hit exactly that and reported successful accepts
 * as errors.
 */
@Serializable
data class EventListResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: List<EventDto> = emptyList(),
)

@Serializable
data class EventResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: EventDto? = null,
)

@Serializable
data class RecurrenceRuleDto(
    @SerialName("frequency") val frequency: Int = 0,
    /** Weekdays for a weekly rule, 0 = Sunday. */
    @SerialName("selectedDays") val selectedDays: List<Int> = emptyList(),
    @SerialName("recurrence_start") val recurrenceStart: String? = null,
    @SerialName("recurrence_end") val recurrenceEnd: String? = null,
)

/**
 * Constants these DTOs compare against.
 *
 * Top-level rather than in a companion, because kotlinx-serialization puts the generated
 * `serializer()` into a `@Serializable` class's companion — and declaring that companion
 * private makes it inaccessible to the reified `api.get<...>()` call sites, which fails at
 * **runtime** with IllegalAccessError rather than at compile time.
 */
private const val TYPE_PROJECT_USER = "project_user"

/** The backend's "no colour chosen", not a real choice. */
private const val UNSET_COLOR = "#FFFFFF"

/**
 * Reads `invitees` whether the server sends objects or bare user-id strings.
 *
 * The list endpoints send `[{user_id, status, …}]`; create and edit send
 * `["6a67…", …]`. A string entry becomes an invitation carrying only the id — which is
 * all the server said — so callers get a usable list either way instead of a decode
 * failure on whichever shape they did not expect.
 */
object InviteesSerializer : KSerializer<List<InvitationDto>?> {
    override val descriptor: SerialDescriptor =
        ListSerializer(InvitationDto.serializer()).descriptor

    override fun deserialize(decoder: Decoder): List<InvitationDto> {
        val json = decoder as? JsonDecoder ?: return emptyList()
        val element = json.decodeJsonElement()
        val array = element as? JsonArray ?: return emptyList()

        return array.mapNotNull { entry ->
            when {
                entry is JsonObject ->
                    runCatching { json.json.decodeFromJsonElement(InvitationDto.serializer(), entry) }
                        .getOrNull()

                entry is JsonPrimitive && entry.isString ->
                    InvitationDto(userId = entry.content)

                else -> null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<InvitationDto>?) {
        encoder.encodeSerializableValue(
            ListSerializer(InvitationDto.serializer()),
            value.orEmpty(),
        )
    }
}

/**
 * A recurrence boundary as epoch millis, or null when absent or unparseable.
 *
 * The server writes these as `yyyy-MM-dd` and reads them back in **UTC**; parsing in the
 * device zone would shift a series' last day by one either side of the meridian. A legacy
 * numeric string is accepted too, since older rows carry epochs here.
 */
private fun String?.toEpochOrNull(): Long? {
    if (this.isNullOrBlank()) return null
    return runCatching {
        java.time.LocalDate.parse(this)
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }.getOrNull() ?: toLongOrNull()
}

@Serializable
data class LocationDto(
    @SerialName("lat") val lat: Double? = null,
    @SerialName("long") val long: Double? = null,
)

@Serializable
data class InvitationDto(
    @SerialName("_id") val id: String = "",
    @SerialName("event_id") val eventId: String = "",
    @SerialName("user_id") val userId: String? = null,
    @SerialName("project_id") val projectId: String = "",
    @SerialName("status") val status: String = "pending",
    @SerialName("rejection_reason") val rejectionReason: String = "",
    @SerialName("invited_by") val invitedBy: String = "",
    @SerialName("type") val type: String = TYPE_PROJECT_USER,
    @SerialName("email") val email: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    /** Display name echoed by the server, so a name needs no directory lookup. */
    @SerialName("_name") val name: String? = null,
    @SerialName("avatar") val avatar: String? = null,
    /**
     * Snake case here, but `statusOverrides` on the occurrence-statuses payload — the two
     * endpoints genuinely differ, so neither spelling can be dropped.
     */
    @SerialName("status_overrides") val statusOverrides: List<StatusOverrideDto> = emptyList(),
) {
    fun toDomain(): Invitation = Invitation(
        userId = userId.orEmpty(),
        name = name.orEmpty(),
        avatarUrl = avatar,
        status = InvitationStatus.fromApi(status),
        reason = rejectionReason.takeIf { it.isNotBlank() },
        isExternal = type != TYPE_PROJECT_USER,
        email = email,
        statusOverrides = statusOverrides.map { it.toDomain() },
    )

}

/**
 * Someone who declined, as the declined-created endpoint reports them.
 *
 * Two generations of field names are live at once: `reason` and `occurrence_dates` are the
 * older shape, `rejection_reason` and `rejected_from_dates` the newer flat one. Both are
 * read, because which arrives depends on the endpoint.
 */
@Serializable
data class DeclinerDto(
    @SerialName("user_id") val userId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("avatar") val avatar: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("occurrence_dates") val occurrenceDates: List<DeclinedDateDto> = emptyList(),
    @SerialName("rejected_from_dates") val rejectedFromDates: List<String> = emptyList(),
) {
    fun toDomain(): Decliner = Decliner(
        userId = userId,
        name = name,
        avatarUrl = avatar,
        reason = rejectionReason?.takeIf { it.isNotBlank() } ?: reason?.takeIf { it.isNotBlank() },
        // The flat list wins when present; it is what the current endpoint sends, and it
        // carries dates without per-date reasons.
        occurrences = if (rejectedFromDates.isNotEmpty()) {
            rejectedFromDates.map { DeclinedOccurrence(it) }
        } else {
            occurrenceDates.map { DeclinedOccurrence(it.date, it.reason) }
        },
    )
}

@Serializable
data class DeclinedDateDto(
    @SerialName("date") val date: String = "",
    @SerialName("reason") val reason: String? = null,
)

@Serializable
data class EventDto(
    @SerialName("_id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("start_datetime") val startDatetime: Long = 0,
    @SerialName("end_datetime") val endDatetime: Long = 0,
    @SerialName("timezone") val timezone: String = "",
    @SerialName("type") val type: Int = 0,
    @SerialName("full_day") val fullDay: Boolean = false,
    @SerialName("recurrence_rule") val recurrenceRule: RecurrenceRuleDto = RecurrenceRuleDto(),
    @SerialName("location") val location: LocationDto? = null,
    @SerialName("location_description") val locationDescription: String = "",
    @SerialName("call_type") val callType: String = "none",
    @SerialName("cnc_group_id") val cncGroupId: String? = null,
    @SerialName("color") val color: String = CalendarEvent.DEFAULT_COLOR,
    @SerialName("notify") val notify: Int = 0,
    @SerialName("creator_id") val creatorId: String = "",
    @SerialName("project_id") val projectId: String = "",
    @SerialName("parent_event_id") val parentEventId: String? = null,
    @SerialName("master_event_id") val masterEventId: String? = null,
    /**
     * The invitee list, under either of the two names the backend uses.
     *
     * Event **detail** answers with `invitations`; the events **list** answers with
     * `invitees` and carries `invitee_count` alongside. Reading only one of them leaves
     * the attendee row empty on whichever endpoint uses the other.
     */
    @SerialName("invitations") val invitations: List<InvitationDto>? = null,
    /**
     * Read through a tolerant serializer because this field has **two shapes**: the list
     * endpoints answer with objects, while create and edit answer with bare user-id
     * strings. Declaring either one alone makes the other a decode failure — and on
     * create that means reporting a failure for an event the server actually saved.
     */
    @SerialName("invitees")
    @Serializable(with = InviteesSerializer::class)
    val invitees: List<InvitationDto>? = null,
    @SerialName("invitee_count") val inviteeCount: Int? = null,
    /**
     * Other master ids belonging to the same series.
     *
     * A series that is edited "this and following" splits into a chain of masters, each
     * owning part of the timeline. Listing a series' occurrences means matching any id in
     * the chain — matching only this master silently drops everything after a split.
     */
    @SerialName("_sibling_ids") val siblingIds: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    /** Set when the event was deleted, or when one occurrence of a series was cancelled. */
    @SerialName("deleted_at") val deletedAt: Long? = null,
    /**
     * The legacy spelling of the same thing, as an epoch rather than a flag.
     *
     * The `/events` endpoint still uses this for per-occurrence cancellations. Reading
     * only `deleted_at` means cancelling a single occurrence of a series changes nothing
     * on screen while cancelling the whole event works — v2 shipped exactly that bug.
     */
    @SerialName("deleted") val deleted: Long? = null,

    // ── Server-computed flags ─────────────────────────────────────────────
    /** An expanded occurrence with no row of its own — it cannot be addressed by id. */
    @SerialName("_isVirtual") val isVirtual: Boolean = false,
    /**
     * Cancellation, under all four names the backend has used for it.
     *
     * Production sends `_isCancelled`; the rest are aliases that a server change could
     * switch to. They cost nothing to accept and their absence would silently drop the
     * strikethrough on a cancelled call, which reads as the event still being on.
     */
    @SerialName("_isCancelled") val isCancelled: Boolean = false,
    @SerialName("is_cancelled") val isCancelledSnake: Boolean = false,
    @SerialName("_isDeleted") val isDeletedFlag: Boolean = false,
    @SerialName("is_deleted") val isDeletedSnake: Boolean = false,
    @SerialName("_isDeclined") val isDeclined: Boolean = false,
    /** `yyyy-MM-dd`. Identifies which occurrence of a series this row is. */
    @SerialName("occurrence_date") val occurrenceDate: String? = null,
    /**
     * Created by the box-schedule module rather than the calendar.
     *
     * The owning module keeps edit and delete, so the calendar must not offer them.
     */
    @SerialName("box_schedule_event") val boxScheduleEvent: Boolean = false,
    /** The creator left themselves off their own event's invitee list. */
    @SerialName("createUser_exclude") val createUserExclude: Boolean = false,
    /** My own status, on the endpoints that state it rather than embedding invitations. */
    @SerialName("invitation_status") val invitationStatus: String? = null,
    @SerialName("decliners") val decliners: List<DeclinerDto> = emptyList(),
    @SerialName("distinct_decliner_count") val distinctDeclinerCount: Int? = null,
) {
    fun toDomain(): CalendarEvent = CalendarEvent(
        id = id,
        title = title,
        description = description,
        // `start_datetime` is already this occurrence's own start — the backend computes
        // it per row when it expands a series. Overriding it with the occurrence date
        // would move every recurring event to midnight, since that date carries no time.
        startDatetime = startDatetime,
        endDatetime = endDatetime,
        timezone = timezone,
        type = EventType.fromValue(type),
        isFullDay = fullDay,
        recurrence = RecurrenceRule(
            frequency = RecurrenceFrequency.fromValue(recurrenceRule.frequency),
            byDay = recurrenceRule.selectedDays,
            from = recurrenceRule.recurrenceStart.toEpochOrNull(),
            until = recurrenceRule.recurrenceEnd.toEpochOrNull(),
        ),
        locationDescription = locationDescription,
        locationLat = location?.lat,
        locationLong = location?.long,
        callType = CallType.fromApi(callType),
        cncGroupId = cncGroupId,
        // White is the backend's "unset", not a choice — an all-white event is invisible
        // against the card it sits on.
        color = color.takeUnless { it.isBlank() || it.equals(UNSET_COLOR, ignoreCase = true) }
            ?: CalendarEvent.DEFAULT_COLOR,
        notifyMinutes = notify,
        creatorId = creatorId,
        projectId = projectId,
        invitations = (invitations ?: invitees).orEmpty().map { it.toDomain() },
        inviteeCount = inviteeCount,
        masterEventId = masterEventId,
        parentEventId = parentEventId,
        occurrenceDate = occurrenceDate,
        siblingIds = siblingIds,
        isVirtual = isVirtual,
        // Any of the four spellings means the same thing downstream.
        isCancelledOccurrence = isCancelled || isCancelledSnake || isDeletedFlag ||
            isDeletedSnake,
        isFromBoxSchedule = boxScheduleEvent,
        createUserExclude = createUserExclude,
        invitationStatus = invitationStatus?.let { InvitationStatus.fromApi(it) },
        decliners = decliners.map { it.toDomain() },
        distinctDeclinerCount = distinctDeclinerCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
        // `deleted` is an epoch, so 0 means "not deleted" rather than "deleted at the
        // epoch" — without the guard every event would read as cancelled.
        deletedAt = deletedAt ?: deleted?.takeIf { it > 0 },
        isDeclined = isDeclined,
    )

}
