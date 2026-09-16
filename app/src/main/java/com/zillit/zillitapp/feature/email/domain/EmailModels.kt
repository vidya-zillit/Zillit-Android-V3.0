package com.zillit.zillitapp.feature.email.domain

/**
 * The email module's domain vocabulary.
 *
 * Carried over from v2's `new_email/domain/model` with the wire-shape leftovers removed:
 * v2 stores `hasAttachments` as the **string** `"true"`, keeps `to`/`cc`/`bcc` as JSON text,
 * and comma-joins `references`, all because those fields are Realm columns pretending to be
 * a document. Here the domain model is a domain model and the storage layer converts.
 */

/** Which mailbox the user is looking at. Every query is scoped by this. */
enum class MailboxScope(val key: String) {
    /** The user's own address. */
    PERSONAL("personal"),

    /** The project's shared "Accounts" mailbox, for users entitled to it. */
    SHARED("shared"),
}

/**
 * A mail address with its display name kept separate.
 *
 * v2 passes `"Name <a@b.com>"` around as one string and re-parses it with a regex at every
 * point of use — in the row, in the trail header, in reply-all's "is this me?" check. Parsing
 * once, here, is what makes reply-all able to compare addresses rather than whole strings.
 */
data class EmailAddress(
    val address: String,
    val name: String = "",
) {
    /** What to show. The name if there is one, otherwise the address itself. */
    val display: String get() = name.ifBlank { address }

    /** What to put on the wire. */
    val encoded: String get() = if (name.isBlank()) address else "$name <$address>"

    val initials: String
        get() = display.trim()
            .split(' ', '.', '_', '-')
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifBlank { "?" }

    companion object {
        private val NAME_AND_ADDRESS = Regex("""^\s*(.*?)\s*<([^>]+)>\s*$""")

        /** Reads `Name <a@b.com>`, `<a@b.com>` or a bare `a@b.com`. */
        fun parse(raw: String): EmailAddress {
            val match = NAME_AND_ADDRESS.find(raw)
                ?: return EmailAddress(address = raw.trim())

            val name = match.groupValues[1].trim().trim('"')
            return EmailAddress(address = match.groupValues[2].trim(), name = name)
        }

        fun parseAll(raw: List<String>): List<EmailAddress> = raw
            .filter { it.isNotBlank() }
            .map(::parse)
    }
}

/**
 * Is this a plausible mail address?
 *
 * v2's only check is `contains("@")`, which passes `a@`, `@b` and `a@b`. This is still not
 * RFC 5322 — nothing short of a parser is — but it requires a local part, a domain, and a
 * dotted TLD, which is what catches the typos people actually make.
 */
fun String.isPlausibleEmail(): Boolean = EMAIL_PATTERN.matches(trim())

private val EMAIL_PATTERN = Regex(
    """^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$""",
)

/** One message. */
data class Email(
    val id: String,
    val uid: Int = 0,
    val folderName: String = "",
    val threadId: String = "",
    val trailReferenceId: String = "",
    val subject: String = "",
    val from: EmailAddress = EmailAddress(""),
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    /** The HTML body when [isHtml], otherwise unused. */
    val body: String = "",
    /** The plain-text body. Also what the snippet is taken from. */
    val text: String = "",
    val isHtml: Boolean = false,
    val read: Boolean = false,
    val createdAt: Long = 0,
    val attachments: List<EmailAttachment> = emptyList(),
    val attachmentCount: Int = 0,
    val references: List<String> = emptyList(),
    val inReplyTo: String = "",
    /** Queued locally, not yet accepted by the server. */
    val pending: Boolean = false,
    /**
     * A draft's idempotency key. Empty on everything that is not a draft.
     *
     * Carried so reopening a draft can go on writing under the key it was created with,
     * and so a draft whose create never got a response can still be listed and reopened —
     * it has no server id to be listed under.
     */
    val draftUniqueId: String = "",
    /**
     * Why the send failed.
     *
     * v2 models and persists this and then never shows it, so a mail that failed to send
     * looks exactly like one that sent.
     */
    val sendError: String? = null,
) {
    val isUnread: Boolean get() = !read

    /** Attachments worth listing — inline images belong to the body, not to the list. */
    val visibleAttachments: List<EmailAttachment> get() = attachments.filterNot { it.isInline }

    val hasAttachments: Boolean get() = visibleAttachments.isNotEmpty() || attachmentCount > 0

    /** The grey second line in a row: the first 120 characters, tags stripped. */
    val snippet: String
        get() = text.ifBlank { body }
            .replace(HTML_TAG, " ")
            .replace(HTML_ENTITY, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .take(SNIPPET_LENGTH)

    /** Everyone who was on it, deduplicated by address. */
    fun allRecipients(): List<EmailAddress> =
        (to + cc + bcc).distinctBy { it.address.lowercase() }

    private companion object {
        val HTML_TAG = Regex("<[^>]*>")
        val HTML_ENTITY = Regex("""&nbsp;|&amp;|&lt;|&gt;|&quot;|&#\d+;""")
        val WHITESPACE = Regex("""\s+""")
        const val SNIPPET_LENGTH = 120
    }
}

/**
 * The thread id, computed the same way the web client computes it.
 *
 * Local, never read from the wire: the server sends a `thread_id` and v2 parses and then
 * ignores it, because the two disagree and the list groups by this one.
 */
fun calculateThreadId(references: List<String>, inReplyTo: String, emailId: String): String {
    fun usable(value: String) = value.isNotBlank() && value != "<>"

    references.firstOrNull(::usable)?.let { return it }
    if (usable(inReplyTo)) return inReplyTo
    return emailId
}

/** A group of messages shown as one row when conversation view is on. */
data class EmailThread(
    val threadId: String,
    val emails: List<Email>,
    /**
     * Counts spanning every folder, not just this one.
     *
     * A reply lives in Sent and its original in Inbox; counting only the current folder
     * tells the user a two-message conversation has one message in it.
     */
    val crossFolder: ThreadCounts? = null,
) {
    val latest: Email get() = emails.maxByOrNull { it.createdAt } ?: emails.first()

    val subject: String get() = latest.subject

    val total: Int get() = crossFolder?.total ?: emails.size

    val unreadCount: Int get() = crossFolder?.unread ?: emails.count { it.isUnread }

    val isUnread: Boolean get() = unreadCount > 0

    val hasAttachments: Boolean get() = emails.any { it.hasAttachments }
}

data class ThreadCounts(val total: Int = 1, val unread: Int = 0)

data class EmailAttachment(
    val id: String = "",
    val attachmentId: String = "",
    val name: String = "",
    /** The S3 object key. */
    val media: String = "",
    val bucket: String = "",
    val region: String = "",
    val contentType: String = "",
    /** Set for inline images; what a `cid:` in the body points at. */
    val contentId: String = "",
    val size: Long = 0,
    val contentDisposition: String = DISPOSITION_ATTACHMENT,
) {
    val isInline: Boolean get() = contentDisposition == DISPOSITION_INLINE

    companion object {
        const val DISPOSITION_INLINE = "inline"
        const val DISPOSITION_ATTACHMENT = "attachment"
    }
}

/** A mailbox folder. */
data class EmailFolder(
    val folderId: String = "",
    /** The server's name, which may be qualified — `INBOX.Vendors`. */
    val folderName: String = "",
    val unreadCount: Int = 0,
    val systemDefined: Boolean = false,
) {
    val isSystem: Boolean get() = systemDefined || folderName in EmailFolders.SYSTEM

    /** The last segment — what a qualified folder is actually called. */
    val shortName: String get() = folderName.substringAfterLast('.')
}

/**
 * The folder names the server uses.
 *
 * Only `INBOX` is renamed for display; the rest are shown as the server names them, which is
 * v2's behaviour and what the client's approved copy says.
 */
object EmailFolders {
    const val INBOX = "INBOX"
    const val SENT = "Sent"
    const val DRAFTS = "Drafts"
    const val TRASH = "Trash"
    const val SPAM = "Spam"
    const val JUNK = "Junk"

    val SYSTEM = setOf(INBOX, SENT, DRAFTS, TRASH, SPAM, JUNK)

    /** Folders whose read state comes from the stored flag rather than from badge rows. */
    val READ_STATE_FROM_FLAG = setOf(SENT, TRASH, JUNK)

    /** Where the row's avatar and name come from the recipient instead of the sender. */
    val SHOWS_RECIPIENT = setOf(SENT, DRAFTS)

    /**
     * What the drawer shows before the first sync answers.
     *
     * Every IMAP mailbox has these four, so listing them costs nothing and an empty drawer
     * on a cold start reads as a broken mailbox rather than one still loading.
     */
    fun defaults(): List<EmailFolder> = listOf(INBOX, SENT, DRAFTS, TRASH)
        .map { EmailFolder(folderName = it, systemDefined = true) }
}

/** A saved contact. Server-side only — the device address book is never read. */
data class EmailContact(
    val id: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val contactName: String = "",
    val emailAddress: String = "",
    val companyName: String = "",
    val phoneNumber: String = "",
    val countryCode: String = "",
    val address: String = "",
    val city: String = "",
    val state: String = "",
    val zipCode: String = "",
    val country: String = "",
    val notes: String = "",
) {
    val displayName: String
        get() = contactName.ifBlank { "$firstName $lastName".trim() }.ifBlank { emailAddress }

    val formattedPhone: String
        get() = if (phoneNumber.isBlank()) "" else "$countryCode $phoneNumber".trim()

    val formattedAddress: String
        get() = listOf(address, city, state, zipCode, country)
            .filter { it.isNotBlank() }
            .joinToString(", ")
}

data class EmailSignature(
    val id: String = "",
    val name: String = "",
    /** HTML. */
    val content: String = "",
    val useForNew: Boolean = false,
    val useForReply: Boolean = false,
)

/**
 * A named set of addresses.
 *
 * Members are **project users only** — the same restriction v2 has. Worth knowing: the
 * group resolves to the group's own mailbox address when used as a recipient, not to its
 * members, so sending to a group is one address on the wire.
 */
data class EmailGroup(
    val id: String = "",
    val groupName: String = "",
    val memberEmails: List<String> = emptyList(),
    /** The address the group itself receives on. */
    val groupEmail: String = "",
)

/** Unconditional forwarding, done at the mail-server level rather than by a rule. */
data class ForwardingSetting(
    val id: String = "",
    val forwardTo: String = "",
    val enabled: Boolean = false,
)

/** What an external mail client needs to connect. The password is fetched on demand. */
data class MailCredentials(
    val emailAddress: String = "",
    val imapHost: String = "",
    val imapPort: Int = 0,
    val smtpHost: String = "",
    val smtpPort: Int = 0,
    val username: String = "",
)

/** A candidate offered by the compose screen's recipient autocomplete. */
data class RecipientSuggestion(
    val address: String,
    val name: String,
    val source: Source,
    val pictureKey: String? = null,
    val thumbnailKey: String? = null,
) {
    /**
     * Where it came from.
     *
     * Shown in the row, and it decides precedence: a project user shadows a contact with
     * the same address, so the same person does not appear twice.
     */
    enum class Source { PROJECT_USER, CONTACT, GROUP }

    val initials: String get() = EmailAddress(address, name).initials
}
