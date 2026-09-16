package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.ext.realmListOf
import io.realm.kotlin.types.EmbeddedRealmObject
import io.realm.kotlin.types.RealmList
import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * One cached message.
 *
 * Keyed `projectId:scope:folderName:messageId`, which is v2's key too — and it has to
 * include the folder, because the same message really does exist in two folders at once
 * (a reply is in Sent and its thread is in Inbox) and they carry different uids.
 *
 * v2 stores `to`, `cc`, `bcc` and the attachment list as **JSON strings** inside Realm
 * columns, `hasAttachments` as the string `"true"`, and `references` comma-joined. Every
 * read then re-parses them. Here they are real lists, which is what makes "who else was on
 * this" a query rather than a parse.
 *
 * ### The one v2 bug this key fixes
 * `moveEmails` there rewrites `folderName` but leaves `uniqueId` — which embeds the *old*
 * folder — so the next sync inserts a second row for the same message. Moving in v3
 * deletes and re-inserts under the new key, so the row cannot desynchronise from its id.
 */
class EmailEntity : RealmObject {

    @PrimaryKey
    var id: String = ""

    @Index
    var projectId: String = ""

    /** "personal" or "shared". Every query filters on this as well as the project. */
    @Index
    var mailboxScope: String = ""

    @Index
    var folderName: String = ""

    /** The server's message id — `messageId` on the wire, not the IMAP uid. */
    var messageId: String = ""

    /** IMAP uid, unique within a folder. What sync diffs on. */
    var uid: Int = 0

    /**
     * Computed locally, never read from the wire.
     *
     * The server sends a `thread_id`; v2 parses it and then ignores it because the two
     * disagree and the list groups by the locally computed one.
     */
    @Index
    var threadId: String = ""

    var trailReferenceId: String = ""

    var subject: String = ""

    var fromAddress: String = ""
    var fromName: String = ""

    /** Encoded `Name <addr>` entries, parsed at the domain boundary. */
    var to: RealmList<String> = realmListOf()
    var cc: RealmList<String> = realmListOf()
    var bcc: RealmList<String> = realmListOf()

    /** HTML when [isHtml], otherwise unused. */
    var body: String = ""

    /** Plain text. Also where the list row's snippet comes from. */
    var text: String = ""

    var isHtml: Boolean = false

    /**
     * The stored read flag.
     *
     * Authoritative **only** in Sent, Trash and Junk. Everywhere else an email is unread
     * iff a badge row names it, which is how read state propagates between a user's
     * devices without an IMAP flag — see `EmailReadState`.
     */
    var read: Boolean = false

    @Index
    var createdAt: Long = 0

    var attachments: RealmList<EmailAttachmentEntity> = realmListOf()

    /** What the server reported, which can exceed [attachments] before a body is fetched. */
    var attachmentCount: Int = 0

    var references: RealmList<String> = realmListOf()
    var inReplyTo: String = ""

    /** Queued locally, not yet accepted by the server. */
    var pending: Boolean = false

    /**
     * A draft's idempotency key — the `unique_id` its `POST` was made with.
     *
     * Empty on everything that is not a draft. It is what keys a draft row before the
     * server id exists, so a create whose response never arrived can be replayed onto the
     * same record instead of producing a second draft.
     */
    @Index
    var draftUniqueId: String = ""

    /**
     * Why the send failed.
     *
     * v2 models and persists this and then never shows it, so a mail that failed looks
     * exactly like one that sent.
     */
    var sendError: String = ""

    /** True once the full body has been downloaded; a list row only needs the index. */
    var bodyLoaded: Boolean = false

    companion object {
        fun primaryKey(
            projectId: String,
            scope: String,
            folderName: String,
            messageId: String,
        ): String = "$projectId:$scope:$folderName:$messageId"

        /**
         * A draft's key, built from its `unique_id` rather than its server id.
         *
         * Stable from before the first `POST` returns until long after, so a create that
         * completes late updates the row in place instead of inserting a duplicate.
         */
        fun draftKey(projectId: String, scope: String, uniqueId: String): String =
            primaryKey(projectId, scope, EMAIL_DRAFTS_FOLDER, uniqueId)

        /** Matches `EmailFolders.DRAFTS`; duplicated here so the entity has no UI import. */
        private const val EMAIL_DRAFTS_FOLDER = "Drafts"
    }
}

/**
 * An attachment, embedded in its message.
 *
 * Embedded rather than a top-level object because an attachment has no life of its own —
 * it is deleted with its message, and nothing ever looks one up by id.
 */
class EmailAttachmentEntity : EmbeddedRealmObject {
    var attachmentId: String = ""
    var name: String = ""

    /** The S3 object key. */
    var media: String = ""
    var bucket: String = ""
    var region: String = ""
    var contentType: String = ""

    /** Set for inline images; what a `cid:` in the body points at. */
    var contentId: String = ""
    var size: Long = 0

    /** "inline" or "attachment". Decides whether it belongs to the body or the list. */
    var contentDisposition: String = "attachment"
}

/**
 * One folder in one mailbox.
 *
 * Keyed `projectId:scope:folderName`. Unread counts are deliberately **not** stored here:
 * they come from the badge tree, which changes without Realm changing, so caching them
 * would guarantee they go stale.
 */
class EmailFolderEntity : RealmObject {

    @PrimaryKey
    var id: String = ""

    @Index
    var projectId: String = ""

    @Index
    var mailboxScope: String = ""

    var folderId: String = ""

    /** The server's name, which may be qualified — `INBOX.Vendors`. */
    var folderName: String = ""

    var systemDefined: Boolean = false

    /** Server order, so the drawer lists folders the way the web client does. */
    var sortOrder: Int = 0

    companion object {
        fun primaryKey(projectId: String, scope: String, folderName: String): String =
            "$projectId:$scope:$folderName"
    }
}
