package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * One queued piece of calendar-mirroring work.
 *
 * Queued rather than performed inline because the device calendar is not always reachable:
 * the permission may not be granted yet, the provider can be busy, and the app may be in
 * the background. A durable row survives all three and is retried with backoff.
 *
 * The op carries **identifiers only**. The worker re-reads the event when it runs, so a
 * job that sat in the queue applies the event's current state rather than a stale copy.
 */
class CalendarSyncJobEntity : RealmObject {

    @PrimaryKey
    var id: String = ""

    /** Serialised [com.zillit.zillitapp.core.calendar.sync.SyncOperation]. */
    var operation: String = ""

    @Index
    var ownerUserId: String = ""

    var attempts: Int = 0

    /** Epoch millis before which this job should not run. Backoff writes it. */
    var notBefore: Long = 0L

    /** Set once retries are exhausted; the row stays for diagnostics and manual retry. */
    var isDead: Boolean = false

    var lastError: String? = null
    var createdAt: Long = 0L
}
