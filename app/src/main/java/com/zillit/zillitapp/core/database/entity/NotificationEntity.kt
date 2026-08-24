package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * A stored notification.
 *
 * Every inbound notification is persisted — **including silent ones**. A silent push
 * carries no tray notification but still means "something changed"; it is what keeps
 * badges correct while the app is in the background, and dropping it would leave the
 * badge stale until the next full API refresh.
 *
 * Keyed on the server's `notification_uuid` so redelivery is idempotent: FCM can deliver
 * the same message more than once, and the same event can arrive over both FCM and the
 * socket. Without a stable key, one event would increment a badge twice.
 */
class NotificationEntity : RealmObject {
    @PrimaryKey
    var uuid: String = ""

    @Index
    var projectId: String = ""

    /** Which [com.zillit.zillitapp.core.badge.BadgeModule] this counts toward. */
    @Index
    var module: String = ""

    /** The badge scope inside that module — unit id, chat room id, tool id. */
    @Index
    var scopeId: String = ""

    /** Server taxonomy, kept verbatim so deep-linking can route on it later. */
    var section: String? = null
    var tool: String? = null
    var unit: String? = null
    var action: String? = null

    /** Hierarchy for tools that nest (Drive, Doc Distribution). */
    var level1: String? = null
    var level2: String? = null
    var level3: String? = null

    var referenceId: String? = null
    var chatRoomId: String? = null
    var senderId: String? = null

    /** Body text. May be server-encrypted; decrypted at render, never stored decrypted. */
    var message: String? = null

    /** True when it arrived as a data-only push with no tray notification. */
    var isSilent: Boolean = false

    @Index
    var isRead: Boolean = false

    var createdAt: Long = 0L

    /**
     * The server's own `updated` stamp.
     *
     * The badge sync is a cursor over **this**, not over creation: reading a notification
     * elsewhere bumps it, which is how a device that was asleep learns the count dropped.
     */
    var updatedAt: Long = 0L

    /**
     * True when this row came from the badge-sync feed rather than a push or the socket.
     *
     * Only these move the cursor. A push arrives with a timestamp of "now" while the feed
     * may still have older rows unfetched — letting it set the cursor would skip them
     * permanently. v2 keeps the same `isFromApiCall` distinction for the same reason.
     */
    var fromSync: Boolean = false
    var receivedAt: Long = 0L

    /**
     * When the event this notification is about ends, for calendar invitations.
     *
     * Stored because the calendar's two counters are the *same* set of unread invitations
     * split by whether their event has passed — pending ahead of it, expired behind. v2
     * derives them the same way, from `endDatetime` on each badge entry. 0 when the
     * notification is not about an event.
     */
    var eventEndAt: Long = 0L
}
