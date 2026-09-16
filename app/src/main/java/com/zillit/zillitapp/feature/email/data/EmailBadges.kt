package com.zillit.zillitapp.feature.email.data

import com.zillit.zillitapp.core.badge.BadgeAxis
import com.zillit.zillitapp.core.badge.BadgeKey
import com.zillit.zillitapp.core.badge.BadgeManager
import com.zillit.zillitapp.core.badge.BadgeSection
import com.zillit.zillitapp.core.notification.NotificationRepository
import com.zillit.zillitapp.feature.email.domain.EmailFolders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Email's read state.
 *
 * **Read state is the notification tree, not a flag on the message.** For every folder but
 * Sent, Trash and Junk there is no read flag on the wire and no endpoint to set one: a mail
 * is unread because an unread notification names it, and reading it marks that notification.
 * That is how read state reaches a user's other devices without an IMAP flag — and it is why
 * the list has to re-render when notifications change, since Realm's mail rows do not.
 *
 * ### What the server actually sends
 * Verified against the live payload, and it is **not** what v2's code assumes:
 *
 * ```
 * section     = "email_label"
 * tool        = "default_tool"
 * unit        = "INBOX"                              ← the folder
 * reference_id= "16"                                 ← the UID
 * level_1     = "someone.project@zillit.net"         ← the MAILBOX ADDRESS
 * level_2/3   = null
 * ```
 *
 * v2's `EmailReadState` matches the uid against `level_1` and the mailbox against `level_3`,
 * so **every one of its per-message read checks fails** and mail stays bold until the folder
 * is re-synced. (Its mailbox-count pill matches the mailbox against `level_1` and is the one
 * that was right; the module's own audit records the two as contradicting each other without
 * establishing which way round the server actually is.)
 *
 * Aggregate counts come from the badge tree, which is keyed by path and correct — `unit` is
 * the folder. Per-message state cannot: a badge row is a **count** for a whole path, so the
 * individual uids are not recoverable from it. Those come from the notifications the badges
 * were computed from.
 */
@Singleton
class EmailBadges @Inject constructor(
    private val badgeManager: BadgeManager,
    private val notifications: NotificationRepository,
    private val mailbox: EmailMailboxContext,
) {

    /** The Email tab's own badge — every unread message in the project. */
    fun observeTotal(projectId: String): Flow<Int> =
        badgeManager.observeUnder(section(projectId))

    /**
     * Unread per folder, for the drawer. Keyed by folder name, which is the badge `unit`.
     *
     * Scoped to the open mailbox. The badge tree groups by unit but cannot filter by the
     * mailbox below it, so a project with a shared mailbox had both mailboxes' unread added
     * into one set of folder counts.
     */
    fun observeFolderCounts(projectId: String): Flow<Map<String, Int>> =
        notifications.observeUnreadCountsByUnit(projectId, BadgeSection.EMAIL, mailboxFilter())

    /**
     * The uids still unread in a folder.
     *
     * The list combines this with its rows, so opening a mail on a laptop un-bolds it here
     * without the folder being re-fetched.
     */
    fun observeUnreadUids(projectId: String, folderName: String): Flow<Set<String>> =
        notifications.observeUnreadReferences(
            projectId = projectId,
            section = BadgeSection.EMAIL,
            unit = folderName,
            level1 = mailboxFilter(),
        )

    /**
     * Every unread uid in the mailbox, whatever folder it is in.
     *
     * Conversation counts span folders, so a reply filed away by a rule still counts
     * towards the thread's unread total in the inbox.
     */
    fun observeAllUnreadUids(projectId: String): Flow<Set<String>> =
        notifications.observeUnreadReferences(
            projectId = projectId,
            section = BadgeSection.EMAIL,
            unit = null,
            level1 = mailboxFilter(),
        )

    /**
     * The mailbox to scope unread by, or null when the project has only one.
     *
     * Null rather than the personal address in the single-mailbox case: with nothing to
     * disambiguate, filtering could only lose rows the server tagged differently.
     */
    private fun mailboxFilter(): String? =
        mailbox.activeAddress?.takeIf { mailbox.entitled.value && it.isNotBlank() }

    /**
     * Unread per mailbox address, for the mailbox chooser's pills.
     *
     * Scoped to the inbox: the chooser answers "is there new mail over there", and counting
     * a mailbox's Trash into that number would be actively misleading.
     */
    fun observeMailboxCounts(projectId: String): Flow<Map<String, Int>> =
        notifications.observeUnreadByLevel1(projectId, BadgeSection.EMAIL, EmailFolders.INBOX)

    /**
     * Marks one message read.
     *
     * Keyed on the uid, which is the notification's `reference_id`. Recomputing the badges
     * is what makes the folder count and the tab badge follow.
     */
    suspend fun markRead(uid: Int) {
        notifications.markReadByReference(uid.toString())
    }

    /** Marks a whole folder read — what Empty Trash and "clear folder" imply. */
    suspend fun markFolderRead(projectId: String, folderName: String) {
        notifications.markRead(
            BadgeKey(projectId = projectId, section = BadgeSection.EMAIL, unit = folderName),
        )
    }

    private fun section(projectId: String) =
        BadgeKey(projectId = projectId, section = BadgeSection.EMAIL)
}
