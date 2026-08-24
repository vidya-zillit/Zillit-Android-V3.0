package com.zillit.zillitapp.core.calendar.sync

import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.CalendarSyncJobEntity
import com.zillit.zillitapp.core.database.entity.NativeCalendarMappingEntity
import com.zillit.zillitapp.core.logging.ZillitLog
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A job as the executor sees it. */
data class SyncJob(
    val id: String,
    val operation: SyncOperation,
    val ownerUserId: String,
    val attempts: Int,
)

/**
 * The durable work list for device-calendar mirroring.
 *
 * Jobs are **de-duplicated by what they do**: queueing "write event X" twice while the
 * first is still pending leaves one job, because the executor re-reads the event anyway
 * and the second write would be identical. Without this, an event edited three times in a
 * row would write three times.
 */
@Singleton
class CalendarSyncQueue @Inject constructor(
    private val realmProvider: RealmProvider,
) {

    private val realm get() = realmProvider.realm

    /** How much work is outstanding, for the settings screen's health line. */
    val pendingCount: Flow<Int> = realm.query<CalendarSyncJobEntity>("isDead == false")
        .asFlow()
        .map { it.list.size }

    val deadCount: Flow<Int> = realm.query<CalendarSyncJobEntity>("isDead == true")
        .asFlow()
        .map { it.list.size }

    suspend fun enqueue(operation: SyncOperation, userId: String) {
        val serialised = operation.serialize()

        realm.write {
            // The serialised op plus the owner is the identity: same work, same row.
            val existing = query<CalendarSyncJobEntity>(
                "operation == $0 AND ownerUserId == $1 AND isDead == false",
                serialised,
                userId,
            ).first().find()

            if (existing != null) return@write

            copyToRealm(
                CalendarSyncJobEntity().apply {
                    this.id = "$userId:$serialised:${System.currentTimeMillis()}"
                    this.operation = serialised
                    this.ownerUserId = userId
                    this.createdAt = System.currentTimeMillis()
                },
                UpdatePolicy.ALL,
            )
        }

        ZillitLog.d(TAG, "queued $serialised")
    }

    /** Jobs that are due now, oldest first. */
    fun due(limit: Int = BATCH_SIZE): List<SyncJob> {
        val now = System.currentTimeMillis()
        return realm.query<CalendarSyncJobEntity>("isDead == false AND notBefore <= $0", now)
            .find()
            .sortedBy { it.createdAt }
            .take(limit)
            .mapNotNull { row ->
                SyncOperation.parse(row.operation)?.let {
                    SyncJob(row.id, it, row.ownerUserId, row.attempts)
                }
            }
    }

    suspend fun onSucceeded(jobId: String) {
        realm.write {
            query<CalendarSyncJobEntity>("id == $0", jobId).first().find()?.let { delete(it) }
        }
    }

    /**
     * Records a failure and schedules the retry.
     *
     * A job that has run out of attempts is kept rather than deleted: it is the only
     * evidence that something never made it into the user's calendar, and the settings
     * screen offers to retry it.
     */
    suspend fun onFailed(jobId: String, error: String?) {
        realm.write {
            val row = query<CalendarSyncJobEntity>("id == $0", jobId).first().find() ?: return@write
            row.attempts += 1
            row.lastError = error

            if (BackoffPolicy.shouldGiveUp(row.attempts)) {
                row.isDead = true
                ZillitLog.w(TAG, "job ${row.operation} gave up after ${row.attempts}: $error")
            } else {
                row.notBefore = System.currentTimeMillis() +
                    BackoffPolicy.nextDelayMs(row.attempts)
            }
        }
    }

    /** Puts dead jobs back in the queue — the settings screen's "retry" action. */
    suspend fun retryDead() {
        realm.write {
            query<CalendarSyncJobEntity>("isDead == true").find().forEach { row ->
                row.isDead = false
                row.attempts = 0
                row.notBefore = 0
                row.lastError = null
            }
        }
    }

    /** Drops everything for a user — switching sync off, or signing out. */
    suspend fun clearFor(userId: String) {
        realm.write {
            delete(query<CalendarSyncJobEntity>("ownerUserId == $0", userId).find())
        }
    }
}

/**
 * Which device-calendar row belongs to which event.
 *
 * The reason an update is an update: without a mapping the app cannot find the row it
 * wrote last time, and every sync would insert a duplicate.
 */
@Singleton
class NativeMappingStore @Inject constructor(
    private val realmProvider: RealmProvider,
) {

    private val realm get() = realmProvider.realm

    fun find(eventId: String, occurrenceDate: String? = null): NativeCalendarMappingEntity? =
        realm.query<NativeCalendarMappingEntity>(
            "id == $0",
            NativeCalendarMappingEntity.key(eventId, occurrenceDate),
        ).first().find()

    /** Every row this app believes it owns for a user. */
    fun allFor(userId: String): List<NativeCalendarMappingEntity> =
        realm.query<NativeCalendarMappingEntity>("ownerUserId == $0", userId).find()

    suspend fun put(
        eventId: String,
        occurrenceDate: String?,
        nativeEventId: Long,
        nativeCalendarId: Long,
        userId: String,
        hash: String,
    ) {
        realm.write {
            copyToRealm(
                NativeCalendarMappingEntity().apply {
                    this.id = NativeCalendarMappingEntity.key(eventId, occurrenceDate)
                    this.appEventId = eventId
                    this.occurrenceDate = occurrenceDate
                    this.nativeEventId = nativeEventId
                    this.nativeCalendarId = nativeCalendarId
                    this.ownerUserId = userId
                    this.lastHash = hash
                    this.lastSyncedAt = System.currentTimeMillis()
                },
                UpdatePolicy.ALL,
            )
        }
    }

    suspend fun remove(eventId: String, occurrenceDate: String? = null) {
        realm.write {
            query<NativeCalendarMappingEntity>(
                "id == $0",
                NativeCalendarMappingEntity.key(eventId, occurrenceDate),
            ).first().find()?.let { delete(it) }
        }
    }

    /** Everything for one event, master and per-date overrides alike. */
    suspend fun removeAllFor(eventId: String) {
        realm.write {
            delete(query<NativeCalendarMappingEntity>("appEventId == $0", eventId).find())
        }
    }

    suspend fun clearFor(userId: String) {
        realm.write {
            delete(query<NativeCalendarMappingEntity>("ownerUserId == $0", userId).find())
        }
    }
}

private const val TAG = "CalendarSyncQueue"
private const val BATCH_SIZE = 50
