package com.zillit.zillitapp.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Every date and time the app displays or sends, formatted in one place.
 *
 * Before this, six screens each declared their own `SimpleDateFormat`, which meant six
 * patterns to keep in step and six chances to drift — and each of those was a shared
 * top-level `val`, which [java.text.SimpleDateFormat] does not survive: it is mutable and
 * **not thread-safe**, so a mapper formatting on a background thread while a composable
 * formats on the main one can produce garbled output or throw.
 *
 * These use [DateTimeFormatter], which is immutable and safe to share, and are cached per
 * pattern **and locale** so switching the app language rebuilds them instead of serving
 * dates in the previous language.
 *
 * Epoch 0 formats as an empty string throughout. The backend uses 0 for "never" on
 * optional timestamps, and "01 Jan 1970" is worse than showing nothing.
 */
object DateTime {

    /** `2026-08-22` — what APIs want. Locale-fixed; never show this to a user. */
    const val PATTERN_API_DATE = "yyyy-MM-dd"

    /** `22 Aug 2026` */
    const val PATTERN_DATE = "dd MMM yyyy"

    /** `22 Aug 2026, 01:32 PM` */
    const val PATTERN_DATE_TIME = "dd MMM yyyy, hh:mm a"

    /** `22 Aug, 01:32 PM` — for lists, where the year is noise. */
    const val PATTERN_SHORT_DATE_TIME = "dd MMM, hh:mm a"

    /** `22 Aug at 01:32 PM` */
    const val PATTERN_DAY_AT_TIME = "dd MMM 'at' hh:mm a"

    /** `13:32` — chat bubbles. */
    const val PATTERN_CLOCK = "HH:mm"

    /** `22 Aug 2026` without the leading zero — chat day separators. */
    const val PATTERN_DAY_SEPARATOR = "d MMM yyyy"

    /** `1:32 PM` — calendar events, which read in 12-hour time throughout. */
    const val PATTERN_TIME_12 = "h:mm a"

    /** `1 PM` — the hour column of the day timeline. */
    const val PATTERN_HOUR_12 = "h a"

    /** `Thu, Apr 23` — an event's date where the year is obvious from context. */
    const val PATTERN_WEEKDAY_DATE = "EEE, MMM d"

    /** `Thu, Apr 23, 2026` */
    const val PATTERN_WEEKDAY_DATE_YEAR = "EEE, MMM d, yyyy"

    /** `April 2026` — the calendar header. */
    const val PATTERN_MONTH_YEAR = "MMMM yyyy"

    private val cache = ConcurrentHashMap<Triple<String, Locale, ZoneId>, DateTimeFormatter>()

    /**
     * A cached formatter for [pattern] in [locale], rendering in [zone].
     *
     * Public so a screen with a genuinely one-off pattern still gets the caching and the
     * thread-safety; add a `PATTERN_` constant here instead as soon as a second caller
     * wants the same shape.
     *
     * [zone] exists for the calendar: an event belongs to the timezone it was scheduled
     * in, so a 9am call set in Mumbai must read as 9am to a viewer in London. Everything
     * else — chat stamps, file dates — is about when something happened to *this* reader,
     * and takes the device zone by default.
     */
    fun formatter(
        pattern: String,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): DateTimeFormatter = cache.getOrPut(Triple(pattern, locale, zone)) {
        DateTimeFormatter.ofPattern(pattern, locale).withZone(zone)
    }

    /**
     * [id] as a zone, falling back to the device's when it is null or unrecognised.
     *
     * Never throws. A zone id arrives from the server, and one bad value should cost a
     * slightly wrong time on one event, not a crash while rendering a month.
     */
    fun zoneOf(id: String?): ZoneId =
        id?.takeIf { it.isNotBlank() }
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
}

/**
 * Formats epoch millis with [pattern]; empty when the timestamp is 0.
 *
 * The base every helper below is built on — prefer a named one so the pattern stays
 * declared in [DateTime] rather than spelled out at the call site.
 */
fun Long.format(pattern: String, locale: Locale = Locale.getDefault()): String =
    if (this == 0L) "" else DateTime.formatter(pattern, locale).format(Instant.ofEpochMilli(this))

/**
 * Formats epoch millis with [pattern], rendered in [zone] rather than the device's.
 *
 * For anything that carries its own timezone — a calendar event, a call-time on a call
 * sheet — where showing it in the reader's zone would silently move the appointment.
 */
fun Long.formatIn(
    zone: java.time.ZoneId,
    pattern: String,
    locale: Locale = Locale.getDefault(),
): String = if (this == 0L) {
    ""
} else {
    DateTime.formatter(pattern, locale, zone).format(Instant.ofEpochMilli(this))
}

/** `1:32 PM` in the event's own zone. */
fun Long.toEventTime(zone: java.time.ZoneId): String = formatIn(zone, DateTime.PATTERN_TIME_12)

/**
 * `Thu, Apr 23 1:32 PM - 2:30 PM`, or the two-sided form when it spans midnight.
 *
 * The start date is repeated on the end only when the event actually crosses a day, so the
 * common case stays short and a genuinely multi-day event is unmistakable.
 */
fun formatEventRange(startMs: Long, endMs: Long, zone: java.time.ZoneId): String {
    val date = DateTime.formatter(DateTime.PATTERN_WEEKDAY_DATE, zone = zone)
    val time = DateTime.formatter(DateTime.PATTERN_TIME_12, zone = zone)

    val start = Instant.ofEpochMilli(startMs)
    val end = Instant.ofEpochMilli(endMs)
    val sameDay = start.atZone(zone).toLocalDate() == end.atZone(zone).toLocalDate()

    return if (sameDay) {
        "${date.format(start)} ${time.format(start)} - ${time.format(end)}"
    } else {
        "${date.format(start)}, ${time.format(start)} - ${date.format(end)}, ${time.format(end)}"
    }
}

/** `2026-08-22`, for request payloads. Locale-fixed so a Hindi phone still sends digits. */
fun Long.toApiDate(): String = format(DateTime.PATTERN_API_DATE, Locale.US)

/** `22 Aug 2026` */
fun Long.toDateLabel(): String = format(DateTime.PATTERN_DATE)

/** `22 Aug 2026, 01:32 PM` */
fun Long.toDateTimeLabel(): String = format(DateTime.PATTERN_DATE_TIME)

/** `22 Aug, 01:32 PM` */
fun Long.toShortDateTimeLabel(): String = format(DateTime.PATTERN_SHORT_DATE_TIME)

/** `22 Aug at 01:32 PM` */
fun Long.toDayAtTimeLabel(): String = format(DateTime.PATTERN_DAY_AT_TIME)

/** `13:32` */
fun Long.toClockTime(): String = format(DateTime.PATTERN_CLOCK)

/** `1:32 PM` — the clock as it is spoken, for anything read alongside the rest of a day. */
fun Long.toTimeLabel(): String = format(DateTime.PATTERN_TIME_12)

/** `22 Aug 2026`, no leading zero. */
fun Long.toDaySeparatorLabel(): String = format(DateTime.PATTERN_DAY_SEPARATOR)

/**
 * `4:07` — elapsed time for voice notes and video.
 *
 * Hours are only shown once there are any, so a 4-second clip reads `0:04` rather than
 * `0:00:04`.
 */
fun Long.toDurationLabel(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Which day a timestamp falls on, relative to now.
 *
 * Deliberately not a String: "Today" and "Yesterday" are app copy and must come from
 * `strings.xml`, so this returns the *decision* and the UI layer supplies the words. Only
 * [RelativeDay.Other] carries text, because a formatted date is locale-resolved already.
 */
sealed interface RelativeDay {
    data object Today : RelativeDay
    data object Yesterday : RelativeDay

    /** Calendar surfaces show days ahead, so the future needs a word too. */
    data object Tomorrow : RelativeDay
    data class Other(val label: String) : RelativeDay

    /** Epoch 0 — no date at all. */
    data object None : RelativeDay
}

/**
 * Classifies [this] as today, yesterday, or a dated day.
 *
 * Compares calendar days in the device's zone rather than subtracting 24 hours: at 00:30
 * a message sent at 23:50 is *yesterday*, not "20 minutes ago today", and the arithmetic
 * version gets that wrong every night.
 */
fun Long.toRelativeDay(zone: ZoneId = ZoneId.systemDefault()): RelativeDay {
    if (this == 0L) return RelativeDay.None

    val day = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)

    return when (day) {
        today -> RelativeDay.Today
        today.minusDays(1) -> RelativeDay.Yesterday
        today.plusDays(1) -> RelativeDay.Tomorrow
        else -> RelativeDay.Other(toDaySeparatorLabel())
    }
}
