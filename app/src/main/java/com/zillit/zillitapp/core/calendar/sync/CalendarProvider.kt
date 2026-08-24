package com.zillit.zillitapp.core.calendar.sync

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.zillit.zillitapp.core.logging.ZillitLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's calendar, as far as this app touches it.
 *
 * Every method is permission-guarded and returns a benign value rather than throwing: sync
 * is an optional convenience, and a revoked permission mid-flight must not take down
 * whatever queued the write.
 */
@Singleton
class CalendarProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Whether the app may read **and** write. One without the other is not enough. */
    fun hasPermission(): Boolean =
        granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR)

    /**
     * Calendars this app could write to.
     *
     * Filtered to ones the user actually owns and can see — writing into a subscribed
     * holiday feed or a hidden calendar would put events where nobody looks.
     */
    fun writableCalendars(): List<CalendarTarget> {
        if (!hasPermission()) return emptyList()

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        )

        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? " +
            "AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_OWNER.toString())

        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                args,
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            CalendarTarget(
                                id = cursor.getLong(0),
                                accountName = cursor.getString(1).orEmpty(),
                                accountType = cursor.getString(2).orEmpty(),
                                displayName = cursor.getString(3).orEmpty(),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.onFailure { ZillitLog.w(TAG, "Listing calendars failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * The calendar to write to when the user has not chosen one.
     *
     * Prefers a Google account, because that is the one that syncs to their other devices
     * — a local-only calendar would strand the mirror on this phone.
     */
    fun defaultTarget(): CalendarTarget? {
        val calendars = writableCalendars()
        return calendars.firstOrNull { it.accountType == ACCOUNT_TYPE_GOOGLE }
            ?: calendars.firstOrNull()
    }

    /** Inserts a row and returns its id, or null when the write was refused. */
    fun insert(values: ContentValues): Long? {
        if (!hasPermission()) return null
        return runCatching {
            context.contentResolver
                .insert(CalendarContract.Events.CONTENT_URI, values)
                ?.let { ContentUris.parseId(it) }
        }.onFailure { ZillitLog.w(TAG, "Insert failed: ${it.message}") }.getOrNull()
    }

    /** Updates a row. False when it no longer exists or the write was refused. */
    fun update(nativeEventId: Long, values: ContentValues): Boolean {
        if (!hasPermission()) return false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, nativeEventId)
        return runCatching {
            context.contentResolver.update(uri, values, null, null) > 0
        }.onFailure { ZillitLog.w(TAG, "Update failed: ${it.message}") }.getOrDefault(false)
    }

    /** Deletes a row. Deleting a recurring master takes its exceptions with it. */
    fun delete(nativeEventId: Long): Boolean {
        if (!hasPermission()) return false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, nativeEventId)
        return runCatching {
            context.contentResolver.delete(uri, null, null) > 0
        }.onFailure { ZillitLog.w(TAG, "Delete failed: ${it.message}") }.getOrDefault(false)
    }

    /**
     * Deletes every row this app wrote for [userId].
     *
     * Matched on the marker written into `CUSTOM_APP_URI`, not on the mapping store — the
     * point is to catch rows the store has lost track of, which is exactly what a reinstall
     * or a cleared database leaves behind.
     */
    fun purgeAllFor(userId: String): Int {
        if (!hasPermission()) return 0

        val selection = "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? " +
            "AND ${CalendarContract.Events.CUSTOM_APP_URI} LIKE ?"
        val args = arrayOf(context.packageName, "%${NativeEventUrl.userMarker(userId)}%")

        return runCatching {
            context.contentResolver.delete(CalendarContract.Events.CONTENT_URI, selection, args)
        }.onFailure { ZillitLog.w(TAG, "Purge failed: ${it.message}") }.getOrDefault(0)
    }

    /**
     * Native row ids this app owns for [userId], newest first.
     *
     * The orphan sweep compares this against the mapping store: anything here that the
     * store does not know about belongs to an event that is gone.
     */
    fun ownedEventIds(userId: String): Map<Long, String> {
        if (!hasPermission()) return emptyMap()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CUSTOM_APP_URI,
        )
        val selection = "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? " +
            "AND ${CalendarContract.Events.CUSTOM_APP_URI} LIKE ?"
        val args = arrayOf(context.packageName, "%${NativeEventUrl.userMarker(userId)}%")

        return runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                args,
                null,
            )?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        put(cursor.getLong(0), cursor.getString(1).orEmpty())
                    }
                }
            }.orEmpty()
        }.onFailure { ZillitLog.w(TAG, "Owned-row query failed: ${it.message}") }
            .getOrDefault(emptyMap())
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "CalendarProvider"
        const val ACCOUNT_TYPE_GOOGLE = "com.google"
    }
}
