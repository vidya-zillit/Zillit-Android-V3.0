package com.zillit.zillitapp.core.calendar.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.zillit.zillitapp.core.common.DateTime
import com.zillit.zillitapp.core.common.format
import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.CalendarSyncJobEntity
import com.zillit.zillitapp.core.session.SessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.realm.kotlin.ext.query
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything about the mirror's state, in one block of text.
 *
 * The reason this exists rather than "check logcat": when someone says their events are
 * not showing up, the answer is usually one of a dozen small facts — permission denied, no
 * calendar selected, the selected calendar deleted, jobs stuck behind a failure, the
 * worker never running. Asking a user on a shoot to reproduce with a laptop attached is
 * not a plan. This gathers all of it in one tap, ready to paste into a message.
 *
 * Strictly read-only. Gathering a diagnostic must never change what it is diagnosing.
 */
@Singleton
class SyncDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: CalendarProvider,
    private val preferences: CalendarSyncPreferences,
    private val mappings: NativeMappingStore,
    private val session: SessionStore,
    private val realmProvider: RealmProvider,
) {

    suspend fun gather(): String = buildString {
        val userId = session.activeProject.value?.userId.orEmpty()

        appendLine("=== Calendar sync diagnostics ===")
        appendLine("user: ${userId.ifBlank { "(none)" }}")
        appendLine("project: ${session.activeProject.value?.projectId ?: "(none)"}")
        appendLine()

        appendLine("READ_CALENDAR:  ${permissionState(Manifest.permission.READ_CALENDAR)}")
        appendLine("WRITE_CALENDAR: ${permissionState(Manifest.permission.WRITE_CALENDAR)}")
        appendLine()

        val target = preferences.target()
        appendLine("enabled: ${preferences.isEnabled()}")
        appendLine("target: ${target?.let { "${it.displayName} (${it.accountName}, id=${it.id})" } ?: "(none)"}")
        // A target that no longer exists is the quiet failure this line is here to catch:
        // the account was removed, and every write since has been going nowhere.
        appendLine(
            "target still exists: " +
                (target?.let { provider.writableCalendars().any { c -> c.id == it.id } } ?: "n/a"),
        )
        appendLine()

        appendLine("--- Writable calendars on this device ---")
        val calendars = provider.writableCalendars()
        if (calendars.isEmpty()) {
            appendLine("(none — nothing on this device can be written to)")
        } else {
            calendars.forEach { calendar ->
                val marker = if (calendar.id == target?.id) "  *** SELECTED ***" else ""
                appendLine(
                    "id=${calendar.id} type='${calendar.accountType}' " +
                        "account='${calendar.accountName}' name='${calendar.displayName}'$marker",
                )
            }
        }
        appendLine()

        appendLine("--- Rows this app owns in the device calendar ---")
        val owned = provider.ownedEventIds(userId)
        appendLine("count: ${owned.size}")
        owned.entries.take(ROW_SAMPLE).forEach { (id, uri) -> appendLine("  _id=$id uri=$uri") }
        if (owned.size > ROW_SAMPLE) appendLine("  …and ${owned.size - ROW_SAMPLE} more")
        appendLine()

        appendLine("--- Mapping store ---")
        val stored = mappings.allFor(userId)
        appendLine("count: ${stored.size}")
        // A mapping whose native row is gone means the user deleted it by hand; the next
        // write reinserts it, and this is how that gets spotted.
        val orphanedMappings = stored.count { it.nativeEventId !in owned.keys }
        appendLine("mappings with no matching native row: $orphanedMappings")
        appendLine()

        appendLine("--- Queue ---")
        val jobs = realmProvider.realm.query<CalendarSyncJobEntity>().find()
        appendLine("pending: ${jobs.count { !it.isDead }}   dead: ${jobs.count { it.isDead }}")
        if (jobs.isEmpty()) {
            appendLine("(no jobs — nothing is waiting)")
        } else {
            jobs.take(JOB_SAMPLE).forEach { job ->
                val state = if (job.isDead) "DEAD" else "PENDING"
                val due = if (job.notBefore == 0L) "now" else job.notBefore.stamp()
                appendLine("[$state] ${job.operation}")
                appendLine("   attempts=${job.attempts} due=$due queued=${job.createdAt.stamp()}")
                job.lastError?.takeIf { it.isNotBlank() }?.let { appendLine("   lastError: $it") }
            }
            if (jobs.size > JOB_SAMPLE) appendLine("…and ${jobs.size - JOB_SAMPLE} more")
        }
    }

    private fun permissionState(permission: String): String =
        if (ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            "GRANTED"
        } else {
            "DENIED"
        }

    private fun Long.stamp(): String =
        if (this == 0L) "-" else format(DateTime.PATTERN_DATE_TIME)

    private companion object {
        /** Enough rows to see the shape without producing something unpasteable. */
        const val ROW_SAMPLE = 15
        const val JOB_SAMPLE = 20
    }
}
