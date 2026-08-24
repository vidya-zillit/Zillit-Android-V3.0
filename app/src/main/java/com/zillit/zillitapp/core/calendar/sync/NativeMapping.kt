package com.zillit.zillitapp.core.calendar.sync

import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.calendar.model.RecurrenceFrequency
import com.zillit.zillitapp.core.calendar.model.RecurrenceRule
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A writable calendar on the device that can receive mirrored events.
 *
 * Resolved once when sync is switched on and remembered, so the app keeps writing to the
 * same calendar even as accounts come and go on the device.
 */
data class CalendarTarget(
    val id: Long,
    val accountName: String,
    val accountType: String,
    val displayName: String,
)

/**
 * The marker that lets a native row be recognised as ours later.
 *
 * Written to `CUSTOM_APP_URI` alongside `CUSTOM_APP_PACKAGE`. Without it, a mapping row
 * lost to a reinstall would leave orphans in the user's calendar with no way to find them
 * again — so identity has to live on the row itself, not only in our database.
 */
object NativeEventUrl {

    private const val PREFIX = "zillit://event/"

    /**
     * @param occurrenceDate pins the URI to one occurrence of a series, for the rows that
     *   override a single date.
     */
    fun of(appEventId: String, userId: String, occurrenceDate: String? = null): String {
        val base = "$PREFIX$appEventId?u=$userId"
        return if (occurrenceDate.isNullOrBlank()) base else "$base&occ=$occurrenceDate"
    }

    /** Matches any row this app wrote for [userId]. Used by the purge and orphan sweep. */
    fun userMarker(userId: String): String = "u=$userId"
}

/**
 * A fingerprint of everything that gets written to the device calendar.
 *
 * The worker skips a write when the fingerprint has not moved, which is what keeps a
 * refresh from re-writing hundreds of unchanged rows.
 *
 * The field list must match what [NativeEventMapper] actually writes. Hash more than is
 * written and the app issues pointless writes; hash less and it skips writes it needed to
 * make, leaving stale rows in someone's calendar.
 */
object EventHash {

    /**
     * Bump when the *mapping* changes, not when a field is added.
     *
     * Every stored hash then stops matching, and one sync cycle re-applies the new mapping
     * to rows written by the old one. v2 needed this when it fixed all-day events landing
     * a day early.
     */
    private const val HASH_VERSION = "v1"

    fun of(event: CalendarEvent): String = md5(
        listOf(
            "v=$HASH_VERSION",
            "title=${event.title}",
            "description=${event.description}",
            "start=${event.startDatetime}",
            "end=${event.endDatetime}",
            "tz=${event.timezone}",
            "fullDay=${event.isFullDay}",
            "location=${event.locationDescription}",
            "callType=${event.callType.apiValue}",
            "freq=${event.recurrence.frequency.value}",
            "days=${event.recurrence.byDay.sorted().joinToString(",")}",
            "recStart=${event.recurrence.from ?: ""}",
            "recEnd=${event.recurrence.until ?: ""}",
            "cancelled=${event.isCancelled}",
        ).joinToString("|"),
    )

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * Turns a recurrence rule into an RFC 5545 RRULE body.
 *
 * Two conventions have to be bridged: the backend numbers weekdays `0 = Sunday`, RFC 5545
 * names them, and a `CUSTOM` frequency means "weekly on these days" rather than anything
 * custom at all.
 */
object RRuleMapper {

    private val BYDAY = arrayOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")

    private val UNTIL_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** The body only — `FREQ=WEEKLY;BYDAY=MO,WE`. Null when the event does not repeat. */
    fun toRRuleBody(rule: RecurrenceRule): String? {
        val frequency = when (rule.frequency) {
            RecurrenceFrequency.NONE -> return null
            RecurrenceFrequency.DAILY -> "DAILY"
            RecurrenceFrequency.WEEKLY, RecurrenceFrequency.CUSTOM -> "WEEKLY"
            RecurrenceFrequency.MONTHLY -> "MONTHLY"
            RecurrenceFrequency.YEARLY -> "YEARLY"
        }

        val parts = mutableListOf("FREQ=$frequency")

        if (frequency == "WEEKLY") {
            val days = rule.byDay
                .filter { it in BYDAY.indices }
                .distinct()
                .sorted()
                .joinToString(",") { BYDAY[it] }
            if (days.isNotEmpty()) parts += "BYDAY=$days"
        }

        rule.until?.let { end ->
            // The stored value is the start of the last day; UNTIL is inclusive, so it has
            // to reach the end of that day or the final occurrence is dropped.
            val endOfDay = end + MILLIS_PER_DAY - MILLIS_PER_SECOND
            parts += "UNTIL=${UNTIL_FORMAT.format(Instant.ofEpochMilli(endOfDay))}"
        }

        return parts.joinToString(";")
    }

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    private const val MILLIS_PER_SECOND = 1000L
}
