package com.zillit.zillitapp.core.notification

import com.zillit.zillitapp.core.badge.BadgeKey
import com.zillit.zillitapp.core.badge.describe
import com.zillit.zillitapp.core.badge.BadgeManager
import com.zillit.zillitapp.core.badge.BadgeSection
import com.zillit.zillitapp.core.badge.BadgeSource
import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.database.entity.NotificationEntity
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.query
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

/**
 * Every notification this device has been told about, and the badges derived from them.
 *
 * **Badges are a projection of stored notifications, never a number kept alongside them.**
 * v2 increments and decrements counters independently of the notifications they describe,
 * which is why its badges drift and need a "recount" endpoint. Here [recomputeBadges]
 * rebuilds every counter from the rows themselves, so a badge cannot disagree with what
 * is behind it.
 *
 * Writes are idempotent on `uuid`: the same notification arrives by FCM, by socket and by
 * the catch-up fetch, and all three must land as one row.
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val realmProvider: RealmProvider,
    private val badgeManager: BadgeManager,
) {

    private val realm get() = realmProvider.realm

    private val _stored = MutableSharedFlow<IncomingNotification>(extraBufferCapacity = 64)

    /**
     * Notifications that were new, from any channel.
     *
     * FCM and the socket both land here, so a reaction subscribed to this fires exactly
     * once however the news arrived — and still fires when one of the two channels is
     * down, which is the case that makes a screen look stale.
     */
    val stored: SharedFlow<IncomingNotification> = _stored.asSharedFlow()

    /**
     * Stores one notification.
     *
     * @return true when it was **new**. The caller uses this to decide whether to raise a
     *   system notification — a duplicate arriving over a second channel must not buzz the
     *   phone twice.
     */
    suspend fun record(incoming: IncomingNotification): Boolean {
        // An instruction is applied, never stored — see [IncomingNotification.shouldStore].
        applyInstructions(incoming)

        val result = write(listOf(incoming))
        ZillitLog.badge(
            "record ${incoming.storeOutcome(result.stored)} uuid=${incoming.uuid} " +
                "section=${incoming.section} tool=${incoming.tool} unit=${incoming.unit}",
        )
        if (result.changed) recomputeBadges()
        if (result.stored > 0) _stored.emit(incoming)
        return result.stored > 0
    }

    /**
     * Stores a whole page, then recomputes **once**.
     *
     * The catch-up sync records dozens of notifications at a time. Recomputing per
     * notification rebuilt every badge in the app N times over, which is what made the
     * counts visibly flicker while a project opened — and it is quadratic in the size of
     * the page.
     *
     * @return how many were new.
     */
    suspend fun recordAll(incoming: List<IncomingNotification>): Int {
        if (incoming.isEmpty()) return 0
        incoming.forEach { applyInstructions(it) }
        val result = write(incoming)
        // Recomputed for a read that arrived from elsewhere too, not only for new rows:
        // otherwise a badge cleared on web stays up here until something else moves it.
        if (result.changed) recomputeBadges()
        if (result.stored > 0) incoming.forEach { _stored.emit(it) }
        return result.stored
    }

    /**
     * Writes the ones we do not already hold, in one transaction.
     *
     * De-duplicated on `uuid` inside the transaction rather than by a query beforehand:
     * the same notification arrives by FCM, by socket and by the catch-up fetch, and
     * checking outside the write leaves a window where two channels both decide it is new.
     */
    private suspend fun write(incoming: List<IncomingNotification>): WriteResult {
        var stored = 0
        var updated = 0
        realm.write {
            incoming.filter { it.shouldStore }.forEach { item ->
                val existing = query<NotificationEntity>("uuid == $0", item.uuid).first().find()

                if (existing != null) {
                    // Already held. The only thing worth taking from the server is that it
                    // has since been read somewhere else — v2 merges the same way, and in
                    // the same direction: a local read is never undone by a server that has
                    // not caught up yet, but a read on web clears the badge here.
                    if (item.isRead && !existing.isRead) {
                        existing.isRead = true
                        updated++
                    }
                    // The cursor has to advance even when nothing else changed, or a row
                    // the server keeps re-sending would pin the sync to its timestamp.
                    if (item.updatedAt > existing.updatedAt) existing.updatedAt = item.updatedAt
                    if (item.fromSync) existing.fromSync = true
                    return@forEach
                }

                copyToRealm(item.toEntity(), UpdatePolicy.ALL)
                stored++
            }
        }
        return WriteResult(stored = stored, updated = updated)
    }

    /**
     * What a write changed.
     *
     * [stored] and [updated] are counted apart because they mean different things to the
     * caller: only something new belongs in the tray, while a read that arrived from
     * another device still has to move the badge.
     */
    private data class WriteResult(val stored: Int, val updated: Int) {
        val changed: Boolean get() = stored > 0 || updated > 0
    }

    /**
     * Why a payload did or did not become a stored notification.
     *
     * Spelled out because "not stored" has four different causes and they mean very
     * different things — a badge that does not move is either correct or a bug depending
     * on which one applies.
     */
    private fun IncomingNotification.storeOutcome(written: Int): String = when {
        written > 0 -> "NEW"
        control?.ignore == true -> "SKIPPED (ignore=true — backend plumbing)"
        control?.self == true -> "SKIPPED (self=true — this user's own action)"
        control?.isInstruction == true -> "SKIPPED (instruction, not news)"
        else -> "duplicate"
    }

    /**
     * Carries out what an instruction payload asks for.
     *
     * The `notification:silent` self-device sync is the important one: another of this
     * user's devices read some notifications and the server names them in
     * `read_notification_ids`. Marking them read here is what keeps two devices agreeing.
     *
     * The echo of **this** device's own sync is skipped — v2 compares `self_device_id` the
     * same way — because acting on it would be marking read what we just marked read.
     */
    private suspend fun applyInstructions(incoming: IncomingNotification) {
        val control = incoming.control ?: return
        if (!control.isInstruction) return

        val ids = control.readNotificationIds.filter { it.isNotBlank() }
        if (ids.isEmpty()) {
            ZillitLog.badge("instruction ${incoming.action} carried no ids — nothing to apply")
            return
        }

        var marked = 0
        realm.write {
            ids.forEach { id ->
                query<NotificationEntity>("uuid == $0 AND isRead == false", id)
                    .find()
                    .forEach {
                        it.isRead = true
                        marked++
                    }
            }
        }

        ZillitLog.badge("read-sync from another device: ${ids.size} id(s) → $marked marked read")
        if (marked > 0) recomputeBadges()
    }

    /**
     * Marks everything under a badge path read, then rebuilds the counters.
     *
     * One method for every module. v2 had twenty — `markReadCashEntity`,
     * `markReadTimecardDisputeEntity`, and so on — each re-deriving the same path by hand,
     * which is why they disagreed with one another. A prefix says all of it: pass a project
     * for "mark everything", a section for a tab, a unit for one chat.
     */
    suspend fun markRead(prefix: BadgeKey, excludeTool: String? = null) {
        val predicates = mutableListOf("isRead == false")
        val args = mutableListOf<Any>()

        fun pin(field: String, value: String?, operator: String = "==") {
            if (value.isNullOrEmpty()) return
            predicates += "$field $operator $${args.size}"
            args += value
        }

        pin("projectId", prefix.projectId)
        // GLOBAL is the catch-all bucket rather than a real section, so pinning it would
        // exclude the sectioned notifications the caller means to clear.
        pin("section", prefix.section?.takeIf { it != BadgeSection.GLOBAL })
        pin("tool", prefix.tool)
        pin("unit", prefix.unit)
        // Whatever the badge left out, the read leaves out too — otherwise opening a Home
        // unit would silently answer for every calendar invitation inside it.
        pin("tool", excludeTool, operator = "!=")

        var marked = 0
        realm.write {
            query<NotificationEntity>(predicates.joinToString(" AND "), *args.toTypedArray())
                .find()
                // Levels are filtered in memory: they live in three separate columns, and
                // a prefix match across them is not expressible as one RQL predicate.
                .filter { row -> prefix.levels.matchesLevelsOf(row) }
                .forEach {
                    it.isRead = true
                    marked++
                }
        }
        ZillitLog.badge("markRead ${prefix.describe()} → $marked notification(s)")
        recomputeBadges()
    }

    /**
     * Rebuilds every badge from the stored notifications.
     *
     * The single source of truth for counters. Cheap enough to run after any write, which
     * is what keeps the badge and the list from ever disagreeing.
     */
    /**
     * Marks every notification about one thing read, wherever it was filed.
     *
     * An invitation to a recurring event produces one notification per occurrence, under
     * whatever path each was sent with; answering it answers all of them, and the only
     * thing they share is the event id.
     *
     * @return how many rows were unread and now are not.
     */
    suspend fun markReadByReference(referenceId: String): Int {
        if (referenceId.isBlank()) return 0

        var marked = 0
        realm.write {
            query<NotificationEntity>("isRead == false AND referenceId == $0", referenceId)
                .find()
                .forEach {
                    it.isRead = true
                    marked++
                }
        }

        recomputeBadges()
        return marked
    }

    /**
     * Which references under a path are still unread.
     *
     * Exists because a badge row is an **aggregate**: rows are keyed by the badge path and
     * carry a count, so several unread items under one path collapse into one row and the
     * individual ids are no longer recoverable from it. Mail needs the ids — "is *this*
     * message unread" is the question every row in the list asks — so it reads the
     * notifications the badges were computed from.
     */
    fun observeUnreadReferences(
        projectId: String,
        section: String,
        unit: String? = null,
        level1: String? = null,
    ): kotlinx.coroutines.flow.Flow<Set<String>> {
        val query = when {
            unit == null -> realm.query<NotificationEntity>(
                "isRead == false AND projectId == $0 AND section == $1",
                projectId, section,
            )

            // A blank `level_1` still counts. The scoping exists so a shared mailbox's
            // unread does not bold rows in the personal one, and a row the server did not
            // tag belongs to whichever mailbox is asking — dropping it would hide real
            // unread mail, which is the worse failure of the two.
            level1 != null -> realm.query<NotificationEntity>(
                "isRead == false AND projectId == $0 AND section == $1 AND unit == $2 " +
                    "AND (level1 == $3 OR level1 == nil OR level1 == '')",
                projectId, section, unit, level1,
            )

            else -> realm.query<NotificationEntity>(
                "isRead == false AND projectId == $0 AND section == $1 AND unit == $2",
                projectId, section, unit,
            )
        }

        return query.asFlow().map { change ->
            change.list.mapNotNull { it.referenceId?.takeIf(String::isNotBlank) }.toSet()
        }
    }

    /**
     * Unread counts per `unit`, scoped to one `level_1`.
     *
     * The badge tree can group by unit already, but it cannot filter by a level below the
     * one it is grouping on — and mail needs exactly that, because the folder counts in the
     * drawer belong to whichever mailbox is open.
     */
    fun observeUnreadCountsByUnit(
        projectId: String,
        section: String,
        level1: String?,
    ): kotlinx.coroutines.flow.Flow<Map<String, Int>> {
        val query = if (level1 == null) {
            realm.query<NotificationEntity>(
                "isRead == false AND projectId == $0 AND section == $1",
                projectId, section,
            )
        } else {
            realm.query<NotificationEntity>(
                "isRead == false AND projectId == $0 AND section == $1 " +
                    "AND (level1 == $2 OR level1 == nil OR level1 == '')",
                projectId, section, level1,
            )
        }

        return query.asFlow().map { change ->
            change.list
                .mapNotNull { it.unit?.takeIf(String::isNotBlank) }
                .groupingBy { it }
                .eachCount()
        }
    }

    /** Unread counts grouped by one of the level columns — for mail, `level_1` is the mailbox. */
    fun observeUnreadByLevel1(
        projectId: String,
        section: String,
        unit: String? = null,
    ): kotlinx.coroutines.flow.Flow<Map<String, Int>> {
        val query = if (unit == null) {
            realm.query<NotificationEntity>(
                "isRead == false AND projectId == $0 AND section == $1",
                projectId, section,
            )
        } else {
            realm.query<NotificationEntity>(
                "isRead == false AND projectId == $0 AND section == $1 AND unit == $2",
                projectId, section, unit,
            )
        }

        return query.asFlow().map { change ->
            change.list
                .mapNotNull { it.level1?.takeIf(String::isNotBlank) }
                .groupingBy { it }
                .eachCount()
        }
    }

    suspend fun recomputeBadges() {
        val counts = realm.query<NotificationEntity>("isRead == false")
            .find()
            // Grouped by the **full path**, not by module. Every badge in the app is then a
            // prefix sum over these rows: a unit, a section, a whole project. Grouping by
            // `(module, scopeId)` — as this did — collapsed the levels the backend sends, so
            // anything nested (Drive folders, Deal Memo queues) could not be counted at all.
            .groupingBy { it.badgeKey() }
            .eachCount()

        ZillitLog.badge(
            "recompute → ${counts.size} path(s), ${counts.values.sum()} unread total" +
                counts.entries.joinToString("") { (key, n) -> "\n    $n  ${key.describe()}" },
        )

        // Handed over whole so the clear and the rebuild land in one Realm transaction.
        // Doing it in steps made every observer watch the badge blank out and tick back
        // up once per group.
        badgeManager.replaceSource(BadgeSource.REALTIME, counts)
    }

    /**
     * The badge path a stored notification counts against.
     *
     * Falls back to the project when the payload carries no section — an unrecognised
     * notification should still raise the project badge rather than vanish, which is what
     * the Zillit logo is for.
     */
    private fun NotificationEntity.badgeKey(): BadgeKey = BadgeKey(
        projectId = projectId,
        section = section?.takeIf { it.isNotBlank() } ?: BadgeSection.GLOBAL,
        tool = tool?.takeIf { it.isNotBlank() },
        // A C&C message identifies its conversation by room for a group and by **sender**
        // for a one-to-one — there is no room id on a direct message. Without the sender
        // fallback every direct-message badge collapsed onto the section, so the C&C tab
        // showed a count that no row in it could account for.
        unit = unit?.takeIf { it.isNotBlank() }
            ?: chatRoomId?.takeIf { it.isNotBlank() }
            ?: senderId?.takeIf { it.isNotBlank() && section == BadgeSection.CNC },
        levels = listOfNotNull(level1, level2, level3).filter { it.isNotBlank() },
    )

    /**
     * The newest notification stored for a project, or 0 when there are none.
     *
     * The cursor for an **incremental** catch-up sync: ask the server for everything
     * *after* this instead of re-reading the whole history on every app entry, which is
     * what v2's `getTimeStamp(projectId, NEXT_PARAM)` is for. On a long-lived project the
     * difference is a page versus thousands of rows.
     */
    fun newestSyncedAt(projectId: String): Long =
        realm.query<NotificationEntity>("projectId == $0 AND fromSync == true", projectId)
            .sort("updatedAt", io.realm.kotlin.query.Sort.DESCENDING)
            .first()
            .find()
            ?.updatedAt
            ?: 0L

    /**
     * Drops read notifications older than [days], then rebuilds the counters.
     *
     * v2's `deleteReadNotificationsOlderThan2Days`, and the same two-day default. Read rows
     * contribute nothing to any badge, so keeping them only grows the Realm file — but they
     * are kept briefly so a sync that re-delivers one does not resurrect it as unread.
     */
    suspend fun pruneRead(days: Int = READ_RETENTION_DAYS) {
        val cutoff = System.currentTimeMillis() - days * MILLIS_PER_DAY

        realm.write {
            delete(
                query<NotificationEntity>("isRead == true AND createdAt < $0", cutoff).find(),
            )
        }
        recomputeBadges()
    }

    /**
     * Drops one notification by uuid, then rebuilds the counters.
     *
     * For a notification the user deleted from the feed. Marking it read would be wrong:
     * read means "seen and still there", and a row nobody can open must not keep
     * contributing to any count.
     */
    suspend fun remove(uuid: String) {
        realm.write { delete(query<NotificationEntity>("uuid == $0", uuid).find()) }
        recomputeBadges()
    }

    /** Wipes the store. For signing out or leaving a project, not for reading. */
    suspend fun clearAll() {
        realm.write { delete(query<NotificationEntity>().find()) }
        badgeManager.clearBySource(BadgeSource.REALTIME)
    }

    private companion object {
        const val READ_RETENTION_DAYS = 2
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

private fun IncomingNotification.toEntity() = NotificationEntity().apply {
    uuid = this@toEntity.uuid
    projectId = this@toEntity.projectId
    module = this@toEntity.module.key
    scopeId = this@toEntity.scopeId
    section = this@toEntity.section
    tool = this@toEntity.tool
    unit = this@toEntity.unit
    action = this@toEntity.action
    level1 = this@toEntity.level1
    level2 = this@toEntity.level2
    level3 = this@toEntity.level3
    referenceId = this@toEntity.referenceId
    chatRoomId = this@toEntity.chatRoomId
    senderId = this@toEntity.senderId
    message = this@toEntity.message
    isSilent = this@toEntity.isSilent
    isRead = this@toEntity.isRead
    updatedAt = this@toEntity.updatedAt.takeIf { it > 0 } ?: this@toEntity.createdAt
    fromSync = this@toEntity.fromSync
    createdAt = this@toEntity.createdAt
    receivedAt = System.currentTimeMillis()
    eventEndAt = this@toEntity.eventEndAt
}

/**
 * Whether a prefix's levels select this row.
 *
 * Empty prefix matches everything below it, which is what makes "mark the whole tool read"
 * work without naming each folder.
 */
private fun List<String>.matchesLevelsOf(row: NotificationEntity): Boolean {
    val rowLevels = listOfNotNull(row.level1, row.level2, row.level3).filter { it.isNotBlank() }
    if (size > rowLevels.size) return false
    return zip(rowLevels).all { (wanted, actual) -> wanted == actual }
}
