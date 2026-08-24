package com.zillit.zillitapp.core.calendar.sync

/**
 * What the mirror can be asked to do.
 *
 * Each variant carries identifiers only. The worker re-reads the event when it runs, so a
 * job that waited in the queue applies the event as it is now rather than as it was when
 * the job was created — which matters, because the queue exists precisely for the times
 * work cannot run immediately.
 *
 * Serialised as `kind|arg|arg` into one column; see [serialize] and [parse].
 */
sealed interface SyncOperation {

    /** Create or update the row for an event, or for a recurring master. */
    data class WriteEvent(val eventId: String) : SyncOperation

    /** Remove the row. Deleting a master takes its per-date overrides with it. */
    data class DeleteEvent(val eventId: String) : SyncOperation

    /** Create or update the override row for one date of a series. */
    data class WriteOccurrence(val masterEventId: String, val occurrenceDate: String) : SyncOperation

    /** Mark one date of a series as cancelled in the device calendar. */
    data class CancelOccurrence(val masterEventId: String, val occurrenceDate: String) : SyncOperation

    /**
     * Mirror everything currently live. Run once when the user switches sync on — before
     * that there is nothing in the device calendar to update.
     */
    data class Backfill(val userId: String) : SyncOperation

    /** Remove every row this app wrote for a user: sync switched off, or signed out. */
    data class PurgeAll(val userId: String) : SyncOperation

    /**
     * Purge and then backfill, as one job.
     *
     * One operation rather than two queued jobs so the order cannot be interleaved with
     * anything else — a purge that ran after its own backfill would leave an empty mirror.
     */
    data class ResetAndResync(val userId: String) : SyncOperation

    fun serialize(): String = when (this) {
        is WriteEvent -> "$WRITE_EVENT$SEPARATOR$eventId"
        is DeleteEvent -> "$DELETE_EVENT$SEPARATOR$eventId"
        is WriteOccurrence -> "$WRITE_OCCURRENCE$SEPARATOR$masterEventId$SEPARATOR$occurrenceDate"
        is CancelOccurrence -> "$CANCEL_OCCURRENCE$SEPARATOR$masterEventId$SEPARATOR$occurrenceDate"
        is Backfill -> "$BACKFILL$SEPARATOR$userId"
        is PurgeAll -> "$PURGE_ALL$SEPARATOR$userId"
        is ResetAndResync -> "$RESET$SEPARATOR$userId"
    }

    companion object {
        private const val SEPARATOR = "|"

        private const val WRITE_EVENT = "write"
        private const val DELETE_EVENT = "delete"
        private const val WRITE_OCCURRENCE = "write_occ"
        private const val CANCEL_OCCURRENCE = "cancel_occ"
        private const val BACKFILL = "backfill"
        private const val PURGE_ALL = "purge"
        private const val RESET = "reset"

        /** Null for anything unrecognised — a row from an older build is skipped, not fatal. */
        fun parse(value: String): SyncOperation? {
            val parts = value.split(SEPARATOR)
            val kind = parts.firstOrNull() ?: return null

            fun arg(index: Int): String? = parts.getOrNull(index)?.takeIf { it.isNotBlank() }

            return when (kind) {
                WRITE_EVENT -> arg(1)?.let { WriteEvent(it) }
                DELETE_EVENT -> arg(1)?.let { DeleteEvent(it) }
                WRITE_OCCURRENCE -> arg(1)?.let { id -> arg(2)?.let { WriteOccurrence(id, it) } }
                CANCEL_OCCURRENCE -> arg(1)?.let { id -> arg(2)?.let { CancelOccurrence(id, it) } }
                BACKFILL -> arg(1)?.let { Backfill(it) }
                PURGE_ALL -> arg(1)?.let { PurgeAll(it) }
                RESET -> arg(1)?.let { ResetAndResync(it) }
                else -> null
            }
        }
    }
}

/**
 * How long to wait before retrying, and when to stop.
 *
 * A minute, doubling, capped at an hour. The failures this covers are things like the
 * provider being briefly unavailable — worth retrying, not worth hammering.
 */
object BackoffPolicy {

    /** After this many consecutive failures a job stops being retried automatically. */
    const val MAX_ATTEMPTS = 6

    private const val BASE_MS = 60_000L
    private const val MAX_DELAY_MS = 60L * 60 * 1000

    fun nextDelayMs(attempts: Int): Long {
        require(attempts >= 1) { "attempts must be >= 1, got $attempts" }
        // Shifting past 63 would overflow; the cap makes that unreachable in practice, but
        // the coerce keeps it true regardless of MAX_ATTEMPTS.
        val shifted = if (attempts > SAFE_SHIFT_LIMIT) MAX_DELAY_MS else BASE_MS shl (attempts - 1)
        return minOf(MAX_DELAY_MS, shifted)
    }

    fun shouldGiveUp(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS

    private const val SAFE_SHIFT_LIMIT = 32
}
