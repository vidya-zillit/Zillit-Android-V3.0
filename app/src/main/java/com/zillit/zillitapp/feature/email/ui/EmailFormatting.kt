package com.zillit.zillitapp.feature.email.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * How a mail's timestamp is written, in the three places it appears.
 *
 * The patterns are v2's exactly — client-approved, and changing them would change every
 * date in the module — but the *implementation* is shared instead of copied. v2 has
 * `formatTime` in `EmailCardAdapter` and `formatShortDate` in `EmailTrailAdapter`, which are
 * the same function except that one shows `MMM d` for an older mail this year and the other
 * shows `EEE`. That difference is real and intended, so it is a parameter here rather than
 * two functions that will drift again.
 *
 * A formatter per call is deliberate: `SimpleDateFormat` is not thread-safe, and it has to
 * pick up a locale change without the process restarting.
 */
object EmailDates {

    /** The list row: today → `4:32 PM`, this year → `Mar 9`, older → `3/9/24`. */
    fun listRow(timestamp: Long): String = relative(timestamp, thisYearPattern = "MMM d")

    /** The trail header: today → `4:32 PM`, this year → `Tue`, older → `3/9/24`. */
    fun trailHeader(timestamp: Long): String = relative(timestamp, thisYearPattern = "EEE")

    /** The expanded message: `Tuesday, 9 March at 4:32 PM`. */
    fun full(timestamp: Long): String =
        if (timestamp <= 0) "" else format("EEEE, d MMMM 'at' h:mm a", timestamp)

    /** The quoted-reply attribution line: `Tue, Mar 9, 2024 at 4:32 PM`. */
    fun quoteAttribution(timestamp: Long): String =
        if (timestamp <= 0) "" else format("EEE, MMM d, yyyy 'at' h:mm a", timestamp)

    /** A rule run: `Mar 09, 2024 at 04:32 PM`. */
    fun executionStamp(timestamp: Long): String =
        if (timestamp <= 0) "" else format("MMM dd, yyyy 'at' hh:mm a", timestamp)

    private fun relative(timestamp: Long, thisYearPattern: String): String {
        if (timestamp <= 0) return ""

        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }

        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)

        val pattern = when {
            sameDay -> "h:mm a"
            sameYear -> thisYearPattern
            else -> "M/d/yy"
        }
        return format(pattern, timestamp)
    }

    private fun format(pattern: String, timestamp: Long): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}
