package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * Links one Zillit event to the row it wrote in the device's calendar.
 *
 * Without this the app cannot tell an update from a new event, and every sync would add a
 * duplicate rather than change what is there.
 *
 * Keyed by `appEventId|occurrenceDate` because a recurring series can have per-date
 * override rows alongside its master. Realm has no composite keys, so the key is built in
 * code — see [key].
 */
class NativeCalendarMappingEntity : RealmObject {

    @PrimaryKey
    var id: String = ""

    @Index
    var appEventId: String = ""

    /** `yyyy-MM-dd` for a single-occurrence override; null for the master. */
    var occurrenceDate: String? = null

    var nativeEventId: Long = 0L
    var nativeCalendarId: Long = 0L

    /** Whose mirror this is. Switching accounts must not touch another user's rows. */
    @Index
    var ownerUserId: String = ""

    /** The content fingerprint at the last write; an unchanged one skips the next. */
    var lastHash: String = ""

    var lastSyncedAt: Long = 0L

    companion object {
        fun key(appEventId: String, occurrenceDate: String? = null): String =
            "$appEventId|${occurrenceDate.orEmpty()}"
    }
}
