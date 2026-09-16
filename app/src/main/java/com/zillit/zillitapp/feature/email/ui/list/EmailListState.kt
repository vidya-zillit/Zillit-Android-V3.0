package com.zillit.zillitapp.feature.email.ui.list

import androidx.compose.runtime.Immutable
import com.zillit.zillitapp.feature.email.domain.Email
import com.zillit.zillitapp.feature.email.domain.EmailAddress
import com.zillit.zillitapp.feature.email.domain.EmailFolder
import com.zillit.zillitapp.feature.email.domain.EmailFolders
import com.zillit.zillitapp.feature.email.domain.EmailThread
import com.zillit.zillitapp.feature.email.ui.EmailDates

/**
 * One row, fully resolved.
 *
 * `@Immutable` so Compose can skip a row whose data has not changed: the list re-renders on
 * every badge emission, because read state lives in the badge tree rather than in Realm, and
 * without this every one of those would re-lay-out every visible row.
 */
@Immutable
data class EmailRowState(
    val id: String,
    val threadId: String,
    val folderName: String,
    /** Who the row is about — the sender, or the recipient in Sent and Drafts. */
    val correspondent: String,
    val initials: String,
    val pictureKey: String? = null,
    val thumbnailKey: String? = null,
    val subject: String,
    val snippet: String,
    val time: String,
    val unread: Boolean,
    val threadCount: Int = 1,
    val hasAttachments: Boolean = false,
    val pending: Boolean = false,
    val isDraft: Boolean = false,
    /** Every message this row stands for. One id normally; the whole thread in conversation view. */
    val messageIds: List<String> = listOf(id),
    val moreDescription: String = "",
)

/**
 * Builds a row from a message.
 *
 * @param noSubjectLabel v2's `"(No Subject)"`, passed in rather than referenced directly so
 *   this stays a pure function and the string stays translatable.
 * @param draftLabel what stands in for the sender in Drafts, where there is no sender yet.
 */
fun Email.toRowState(
    noSubjectLabel: String,
    draftLabel: String,
    unread: Boolean = isUnread,
    threadCount: Int = 1,
    resolveAvatar: (EmailAddress) -> Pair<String?, String?> = { null to null },
): EmailRowState {
    val showRecipient = folderName in EmailFolders.SHOWS_RECIPIENT
    val party = if (showRecipient) to.firstOrNull() ?: EmailAddress("") else from

    // v2 puts the literal word "Draft" where the sender goes, because a draft's recipient
    // list is usually still empty and the row would otherwise be blank.
    val correspondent = when {
        folderName == EmailFolders.DRAFTS -> draftLabel
        party.display.isNotBlank() -> party.display
        else -> draftLabel
    }

    val (picture, thumbnail) = resolveAvatar(party)

    return EmailRowState(
        id = id,
        threadId = threadId,
        folderName = folderName,
        correspondent = correspondent,
        initials = party.initials,
        pictureKey = picture,
        thumbnailKey = thumbnail,
        subject = subject.ifBlank { noSubjectLabel },
        snippet = snippet,
        time = EmailDates.listRow(createdAt),
        unread = unread,
        threadCount = threadCount,
        hasAttachments = hasAttachments,
        pending = pending,
        isDraft = folderName == EmailFolders.DRAFTS,
        messageIds = listOf(id),
    )
}

/** Builds a row from a conversation — the latest message, with the thread's counts. */
fun EmailThread.toRowState(
    noSubjectLabel: String,
    draftLabel: String,
    resolveAvatar: (EmailAddress) -> Pair<String?, String?> = { null to null },
): EmailRowState = latest.toRowState(
    noSubjectLabel = noSubjectLabel,
    draftLabel = draftLabel,
    unread = isUnread,
    threadCount = total,
    resolveAvatar = resolveAvatar,
).copy(
    threadId = threadId,
    hasAttachments = hasAttachments,
    // Selecting one row in conversation view selects the whole conversation, so the row has
    // to carry every message it stands for — a move acts on all of them.
    messageIds = emails.map { it.id },
)

/**
 * A selection in progress.
 *
 * Capped at [LIMIT], which is v2's cap too. v2 enforces it by silently dropping the 31st
 * tap; here [isFull] lets the bar say so.
 */
@Immutable
data class EmailSelection(
    val rowIds: Set<String> = emptySet(),
) {
    val count: Int get() = rowIds.size

    val isActive: Boolean get() = rowIds.isNotEmpty()

    val isFull: Boolean get() = count >= LIMIT

    operator fun contains(rowId: String): Boolean = rowId in rowIds

    fun toggle(rowId: String): EmailSelection = when {
        rowId in rowIds -> copy(rowIds = rowIds - rowId)
        isFull -> this
        else -> copy(rowIds = rowIds + rowId)
    }

    companion object {
        const val LIMIT = 30
    }
}

/** Everything the list screen draws. */
@Immutable
data class EmailListUiState(
    val folder: EmailFolder = EmailFolder(folderName = EmailFolders.INBOX),
    val rows: List<EmailRowState> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val selection: EmailSelection = EmailSelection(),
    val conversationView: Boolean = false,
    /** Drives the dot on the filter icon. */
    val filterActive: Boolean = false,
    val offline: Boolean = false,
    /**
     * The profile carries no mailbox address for the mailbox being read.
     *
     * A brand-new project's user has an empty `mail_box_detail` until the backend
     * provisions one, and the mail service answers every call with
     * `email_credentials_not_available` until then. Nothing the app does can change it,
     * so the list says so once rather than syncing, failing and toasting on every open.
     */
    val noMailbox: Boolean = false,
) {
    val isEmpty: Boolean get() = rows.isEmpty() && !loading

    /** Trash offers "Empty Trash"; every other folder offers to clear itself. */
    val canEmptyFolder: Boolean get() = rows.isNotEmpty()

    val isTrash: Boolean get() = folder.folderName == EmailFolders.TRASH

    val isDrafts: Boolean get() = folder.folderName == EmailFolders.DRAFTS
}
