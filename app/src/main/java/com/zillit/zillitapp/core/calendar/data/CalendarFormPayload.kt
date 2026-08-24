package com.zillit.zillitapp.core.calendar.data

import com.zillit.zillitapp.core.calendar.model.CallType
import com.zillit.zillitapp.core.calendar.model.EditScope
import com.zillit.zillitapp.core.calendar.model.EventType
import com.zillit.zillitapp.core.common.toApiDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Everything the form collects, in one object so validation and payload agree on it. */
data class EventFormInput(
    val title: String = "",
    val description: String = "",
    val date: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.now(),
    val endTime: LocalTime = LocalTime.now().plusHours(1),
    val isFullDay: Boolean = false,
    val timezone: String = ZoneId.systemDefault().id,
    val type: EventType = EventType.MEMBERS,
    val recurrenceFrequency: Int = 0,
    val selectedDays: List<Int> = emptyList(),
    val recurrenceEnd: LocalDate? = null,
    val locationDescription: String = "",
    val locationLat: Double? = null,
    val locationLong: Double? = null,
    val callType: CallType = CallType.AUDIO,
    val color: String = CalendarFormPayload.DEFAULT_EVENT_COLOR,
    val notifyMinutes: Int = 0,
    val inviteeIds: List<String> = emptyList(),
    val externalEmails: List<String> = emptyList(),
    /** The creator is leaving themselves off their own event's invitee list. */
    val excludeCreator: Boolean = false,
)

/** Why a form cannot be submitted. Each maps to one message; the UI resolves the string. */
enum class EventFormError {
    TITLE_TOO_SHORT,
    START_IN_PAST,
    DURATION_TOO_SHORT,
    RECURRENCE_END_REQUIRED,
    RECURRENCE_END_BEFORE_START,
    INVITEES_REQUIRED,
    CALL_TYPE_REQUIRED,
    LOCATION_REQUIRED,
}

/**
 * Validation and payload building for the event form.
 *
 * Pure functions, deliberately: these are the rules most likely to be wrong in a subtle
 * way, and keeping them free of Android types means they can be reasoned about — and
 * tested — without a form on screen.
 */
object CalendarFormPayload {

    /** Half an hour, matching the calendar's grid resolution. */
    const val MIN_DURATION_MINUTES = 30

    const val DEFAULT_EVENT_COLOR = "#fc9404"

    /**
     * The palette the picker offers. Ordered as v2 orders it, with the brand colour first.
     *
     * Names are deliberately not translated — they are colour names on swatches, and v2
     * ships them as English labels.
     */
    val COLORS: List<Pair<String, String>> = listOf(
        "#fc9404" to "Amber Gold",
        "#C7784A" to "Terracotta",
        "#7A8B6E" to "Sage Green",
        "#C4697A" to "Dusty Rose",
        "#5B7FA5" to "Warm Blue",
        "#A67B5B" to "Clay",
        "#D4A853" to "Golden",
        "#5A6B4F" to "Deep Olive",
        "#8B6E8B" to "Muted Plum",
        "#6B8F9C" to "Teal Mist",
        "#D4956B" to "Peach",
        "#785A3C" to "Warm Brown",
    )

    /** Reminder offsets in minutes; 0 is "none". */
    val NOTIFY_OPTIONS: List<Int> = listOf(0, 5, 10, 15, 30, 60)

    /**
     * What stops this form from being submitted, or null when nothing does.
     *
     * @param isCreateMode the past-time guard applies only to a new event. An event that
     *   already happened must stay editable, or a typo in last week's call sheet can never
     *   be corrected.
     */
    fun validate(input: EventFormInput, isCreateMode: Boolean): EventFormError? {
        if (input.title.trim().length < MIN_TITLE_LENGTH) return EventFormError.TITLE_TOO_SHORT

        if (!input.isFullDay) {
            if (isCreateMode) {
                // "Today" and "now" are both read in the **event's** zone: scheduling a
                // 9am Mumbai call from London at 6am local is not scheduling in the past.
                val zone = runCatching { ZoneId.of(input.timezone) }
                    .getOrDefault(ZoneId.systemDefault())
                if (input.date == LocalDate.now(zone) &&
                    input.startTime.isBefore(LocalTime.now(zone))
                ) {
                    return EventFormError.START_IN_PAST
                }
            }

            if (input.durationMinutes() < MIN_DURATION_MINUTES) {
                return EventFormError.DURATION_TOO_SHORT
            }
        }

        if (input.recurrenceFrequency > 0) {
            val end = input.recurrenceEnd ?: return EventFormError.RECURRENCE_END_REQUIRED
            if (end.isBefore(input.date)) return EventFormError.RECURRENCE_END_BEFORE_START
        }

        // A personal event has no attendance flow at all, so none of the invitee rules
        // apply to it.
        if (input.type == EventType.MEMBERS) {
            if (input.inviteeIds.isEmpty() && input.externalEmails.isEmpty()) {
                return EventFormError.INVITEES_REQUIRED
            }
            if (input.callType == CallType.NONE) return EventFormError.CALL_TYPE_REQUIRED

            // Only this one combination. With plain "Meet in Person" the event *is* the
            // gathering and the venue is often arranged separately; with "Meet in Person &
            // Call" some people are dialling in and the rest need somewhere to go.
            if (input.callType == CallType.MEET_IN_PERSON_CALL &&
                input.locationDescription.isBlank()
            ) {
                return EventFormError.LOCATION_REQUIRED
            }
        }

        return null
    }

    /**
     * The minutes an event runs for, with an end at or before the start rolling to the
     * next day — an event that runs 22:00 to 01:00 is a three-hour overnight, not a
     * negative one.
     */
    fun EventFormInput.durationMinutes(): Int {
        val start = startTime.hour * MINUTES_PER_HOUR + startTime.minute
        var end = endTime.hour * MINUTES_PER_HOUR + endTime.minute
        if (end <= start) end += MINUTES_PER_DAY
        return end - start
    }

    /**
     * The date the event actually ends on.
     *
     * The same day, unless the end time is at or before the start — then it rolls to the
     * next, which is what makes an event that runs 22:00 to 01:00 a three-hour overnight
     * rather than a negative one. Shown read-only; there is nothing to choose.
     */
    fun EventFormInput.endDate(): LocalDate {
        if (isFullDay) return date
        val start = startTime.hour * MINUTES_PER_HOUR + startTime.minute
        val end = endTime.hour * MINUTES_PER_HOUR + endTime.minute
        return if (end <= start) date.plusDays(1) else date
    }

    /** How long a series runs by default once a frequency is picked. */
    fun defaultRecurrenceEnd(frequency: Int, date: LocalDate): LocalDate? = when (frequency) {
        FREQ_DAILY -> date.plusDays(1)
        FREQ_WEEKLY, FREQ_CUSTOM -> date.plusWeeks(1)
        FREQ_MONTHLY -> date.plusMonths(1)
        FREQ_YEARLY -> date.plusYears(1)
        else -> null
    }

    /**
     * The create payload.
     *
     * @param resolveName turns a user id into the display name the server echoes back as
     *   `_name`. Injected rather than looked up here so this stays free of the directory.
     */
    fun build(input: EventFormInput, resolveName: (String) -> String = { "" }): JsonObject {
        val zone = runCatching { ZoneId.of(input.timezone) }.getOrDefault(ZoneId.systemDefault())
        val isPersonal = input.type == EventType.PERSONAL

        val startMs = if (input.isFullDay) {
            input.date.atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            input.date.atTime(input.startTime).atZone(zone).toInstant().toEpochMilli()
        }

        val endMs = if (input.isFullDay) {
            // The last millisecond of the day, not the first of the next — an end on the
            // next day's midnight makes a one-day event span two.
            input.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        } else {
            val raw = input.date.atTime(input.endTime).atZone(zone).toInstant().toEpochMilli()
            if (raw <= startMs) {
                input.date.plusDays(1).atTime(input.endTime).atZone(zone).toInstant().toEpochMilli()
            } else {
                raw
            }
        }

        return buildJsonObject {
            put("title", input.title.trim())
            put("description", input.description)
            put("start_datetime", startMs)
            put("end_datetime", endMs)
            put("timezone", input.timezone)
            put("type", input.type.value)
            put("full_day", input.isFullDay)

            putJsonObject("recurrence_rule") {
                put("frequency", input.recurrenceFrequency)
                putJsonArray("selectedDays") { input.selectedDays.forEach { add(it) } }
                if (input.recurrenceFrequency > 0 && input.recurrenceEnd != null) {
                    // End of the chosen day in the **device** zone, not the event's: the
                    // user picked a date off a calendar, not a moment in another country.
                    val deviceZone = ZoneId.systemDefault()
                    put(
                        "recurrence_end",
                        input.recurrenceEnd.plusDays(1)
                            .atStartOfDay(deviceZone).toInstant().toEpochMilli() - 1,
                    )
                }
            }

            putJsonObject("location") {
                put("lat", input.locationLat)
                put("long", input.locationLong)
            }
            put("location_description", input.locationDescription)
            put("call_type", if (isPersonal) CallType.NONE.apiValue else input.callType.apiValue)
            put("color", input.color)
            put("notify", input.notifyMinutes)
            put("createUser_exclude", input.excludeCreator)

            // Invitees go out in three shapes at once. `invitees` is the current one; the
            // edit endpoint diffs against the legacy `invited_users` and `email` instead,
            // and sending only the modern shape means edited invitees save as a no-op
            // while every other field on the event updates. v2 found this the hard way.
            putJsonArray("invitees") {
                if (!isPersonal) {
                    input.inviteeIds.forEach { userId ->
                        add(
                            buildJsonObject {
                                put("user_id", userId)
                                put("type", TYPE_PROJECT_USER)
                                // Dropped when blank so the server's own fallback runs
                                // rather than it storing an empty name.
                                resolveName(userId).trim().takeIf { it.isNotBlank() }
                                    ?.let { put("_name", it) }
                            },
                        )
                    }
                    input.externalEmails.forEach { email ->
                        add(
                            buildJsonObject {
                                put("email", email)
                                put("type", TYPE_EXTERNAL)
                            },
                        )
                    }
                }
            }

            put(
                "invited_users",
                buildJsonArray {
                    if (!isPersonal) {
                        input.inviteeIds.forEach { add(buildJsonObject { put("user_id", it) }) }
                    }
                },
            )

            put(
                "email",
                buildJsonArray {
                    if (!isPersonal) {
                        input.externalEmails.forEach { mail ->
                            add(
                                buildJsonObject {
                                    // The server generates the real id; this only has to be
                                    // stable within one request. Derived from the address
                                    // rather than the clock so a retry does not churn.
                                    put("_id", mail.hashCode().toString())
                                    put("mail", mail)
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    /**
     * The same payload, prepared for an edit.
     *
     * `edit_type` goes on **every** edit — the server rejects a request without it. What
     * else is attached depends on the scope, and each omission matters:
     *
     *  - `single` and `thisAndFuture` must not carry `recurrence_rule`. They change one
     *    occurrence or truncate the series; sending the rule would rewrite the master's
     *    recurrence as a side effect of editing one date.
     *  - `occurrence_date` is `yyyy-MM-dd`. The server matches it against a date-keyed
     *    exception list, so an epoch misses and the edit silently applies to nothing.
     *  - `thisAndFuture` also needs `dayStartDate`, the legacy epoch boundary field.
     */
    fun forEdit(
        payload: JsonObject,
        scope: EditScope,
        occurrenceStartMs: Long?,
    ): JsonObject = buildJsonObject {
        payload.forEach { (key, value) ->
            val dropsRule = scope != EditScope.ALL && key == "recurrence_rule"
            if (!dropsRule) put(key, value)
        }

        put("edit_type", scope.apiValue)

        if (scope != EditScope.ALL && occurrenceStartMs != null) {
            put("occurrence_date", occurrenceStartMs.toApiDate())
            if (scope == EditScope.THIS_AND_FUTURE) {
                put("dayStartDate", occurrenceStartMs)
            }
        }
    }

    private const val MIN_TITLE_LENGTH = 3
    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * 60

    private const val TYPE_PROJECT_USER = "project_user"
    private const val TYPE_EXTERNAL = "external"

    const val FREQ_NONE = 0
    const val FREQ_DAILY = 1
    const val FREQ_WEEKLY = 2
    const val FREQ_MONTHLY = 3
    const val FREQ_YEARLY = 4
    const val FREQ_CUSTOM = 5
}
