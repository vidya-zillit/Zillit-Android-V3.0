package com.zillit.zillitapp.navigation

import kotlinx.serialization.Serializable

/**
 * The email module's destinations.
 *
 * A separate file from the rest of [Route] only because there are twenty of them and the
 * main list is already long — Kotlin permits a sealed hierarchy to span files in the same
 * package, so these are ordinary `Route`s and the nav host treats them as such.
 *
 * v2 reaches all of this with `Intent`s and six `startActivityForResult` round trips, which
 * is why "move this mail to a folder" returns its answer through `onActivityResult` and the
 * list has to remember what it was doing. Here the folder picker is a destination that sets
 * a result on the back stack, and the argument types are checked at compile time.
 */

/** The email shell: the drawer, and whichever folder's list is showing. */
@Serializable
data class EmailLanding(
    /**
     * The folder to open. Defaults to the inbox, which is also what the Email tab wants —
     * but a notification tap opens the folder the mail is actually in.
     */
    val folderName: String = "INBOX",
) : Route

/** One mail, or one conversation. */
@Serializable
data class EmailDetail(
    val emailId: String,
    val folderName: String,
    /** Set when conversation view is on: the whole trail is shown, not just this message. */
    val threadId: String? = null,
) : Route

/**
 * The composer.
 *
 * Every way of reaching it is one route with different arguments, rather than v2's six
 * intent-extra combinations decoded by a `when` on a string mode.
 */
@Serializable
data class EmailCompose(
    val mode: String = MODE_NEW,
    /** The message being replied to or forwarded. */
    val sourceEmailId: String? = null,
    val sourceFolderName: String? = null,
    /** An existing draft to reopen. */
    val draftId: String? = null,
    /** Pre-filled from a share, or from "Email" on a contact. */
    val prefillTo: String? = null,
    val prefillSubject: String? = null,
    val prefillBody: String? = null,
) : Route {
    companion object {
        const val MODE_NEW = "new"
        const val MODE_REPLY = "reply"
        const val MODE_REPLY_ALL = "replyAll"
        const val MODE_FORWARD = "forward"
        const val MODE_SHARE = "share"
        const val MODE_EDIT_DRAFT = "editDraft"
    }
}

/** Local search across synced folders. */
@Serializable
data object EmailSearch : Route

/**
 * Pick a destination folder for a move.
 *
 * Returns its answer on the back-stack entry under [RESULT_FOLDER] rather than taking a
 * callback, so the list survives the process being killed behind it.
 */
@Serializable
data class EmailFolderPicker(
    val sourceFolderName: String,
) : Route {
    companion object {
        const val RESULT_FOLDER = "email_folder_picker_result"
    }
}

@Serializable
data object EmailSettings : Route

/** Addresses blind-copied on every mail this mailbox sends. */
@Serializable
data object EmailBccPresets : Route

@Serializable
data object EmailSignatures : Route

@Serializable
data class EmailSignatureEditor(val signatureId: String? = null) : Route

@Serializable
data object EmailGroups : Route

@Serializable
data class EmailGroupEditor(val groupId: String? = null) : Route

@Serializable
data object EmailContacts : Route

@Serializable
data class EmailContactEditor(
    val contactId: String? = null,
    /** "Add to Contacts" from a message arrives with the address already known. */
    val prefillEmail: String? = null,
    val prefillName: String? = null,
) : Route

@Serializable
data object EmailRules : Route

@Serializable
data class EmailRuleEditor(val ruleId: String? = null) : Route

@Serializable
data class EmailRuleExecutions(val ruleId: String, val ruleName: String) : Route

/** The main calendar, opened from the email drawer. */
@Serializable
data object EmailCalendar : Route

/** Who has opened a mail this user sent. */
@Serializable
data class EmailReadBy(val emailId: String, val folderName: String) : Route
