package com.zillit.zillitapp.feature.email.ui.rules

import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.email.domain.EmailRule
import com.zillit.zillitapp.feature.email.domain.RuleAction
import com.zillit.zillitapp.feature.email.domain.isPlausibleEmail

/**
 * Why a rule cannot be saved yet.
 *
 * An ordered list, checked first-match-wins, because telling somebody about four problems at
 * once means they fix none of them. [field] is what the editor scrolls to and reddens.
 */
enum class RuleIssue(val messageRes: Int, val field: RuleField) {
    NAME_REQUIRED(R.string.email_rule_validation_name, RuleField.NAME),
    NAME_TOO_LONG(R.string.email_rule_validation_name_long, RuleField.NAME),
    CONDITION_REQUIRED(R.string.email_rule_validation_condition, RuleField.CONDITIONS),
    FROM_INVALID(R.string.email_rule_validation_from_invalid, RuleField.FROM),
    VALUE_TOO_LONG(R.string.email_rule_validation_value_long, RuleField.CONDITIONS),
    ACTION_REQUIRED(R.string.email_rule_validation_action, RuleField.ACTIONS),
    ACTION_INCOMPLETE(R.string.email_rule_validation_action_config, RuleField.ACTIONS),
    DRIVE_FOLDER_MISSING(R.string.email_rule_validation_drive_missing, RuleField.ACTIONS),
    EMAIL_FOLDER_MISSING(R.string.email_rule_validation_folder_missing, RuleField.ACTIONS),
}

/** Where an issue lives, so the editor can point at it. */
enum class RuleField { NAME, FROM, CONDITIONS, ACTIONS }

/**
 * The editor's draft, in the three-condition shape the screen can actually express.
 *
 * v2 has the same limitation — From, Subject, and Has-the-words, always `contains` — but
 * models it as a full condition list and then refuses to open anything it cannot round-trip.
 * Keeping the draft in this shape makes the conversion explicit in one place.
 */
data class RuleDraft(
    val name: String = "",
    val from: String = "",
    val subject: String = "",
    val hasWords: String = "",
    val hasAttachment: Boolean = false,
    val actions: List<RuleAction> = emptyList(),
    /**
     * True when the loaded rule uses options this screen cannot show.
     *
     * Read-only rather than silently simplified: opening a rule that matches on `from_domain`
     * and saving it as a `from` rule would change what it does without telling anybody.
     */
    val readOnly: Boolean = false,
    /** The rule matches ANY condition rather than all — preserved on save, not editable. */
    val matchesAny: Boolean = false,
    val missingDriveFolderIds: Set<String> = emptySet(),
    val missingFolderNames: Set<String> = emptySet(),
) {
    val hasAnyCondition: Boolean
        get() = from.isNotBlank() || subject.isNotBlank() || hasWords.isNotBlank() || hasAttachment

    /**
     * Whether to nudge the user towards the forwarding *setting* instead.
     *
     * A rule whose only action is a plain forward, with nothing to match on, is exactly what
     * the mail-server-level setting does — and that one keeps working when this service does
     * not.
     */
    val shouldSuggestForwardingSetting: Boolean
        get() = !hasAnyCondition && actions.size == 1 && actions.first() is RuleAction.ForwardTo

    /** The first thing stopping a save, or null when there is nothing. */
    fun firstIssue(): RuleIssue? = when {
        name.isBlank() -> RuleIssue.NAME_REQUIRED

        name.trim().length > EmailRule.NAME_MAX_LENGTH -> RuleIssue.NAME_TOO_LONG

        !hasAnyCondition -> RuleIssue.CONDITION_REQUIRED

        from.isNotBlank() && !isValidSender(from) -> RuleIssue.FROM_INVALID

        listOf(from, subject, hasWords).any {
            it.trim().length > EmailRule.VALUE_MAX_LENGTH
        } -> RuleIssue.VALUE_TOO_LONG

        actions.isEmpty() -> RuleIssue.ACTION_REQUIRED

        actions.any { !it.isValid } -> RuleIssue.ACTION_INCOMPLETE

        actions.any {
            it is RuleAction.SaveAttachmentsToDrive && it.driveFolderId in missingDriveFolderIds
        } -> RuleIssue.DRIVE_FOLDER_MISSING

        actions.any {
            it is RuleAction.MoveToFolder && it.folderName in missingFolderNames
        } -> RuleIssue.EMAIL_FOLDER_MISSING

        else -> null
    }
}

/**
 * Is this a usable sender match — a full address, or a bare domain?
 *
 * Stricter than the forward-to field on purpose, because this one is matched against every
 * incoming message: `accounts` as a sender match would be a rule that quietly never fires,
 * and the user would have no way to tell it apart from one that simply had no mail yet.
 *
 * Rejects `accounts`, `bob@`, `@acme.com`, `bob@acme`, `.acme.com` and `acme..com`.
 */
fun isValidSender(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return true
    if (!trimmed.all { it.isLetterOrDigit() || it in ALLOWED_SENDER_CHARS }) return false
    if (trimmed.count { it == '@' } > 1) return false

    return if ('@' in trimmed) {
        trimmed.isPlausibleEmail()
    } else {
        DOMAIN.matches(trimmed)
    }
}

private const val ALLOWED_SENDER_CHARS = "._%+-@"

private val DOMAIN = Regex(
    """^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*\.[A-Za-z]{2,}$""",
)
