package com.zillit.zillitapp.core.calendar.sync

import android.content.Context
import com.zillit.zillitapp.core.calendar.data.CalendarRepository
import com.zillit.zillitapp.core.calendar.model.CalendarEvent
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.session.SessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Why a job did not succeed, when it did not. */
class SyncFailure(message: String) : Exception(message)

/**
 * Performs the queued mirroring work against the device calendar.
 *
 * Every operation re-reads the event **by id** before writing. That is not defensive
 * padding: the events index is eventually consistent, and a job created by a socket
 * message runs slightly later, so reading the event directly is the only way to see the
 * change that caused the job in the first place.
 */
@Singleton
class CalendarSyncExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: CalendarProvider,
    private val repository: CalendarRepository,
    private val mappings: NativeMappingStore,
    private val preferences: CalendarSyncPreferences,
    private val session: SessionStore,
    private val projects: SyncProjectNames,
) {

    /**
     * Runs one job.
     *
     * @throws SyncFailure when the job should be retried. Returning normally means it is
     *   finished, including the cases where there was nothing to do.
     */
    suspend fun execute(job: SyncJob) {
        if (!preferences.isEnabled()) {
            ZillitLog.d(TAG, "sync disabled — dropping ${job.operation}")
            return
        }

        if (!provider.hasPermission()) {
            // Retryable: the user may still grant it. Failing rather than dropping keeps
            // the work for when they do.
            throw SyncFailure("calendar permission not granted")
        }

        when (val operation = job.operation) {
            is SyncOperation.WriteEvent -> write(operation.eventId, null, job.ownerUserId)

            is SyncOperation.WriteOccurrence ->
                write(operation.masterEventId, operation.occurrenceDate, job.ownerUserId)

            is SyncOperation.DeleteEvent -> delete(operation.eventId)

            is SyncOperation.CancelOccurrence ->
                delete(operation.masterEventId, operation.occurrenceDate)

            is SyncOperation.Backfill -> backfill(job.ownerUserId)

            is SyncOperation.PurgeAll -> purge(operation.userId)

            is SyncOperation.ResetAndResync -> {
                purge(operation.userId)
                backfill(operation.userId)
            }
        }
    }

    private suspend fun write(eventId: String, occurrenceDate: String?, userId: String) {
        val target = resolveTarget()
            ?: throw SyncFailure("no writable calendar on this device")

        val event = when (val result = repository.event(eventId, occurrenceDate)) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> throw SyncFailure("fetch failed: ${result.error.message}")
        }

        if (event == null) {
            // The event is gone. Mirroring nothing is the correct outcome, and the row it
            // used to have should go with it.
            delete(eventId, occurrenceDate)
            return
        }

        // A cancelled event is removed from the device calendar rather than mirrored
        // struck-through — the platform has no such concept, and leaving it there would
        // have people turning up.
        if (event.isCancelled) {
            delete(eventId, occurrenceDate)
            return
        }

        val hash = EventHash.of(event)
        val existing = mappings.find(eventId, occurrenceDate)

        if (existing != null && existing.lastHash == hash) {
            ZillitLog.d(TAG, "unchanged — skipping $eventId")
            return
        }

        val values = NativeEventMapper.toContentValues(
            event = event,
            calendarId = target.id,
            packageName = context.packageName,
            userId = userId,
            projectName = projectName(),
        )

        val nativeId = if (existing != null) {
            // An update that finds nothing has lost its row — the user deleted it by hand,
            // or the calendar was removed. Falls through to an insert rather than silently
            // doing nothing.
            if (provider.update(existing.nativeEventId, values)) {
                existing.nativeEventId
            } else {
                ZillitLog.d(TAG, "row ${existing.nativeEventId} gone — reinserting")
                provider.insert(values)
            }
        } else {
            provider.insert(values)
        } ?: throw SyncFailure("write refused by the calendar provider")

        mappings.put(
            eventId = eventId,
            occurrenceDate = occurrenceDate,
            nativeEventId = nativeId,
            nativeCalendarId = target.id,
            userId = userId,
            hash = hash,
        )
    }

    /**
     * The calendar to write to, checked against what is actually on the device.
     *
     * A stored target can outlive the calendar it names — the account gets removed, the
     * calendar deleted — and writing to a dead id fails silently, one row at a time, with
     * nothing in the UI to show for it. Falling back to the default keeps the mirror
     * working; the diagnostics report is what says the target moved.
     */
    private suspend fun resolveTarget(): CalendarTarget? {
        val stored = preferences.target()
        val available = provider.writableCalendars()

        stored?.let { target ->
            if (available.any { it.id == target.id }) return target
            ZillitLog.w(TAG, "target ${target.id} is gone — falling back")
        }

        return provider.defaultTarget()
    }

    /**
     * The project's name, for the description header.
     *
     * Read from the project store rather than the session, which carries ids only. A miss
     * simply omits the line — a mirrored event without its project named is still useful.
     */
    private fun projectName(): String? =
        session.activeProject.value?.projectId?.let { projects.name(it) }

    private suspend fun delete(eventId: String, occurrenceDate: String? = null) {
        val mapping = mappings.find(eventId, occurrenceDate)

        if (mapping == null) {
            // Deleting a master takes its per-date overrides with it, so their mappings
            // have to go too or they will point at rows that no longer exist.
            if (occurrenceDate == null) mappings.removeAllFor(eventId)
            return
        }

        provider.delete(mapping.nativeEventId)

        if (occurrenceDate == null) {
            mappings.removeAllFor(eventId)
        } else {
            mappings.remove(eventId, occurrenceDate)
        }
    }

    /**
     * Mirrors everything currently worth mirroring.
     *
     * Bounded to a window around today rather than all of history: the point of the mirror
     * is what is coming up, and writing years of finished events into someone's calendar
     * is noise they did not ask for.
     */
    private suspend fun backfill(userId: String) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.minusDays(BACKFILL_PAST_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(BACKFILL_FUTURE_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()

        val events = when (val result = repository.events(start, end)) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> throw SyncFailure("backfill fetch failed: ${result.error.message}")
        }

        // One row per series, not one per expanded occurrence: the platform expands the
        // RRULE itself, and writing every occurrence separately would produce duplicates
        // on top of the ones it generates.
        val masters = events
            .filterNot { it.isCancelled }
            .distinctBy { it.effectiveMasterEventId }

        ZillitLog.d(TAG, "backfill: ${masters.size} event(s)")

        masters.forEach { event ->
            runCatching { write(event.effectiveMasterEventId, null, userId) }
                .onFailure { ZillitLog.w(TAG, "backfill skipped ${event.id}: ${it.message}") }
        }

        sweepOrphans(userId, masters)
    }

    private suspend fun purge(userId: String) {
        val removed = provider.purgeAllFor(userId)
        mappings.clearFor(userId)
        ZillitLog.d(TAG, "purged $removed row(s) for $userId")
    }

    /**
     * Removes rows for events that no longer exist.
     *
     * A delete that arrived while the app was offline, or an event removed by someone else
     * entirely, leaves a row in the user's calendar with nothing behind it. The backfill is
     * the one moment the app knows the full live set, so that is when they are swept.
     */
    private suspend fun sweepOrphans(userId: String, live: List<CalendarEvent>) {
        val liveIds = live.map { it.effectiveMasterEventId }.toSet()

        mappings.allFor(userId)
            .filterNot { it.appEventId in liveIds }
            .forEach { orphan ->
                ZillitLog.d(TAG, "sweeping orphan ${orphan.appEventId}")
                provider.delete(orphan.nativeEventId)
                mappings.remove(orphan.appEventId, orphan.occurrenceDate)
            }
    }

    private companion object {
        const val TAG = "CalendarSyncExecutor"

        /** Enough recent history to keep last week's schedule readable. */
        const val BACKFILL_PAST_DAYS = 30L
        const val BACKFILL_FUTURE_DAYS = 365L
    }
}
