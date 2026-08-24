package com.zillit.zillitapp.core.calendar.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy as WorkBackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zillit.zillitapp.core.calendar.CalendarChange
import com.zillit.zillitapp.core.calendar.CalendarRealtime
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.session.SessionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors Zillit events into the device's own calendar.
 *
 * The entry point for everything in this package. Callers say what happened — an event was
 * created, edited, deleted — and this decides whether anything needs writing, queues it,
 * and lets WorkManager run it when it can.
 *
 * Nothing here writes directly. Every path goes through the queue, because the device
 * calendar is not always available: the permission may be missing, the app may be in the
 * background, the provider may be busy. A durable job survives all three.
 */
@Singleton
class CalendarSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queue: CalendarSyncQueue,
    private val preferences: CalendarSyncPreferences,
    private val provider: CalendarProvider,
    private val session: SessionStore,
    realtime: CalendarRealtime,
) {

    private val scope = CoroutineScope(SupervisorJob())

    /** Outstanding and failed work, for the settings screen. */
    val pendingCount: Flow<Int> = queue.pendingCount
    val failedCount: Flow<Int> = queue.deadCount

    val enabled: Flow<Boolean> = preferences.enabled

    init {
        // The mirror follows the app: whatever changes the in-app calendar changes here.
        // Own-device actions are *not* excluded here the way they are for the UI — this
        // device's own create is exactly what needs mirroring.
        scope.launch {
            realtime.changes.collect { change ->
                when (change.action) {
                    CalendarChange.Action.DELETED -> onEventDeleted(change.eventId)
                    else -> onEventChanged(change.eventId)
                }
            }
        }
    }

    /**
     * Turns mirroring on and fills the device calendar with what is already scheduled.
     *
     * @return false when there is no writable calendar to use — the caller shows why
     *   rather than leaving a switch that turns itself back off.
     */
    suspend fun enable(target: CalendarTarget? = null): Boolean {
        val chosen = target ?: provider.defaultTarget() ?: return false

        preferences.setTarget(chosen)
        preferences.setEnabled(true)

        val userId = session.activeProject.value?.userId ?: return false
        queue.enqueue(SyncOperation.Backfill(userId), userId)
        schedule()
        return true
    }

    /**
     * Turns mirroring off and removes what it wrote.
     *
     * Leaving the events behind would be worse than never mirroring: they would sit in the
     * user's calendar with nothing keeping them current, quietly going stale.
     */
    suspend fun disable() {
        preferences.setEnabled(false)

        val userId = session.activeProject.value?.userId ?: return
        // Queued through the same path so a purge that cannot run now still happens later.
        queue.clearFor(userId)
        queue.enqueue(SyncOperation.PurgeAll(userId), userId)
        scheduleIgnoringEnabledFlag()
    }

    /** Wipes the mirror and rebuilds it — the settings screen's repair action. */
    suspend fun resetAndResync() {
        val userId = session.activeProject.value?.userId ?: return
        queue.enqueue(SyncOperation.ResetAndResync(userId), userId)
        schedule()
    }

    /** Re-queues work that ran out of retries. */
    suspend fun retryFailed() {
        queue.retryDead()
        schedule()
    }

    suspend fun onEventChanged(eventId: String) {
        val userId = enabledUserOrNull() ?: return
        queue.enqueue(SyncOperation.WriteEvent(eventId), userId)
        schedule()
    }

    suspend fun onEventDeleted(eventId: String) {
        val userId = enabledUserOrNull() ?: return
        queue.enqueue(SyncOperation.DeleteEvent(eventId), userId)
        schedule()
    }

    suspend fun onOccurrenceChanged(masterEventId: String, occurrenceDate: String) {
        val userId = enabledUserOrNull() ?: return
        queue.enqueue(SyncOperation.WriteOccurrence(masterEventId, occurrenceDate), userId)
        schedule()
    }

    suspend fun onOccurrenceCancelled(masterEventId: String, occurrenceDate: String) {
        val userId = enabledUserOrNull() ?: return
        queue.enqueue(SyncOperation.CancelOccurrence(masterEventId, occurrenceDate), userId)
        schedule()
    }

    /** The signed-in user, but only when mirroring is actually on. */
    private suspend fun enabledUserOrNull(): String? =
        session.activeProject.value?.userId?.takeIf { preferences.isEnabled() }

    private suspend fun schedule() {
        if (!preferences.isEnabled()) return
        scheduleIgnoringEnabledFlag()
    }

    /** Used by disable, whose purge has to run *after* the flag is already off. */
    private fun scheduleIgnoringEnabledFlag() {
        val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>()
            // The executor fetches each event by id, so it needs the network — not because
            // the calendar provider does.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(WorkBackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            // KEEP, not REPLACE: a run already underway should finish. The queue is
            // durable, so anything enqueued meanwhile is picked up by the next run.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val WORK_NAME = "calendar-sync"
        private const val BACKOFF_SECONDS = 30L
    }
}

/**
 * Drains the queue.
 *
 * Runs under WorkManager so mirroring survives the app being closed and waits for a
 * connection instead of failing without one.
 */
@HiltWorker
class CalendarSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: CalendarSyncQueue,
    private val executor: CalendarSyncExecutor,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val jobs = queue.due()
        if (jobs.isEmpty()) return Result.success()

        ZillitLog.d(TAG, "running ${jobs.size} job(s)")
        var retryable = false

        jobs.forEach { job ->
            runCatching { executor.execute(job) }
                .onSuccess { queue.onSucceeded(job.id) }
                .onFailure { error ->
                    ZillitLog.w(TAG, "job ${job.operation} failed: ${error.message}")
                    queue.onFailed(job.id, error.message)
                    retryable = true
                }
        }

        // Retry asks WorkManager to bring the whole run back; the per-job backoff decides
        // which of them are actually due by then.
        return if (retryable) Result.retry() else Result.success()
    }

    private companion object {
        const val TAG = "CalendarSyncWorker"
    }
}
