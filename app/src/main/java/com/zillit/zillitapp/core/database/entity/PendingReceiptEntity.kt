package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * A read or delivered receipt that could not be sent yet.
 *
 * Receipts are fire-and-forget by design — nothing on screen waits for one — but that makes
 * them easy to lose, and a lost receipt is permanent: the sender's message stays on one tick
 * forever, because nothing ever re-sends it. v2 queues them in preferences for exactly this
 * reason; here they are rows so the flush can be a query rather than a JSON blob.
 *
 * The common case is a push arriving while the socket is down: the message is delivered to
 * this device, the sender should see two ticks, and there is no connection to say so on.
 */
class PendingReceiptEntity : RealmObject {

    /** The message the receipt is about — one receipt per message, so it is the key. */
    @PrimaryKey
    var messageServerId: String = ""

    @Index
    var projectId: String = ""

    /** [com.zillit.zillitapp.feature.cnc.data.ChatSurface] key. */
    var surface: String = ""

    /** `2` delivered, `3` read. A later read replaces a pending delivered. */
    var status: Int = 0

    var isGroup: Boolean = false

    /** Room id for a group, the other person's user id for a one-to-one. */
    var conversationId: String = ""

    var chatTool: String = ""
    var departmentId: String = ""
    var budgetDocumentId: String = ""

    var createdAt: Long = 0
}
