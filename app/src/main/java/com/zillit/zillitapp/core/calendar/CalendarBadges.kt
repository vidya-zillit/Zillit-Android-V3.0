package com.zillit.zillitapp.core.calendar

import com.zillit.zillitapp.core.badge.BadgeSection
import com.zillit.zillitapp.core.badge.BadgeTool
import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.NotificationEntity
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.notification.BadgeSyncCoordinator
import com.zillit.zillitapp.core.notification.NotificationRepository
import com.zillit.zillitapp.core.session.SessionStore
import io.realm.kotlin.ext.query
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The calendar's two counters: invitations still ahead, and invitations already missed.
 *
 * They are **not** separate badge paths. Both are the same set of unread calendar
 * notifications, split by whether the event they refer to has already ended — which is
 * exactly how v2 derives them, by filtering its badge entries on `endDatetime`. Adding
 * counters for them would double-count against the project total the logo shows, since the
 * same notifications already feed the Calendar unit's badge.
 */
data class CalendarBadgeCounts(
    /** Invitations whose event has not happened yet. */
    val pending: Int = 0,
    /** Invitations whose event has been and gone. */
    val expired: Int = 0,
) {
    /** What the Events button shows — one number covering both. */
    val total: Int get() = pending + expired
}

@Singleton
class CalendarBadges @Inject constructor(
    private val realmProvider: RealmProvider,
    private val session: SessionStore,
    private val notifications: NotificationRepository,
    private val badgeSync: BadgeSyncCoordinator,
) {

    private val realm get() = realmProvider.realm

    /**
     * Live counts for the open project.
     *
     * Re-derived on every Realm change rather than cached: expiry is a function of the
     * clock, and a stored count would keep calling an event "pending" hours after it ended.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val counts: Flow<CalendarBadgeCounts> = session.activeProject
        .flatMapLatest { project ->
            val projectId = project?.projectId ?: return@flatMapLatest flowOf(CalendarBadgeCounts())

            // Confirmed against a live payload: a calendar invitation is classified
            // `section=home_label, tool=calendar_label`. `eventEndAt > 0` alone would be
            // enough today, but pinning the tool keeps this from quietly counting some
            // future Home notification that happens to carry an end time.
            realm.query<NotificationEntity>(
                "projectId == $0 AND isRead == false AND section == $1 AND tool == $2 " +
                    "AND eventEndAt > 0",
                projectId,
                BadgeSection.HOME,
                TOOL_CALENDAR,
            )
                .asFlow()
                .map { change ->
                    val now = System.currentTimeMillis()
                    val (expired, pending) = change.list.partition { it.eventEndAt < now }
                    CalendarBadgeCounts(pending = pending.size, expired = expired.size)
                }
        }

    /**
     * Clears the notifications for one event after its invitation is answered.
     *
     * Keyed on the event id rather than a badge path: answering one invitation must not
     * clear the badge for every other calendar notification in the project.
     */
    suspend fun onInvitationActioned(eventId: String) {
        if (eventId.isBlank()) return

        // Through the coordinator, exactly as opening a chat unit does. Writing `isRead`
        // straight into Realm — which this used to do — clears the badge on this screen and
        // nowhere else: the server still holds the notification as unread, so the next sync
        // brings the count back and the user's other devices never learn the invitation was
        // answered.
        //
        // Addressed by the event id rather than by a path, because that is how v2 answers
        // one invitation without answering the rest: `segment=calendar_invite_users` plus
        // the event's `reference_id`.
        badgeSync.markReadForReference(referenceId = eventId, segment = SEGMENT_INVITE)
    }

    /**
     * Clears the expired ones when the user opens the Expired tab.
     *
     * Opening the tab *is* reading them — there is nothing else to do with an invitation to
     * an event that already happened, so leaving the count up would make it permanent.
     */
    suspend fun onExpiredTabOpened() {
        val projectId = session.activeProject.value?.projectId ?: return
        val now = System.currentTimeMillis()

        // Locally this clears only what has expired — a pending invitation is still waiting
        // for an answer and must keep its badge.
        val expired = realm.query<NotificationEntity>(
            "projectId == $0 AND isRead == false AND eventEndAt > 0 AND eventEndAt < $1",
            projectId,
            now,
        ).find().mapNotNull { it.referenceId?.takeIf { id -> id.isNotBlank() } }.distinct()

        ZillitLog.badge("calendar: reading ${expired.size} expired invitation(s)")

        // Echoed per event rather than as one segment-wide read. v2 sends a bare
        // `calendar_invite_users` here, which tells the server to clear pending
        // invitations too — the local model then disagrees with the server until the next
        // reinstall. Naming each expired event says exactly what was read.
        expired.forEach { badgeSync.markReadForReference(it, SEGMENT_INVITE) }
    }

    private companion object {
        /** How the backend tags a calendar notification within Home. */
        const val TOOL_CALENDAR = BadgeTool.CALENDAR

        /** v2's `Constants.CALENDAR_INVITE_USER` — the segment an invitation read carries. */
        const val SEGMENT_INVITE = "calendar_invite_users"
    }
}
