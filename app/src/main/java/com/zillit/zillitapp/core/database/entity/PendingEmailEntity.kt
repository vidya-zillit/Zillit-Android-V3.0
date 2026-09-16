package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * A mail that has been sent but has not reached the server yet.
 *
 * Sending is not a request the composer waits on. The screen closes the moment Send is
 * tapped, and the mail is delivered from here — so backgrounding the app, losing signal or
 * the process being killed mid-request costs nothing. Without this row, a send started on a
 * train was simply lost, with no error and nothing in Sent.
 *
 * The whole request is stored as JSON rather than as columns. It is written once and read
 * once, never queried by its contents, and the alternative is mirroring a dozen DTO fields
 * into Realm and keeping them in step with the wire format.
 */
class PendingEmailEntity : RealmObject {
    @PrimaryKey
    var id: String = ""

    var projectId: String = ""

    /**
     * The mailbox this was composed in, frozen at the moment Send was tapped.
     *
     * Read back at delivery rather than taken from whatever is active then: switching to
     * the shared mailbox while a personal mail is still queued must not send it from the
     * shared one.
     */
    var mailboxScope: String = ""

    /** The serialized send request, exactly as it will go on the wire. */
    var payloadJson: String = ""

    /** Enough to draw the optimistic row in Sent without deserializing the payload. */
    var subject: String = ""
    var recipients: String = ""
    var createdAt: Long = 0L

    var attempts: Int = 0

    /** The last failure's label key, for a row that keeps failing. Null while untried. */
    var lastError: String? = null
}
