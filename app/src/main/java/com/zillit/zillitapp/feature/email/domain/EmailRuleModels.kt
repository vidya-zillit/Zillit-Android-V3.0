package com.zillit.zillitapp.feature.email.domain

/**
 * The inbound rule engine's models.
 *
 * Ported from v2 largely unchanged, because unlike the rest of the module these were written
 * from a backend spec rather than reverse-engineered — the limits, operator sets and action
 * schemas here are the server's, and diverging from them just moves a rejection later.
 *
 * What v2 got wrong was not the model but the **editor**: it can only ever produce three
 * condition shapes (From, Subject, Has-the-words) with `contains`, so a rule using anything
 * else opens read-only and cannot be edited on a phone at all. v3's editor drives the model
 * it actually has.
 */
data class EmailRule(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    /** Ascending run order. Array position **is** priority. */
    val priority: Int = 0,
    val stopOnMatch: Boolean = false,
    val matchType: RuleMatchType = RuleMatchType.ALL,
    val conditions: List<RuleCondition> = emptyList(),
    val actions: List<RuleAction> = emptyList(),
    val scope: MailboxScope = MailboxScope.PERSONAL,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /**
     * An empty condition list is **rejected**, not treated as a catch-all: a half-built rule
     * must never quietly start acting on every message. [ConditionField.ALWAYS] is the
     * explicit way to say "everything".
     */
    val isValid: Boolean
        get() = name.trim().isNotEmpty() &&
            name.trim().length <= NAME_MAX_LENGTH &&
            conditions.isNotEmpty() && conditions.size <= MAX_CONDITIONS &&
            conditions.all { it.isValid } &&
            actions.isNotEmpty() && actions.size <= MAX_ACTIONS &&
            actions.all { it.isValid }

    companion object {
        const val MAX_RULES_PER_MAILBOX = 50
        const val MAX_CONDITIONS = 20
        const val MAX_ACTIONS = 10
        const val NAME_MAX_LENGTH = 120
        const val VALUE_MAX_LENGTH = 512
        const val FOLDER_NAME_MAX_LENGTH = 255
        const val MAX_EXTENSIONS = 25
        const val EXTENSION_MAX_LENGTH = 16

        /** 1 GiB. Zero means no limit. */
        const val MAX_ATTACHMENT_SIZE_BYTES = 1024L * 1024L * 1024L
    }
}

/** How several conditions combine. */
enum class RuleMatchType(val key: String) {
    /** AND. */
    ALL("all"),

    /** OR. */
    ANY("any");

    companion object {
        fun from(key: String?): RuleMatchType =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: ALL
    }
}

/**
 * Operators. Text fields and boolean fields take **disjoint** sets — see
 * [ConditionField.operators] — and a mismatched pair is rejected outright by the API.
 */
enum class ConditionOperator(val key: String) {
    IS("is"),
    IS_NOT("is_not"),
    CONTAINS("contains"),
    NOT_CONTAINS("not_contains"),
    STARTS_WITH("starts_with"),
    ENDS_WITH("ends_with"),
    IS_TRUE("is_true"),
    IS_FALSE("is_false");

    companion object {
        val TEXT_OPERATORS = listOf(IS, IS_NOT, CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH)

        fun from(key: String?): ConditionOperator? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/**
 * The field a condition tests.
 *
 * [operators] hangs off the field on purpose: "changing the field resets the operator" then
 * falls out of the model instead of being a rule the editor has to remember.
 */
enum class ConditionField(
    val key: String,
    val operators: List<ConditionOperator>,
    val needsValue: Boolean,
) {
    ALWAYS("always", listOf(ConditionOperator.IS_TRUE), needsValue = false),
    FROM("from", ConditionOperator.TEXT_OPERATORS, needsValue = true),
    FROM_DOMAIN("from_domain", ConditionOperator.TEXT_OPERATORS, needsValue = true),
    SUBJECT("subject", ConditionOperator.TEXT_OPERATORS, needsValue = true),
    BODY("body", ConditionOperator.TEXT_OPERATORS, needsValue = true),
    SUBJECT_OR_BODY("subject_or_body", ConditionOperator.TEXT_OPERATORS, needsValue = true),
    HAS_ATTACHMENT(
        "has_attachment",
        listOf(ConditionOperator.IS_TRUE, ConditionOperator.IS_FALSE),
        needsValue = false,
    );

    /**
     * Forces a full message download for every inbound mail, so it is worth warning about
     * and should never be a default.
     */
    val isExpensive: Boolean get() = this == BODY || this == SUBJECT_OR_BODY

    val defaultOperator: ConditionOperator get() = operators.first()

    companion object {
        fun from(key: String?): ConditionField? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

data class RuleCondition(
    val field: ConditionField = ConditionField.FROM,
    val operator: ConditionOperator = ConditionOperator.CONTAINS,
    val value: String = "",
) {
    // `this.field` is required: inside a property accessor a bare `field` is the backing
    // field of `isValid`, not this class's property.
    val isValid: Boolean
        get() = operator in this.field.operators &&
            (
                !this.field.needsValue ||
                    (value.isNotBlank() && value.length <= EmailRule.VALUE_MAX_LENGTH)
                )

    /** Keeps the pair legal when the field changes. */
    fun withField(newField: ConditionField): RuleCondition = RuleCondition(
        field = newField,
        operator = if (operator in newField.operators) operator else newField.defaultOperator,
        value = if (newField.needsValue) value else "",
    )
}

/**
 * An action.
 *
 * Sealed so "send only the keys this type takes" is the type system's job — the API schemas
 * are strict, and a stray `folder_name` on a `forward_to` is rejected.
 */
sealed interface RuleAction {

    val type: RuleActionType

    val isValid: Boolean

    /**
     * Files the message's attachments into a Drive folder.
     *
     * [driveFolderId] is checked at save time against real Drive folders in the project, so
     * it has to come from the picker and can never be typed.
     */
    data class SaveAttachmentsToDrive(
        val driveFolderId: String = "",
        val driveFolderName: String = "",
        /** Lower-case, no leading dot. Normalised server-side too. */
        val extensions: List<String> = emptyList(),
        /** Zero means no limit. */
        val maxSizeBytes: Long = 0,
    ) : RuleAction {
        override val type = RuleActionType.SAVE_ATTACHMENTS_TO_DRIVE

        override val isValid: Boolean
            get() = driveFolderId.isNotBlank() &&
                extensions.size <= EmailRule.MAX_EXTENSIONS &&
                extensions.all { it.length <= EmailRule.EXTENSION_MAX_LENGTH } &&
                maxSizeBytes in 0..EmailRule.MAX_ATTACHMENT_SIZE_BYTES

        fun normalisedExtensions(): List<String> = extensions
            .map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    data class MoveToFolder(val folderName: String = "") : RuleAction {
        override val type = RuleActionType.MOVE_TO_FOLDER

        override val isValid: Boolean
            get() = folderName.isNotBlank() &&
                folderName.length <= EmailRule.FOLDER_NAME_MAX_LENGTH
    }

    /**
     * Forwards the message.
     *
     * For plain unconditional forwarding with no other action, the forwarding **setting** is
     * the better tool — it forwards at the mail-server level, so it keeps working when the
     * API is down and costs nothing per message. This action is for forwarding combined with
     * something else.
     */
    data class ForwardTo(val email: String = "") : RuleAction {
        override val type = RuleActionType.FORWARD_TO

        override val isValid: Boolean get() = email.trim().isPlausibleEmail()
    }

    /** Marks it read. Nothing to configure. */
    data object MarkRead : RuleAction {
        override val type = RuleActionType.MARK_READ

        override val isValid: Boolean get() = true
    }
}

enum class RuleActionType(val key: String) {
    SAVE_ATTACHMENTS_TO_DRIVE("save_attachments_to_drive"),
    MOVE_TO_FOLDER("move_to_folder"),
    FORWARD_TO("forward_to"),
    MARK_READ("mark_read");

    /** A blank action of this type, for when the user picks a type in the editor. */
    fun empty(): RuleAction = when (this) {
        SAVE_ATTACHMENTS_TO_DRIVE -> RuleAction.SaveAttachmentsToDrive()
        MOVE_TO_FOLDER -> RuleAction.MoveToFolder()
        FORWARD_TO -> RuleAction.ForwardTo()
        MARK_READ -> RuleAction.MarkRead
    }

    companion object {
        fun from(key: String?): RuleActionType? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** One row per rule × message — the answer to "did my rule actually fire?". */
data class RuleExecution(
    val id: String = "",
    val ruleId: String = "",
    val mailboxEmail: String = "",
    val messageId: String = "",
    val subject: String = "",
    val uid: Int = 0,
    val status: ExecutionStatus = ExecutionStatus.PENDING,
    val attempts: Int = 0,
    /** Epoch millis; zero means nothing is scheduled. */
    val nextRetryAt: Long = 0,
    val results: List<ExecutionActionResult> = emptyList(),
    val error: String = "",
    val createdAt: Long = 0,
) {
    /**
     * A failed run retries with backoff up to five times. While one is scheduled the screen
     * can honestly say "retrying"; once nothing is scheduled and it still has not succeeded,
     * it is over and somebody has to look at it.
     */
    val isRetrying: Boolean get() = nextRetryAt > 0 && status != ExecutionStatus.SUCCESS

    val needsAttention: Boolean
        get() = !isRetrying &&
            (status == ExecutionStatus.FAILED || status == ExecutionStatus.PARTIAL)
}

enum class ExecutionStatus(val key: String) {
    PENDING("pending"),
    SUCCESS("success"),

    /** Some actions worked and others did not — show the per-action results, not a verdict. */
    PARTIAL("partial"),
    FAILED("failed");

    companion object {
        fun from(key: String?): ExecutionStatus =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: PENDING
    }
}

/**
 * One action's outcome inside a run.
 *
 * "skipped" is a **normal** outcome, not a failure — no attachments, nothing matched the
 * extension filter, or a forward the loop guard suppressed. [detail] says which.
 */
data class ExecutionActionResult(
    val type: String = "",
    val status: String = "",
    val detail: String = "",
) {
    val isSkipped: Boolean get() = status.equals(STATUS_SKIPPED, ignoreCase = true)
    val isFailed: Boolean get() = status.equals(STATUS_FAILED, ignoreCase = true)

    companion object {
        const val STATUS_SKIPPED = "skipped"
        const val STATUS_FAILED = "failed"
    }
}

/**
 * A Drive folder, as the rule editor's picker sees it.
 *
 * [canEdit] rides on the folder rather than being tracked separately, because the picker
 * needs it per row *and* for the folder currently open — a view-only folder can still be
 * browsed, since its children may well be writable, but it cannot be selected.
 */
data class RuleDriveFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val hasChildren: Boolean = false,
    val canEdit: Boolean = true,
)
