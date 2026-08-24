package com.zillit.zillitapp.core.calendar.sync

import android.content.ContentValues
import android.provider.CalendarContract
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Turns an event into the `ContentValues` the platform calendar wants.
 *
 * What is deliberately **not** written:
 *
 *  - **Colour.** Kept off so the mirror looks the same on every platform; iOS cannot match
 *    an arbitrary hex anyway.
 *  - **Reminders.** The app owns notifying you about your own events. Writing them too
 *    means every event alerts twice.
 *  - **Attendees.** Copying a production's crew list into a personal Google account is not
 *    something to do quietly.
 */
object NativeEventMapper {

    fun toContentValues(
        event: CalendarEvent,
        calendarId: Long,
        packageName: String,
        userId: String,
        projectName: String? = null,
    ): ContentValues = ContentValues().apply {
        // A series shares one master id across its occurrences. Keying on the per-row id
        // would write one native master per day.
        val masterId = event.effectiveMasterEventId

        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, event.title)
        put(CalendarContract.Events.DESCRIPTION, description(event, projectName))
        put(CalendarContract.Events.EVENT_LOCATION, event.locationDescription)

        // Both timezone columns are set, always. Some stock calendars render the time
        // twice — once per column — and append each column's label; an empty end timezone
        // shows as a literal "null" beside the second copy.
        val zone = event.timezone
            .takeIf { it.isNotBlank() && it != "null" }
            ?: TimeZone.getDefault().id
        val effectiveZone = if (event.isFullDay) UTC else zone
        put(CalendarContract.Events.EVENT_TIMEZONE, effectiveZone)
        put(CalendarContract.Events.EVENT_END_TIMEZONE, effectiveZone)
        put(CalendarContract.Events.ALL_DAY, if (event.isFullDay) 1 else 0)

        val rrule = RRuleMapper.toRRuleBody(event.recurrence)

        if (event.isFullDay) {
            // An all-day event must start at **UTC midnight of the local date** — not at
            // the local midnight re-expressed as an epoch. The server stores the latter,
            // and writing it raw makes the calendar read the day before for anyone east of
            // UTC: an event on the 12th shows on the 11th.
            val localDate = Instant.ofEpochMilli(event.startDatetime)
                .atZone(ZoneId.of(zone))
                .toLocalDate()
            val start = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val days = allDayCount(event, zone)

            put(CalendarContract.Events.DTSTART, start)
            if (rrule == null) {
                // DTEND is exclusive: a one-day event ends at the start of the next day.
                put(CalendarContract.Events.DTEND, start + days * MILLIS_PER_DAY)
            } else {
                // A recurring event takes a duration instead of an end, and an all-day one
                // must express it in days — seconds are accepted but render wrongly.
                put(CalendarContract.Events.DURATION, "P${days}D")
            }
        } else {
            put(CalendarContract.Events.DTSTART, event.startDatetime)
            if (rrule == null) {
                put(CalendarContract.Events.DTEND, event.endDatetime)
            } else {
                val seconds = ((event.endDatetime - event.startDatetime) / 1000)
                    .coerceAtLeast(MIN_DURATION_SECONDS)
                put(CalendarContract.Events.DURATION, "P${seconds}S")
            }
        }

        rrule?.let { put(CalendarContract.Events.RRULE, it) }

        // How a row is recognised as ours after a reinstall wipes the mapping store.
        put(CalendarContract.Events.CUSTOM_APP_PACKAGE, packageName)
        put(
            CalendarContract.Events.CUSTOM_APP_URI,
            NativeEventUrl.of(masterId, userId, event.occurrenceDate),
        )
    }

    /**
     * The description, with the project named above it.
     *
     * Someone looking at a mirrored event in their own calendar has no other way to tell
     * which production it came from.
     */
    private fun description(event: CalendarEvent, projectName: String?): String {
        val header = projectName?.takeIf { it.isNotBlank() }?.let { "Project: $it" }
        return listOfNotNull(header, event.description.takeIf { it.isNotBlank() })
            .joinToString("\n\n")
    }

    /** How many whole days an all-day event covers; at least one. */
    private fun allDayCount(event: CalendarEvent, zone: String): Long {
        val start = Instant.ofEpochMilli(event.startDatetime).atZone(ZoneId.of(zone)).toLocalDate()
        val end = Instant.ofEpochMilli(event.endDatetime).atZone(ZoneId.of(zone)).toLocalDate()
        return (java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1).coerceAtLeast(1)
    }

    private const val UTC = "UTC"
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    private const val MIN_DURATION_SECONDS = 60L
}
