package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * An error-log event that could not be delivered.
 *
 * The whole envelope is stored as its serialized JSON rather than as columns: the queue's
 * only job is to replay the exact bytes later, and columns would mean a second copy of the
 * schema to keep in step with the wire model.
 */
class PendingLogEntity : RealmObject {
    /** The envelope's own `unique_id` — already a fresh UUID v4 per event. */
    @PrimaryKey
    var uniqueId: String = ""

    /** The serialized [com.zillit.zillitapp.core.errorlog.LogEnvelope]. */
    var envelopeJson: String = ""

    /** When it was queued, so the oldest ship first and a stuck queue is diagnosable. */
    var queuedAt: Long = 0L
}
