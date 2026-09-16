package com.zillit.zillitapp.feature.email.data

import com.zillit.zillitapp.feature.email.domain.ConditionField
import com.zillit.zillitapp.feature.email.domain.ConditionOperator
import com.zillit.zillitapp.feature.email.domain.EmailRule
import com.zillit.zillitapp.feature.email.domain.ExecutionActionResult
import com.zillit.zillitapp.feature.email.domain.ExecutionStatus
import com.zillit.zillitapp.feature.email.domain.MailboxScope
import com.zillit.zillitapp.feature.email.domain.RuleAction
import com.zillit.zillitapp.feature.email.domain.RuleActionType
import com.zillit.zillitapp.feature.email.domain.RuleCondition
import com.zillit.zillitapp.feature.email.domain.RuleExecution
import com.zillit.zillitapp.feature.email.domain.RuleMatchType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class RuleConditionDto(
    @SerialName("field") val field: String = "",
    @SerialName("operator") val operator: String = "",
    @SerialName("value") val value: String = "",
)

/**
 * An action, flat.
 *
 * The wire shape is a union discriminated by `type`, with only that type's keys populated —
 * so this DTO has every key and the mapping below decides which are meaningful.
 */
@Serializable
data class RuleActionDto(
    @SerialName("type") val type: String = "",
    @SerialName("drive_folder_id") val driveFolderId: String = "",
    @SerialName("drive_folder_name") val driveFolderName: String = "",
    @SerialName("extensions") val extensions: List<String> = emptyList(),
    @SerialName("max_size_bytes") val maxSizeBytes: Long = 0,
    @SerialName("folder_name") val folderName: String = "",
    @SerialName("forward_to_email") val forwardToEmail: String = "",
)

@Serializable
data class EmailRuleDto(
    @SerialName("_id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("priority") val priority: Int = 0,
    @SerialName("stop_on_match") val stopOnMatch: Boolean = false,
    @SerialName("match_type") val matchType: String = "all",
    @SerialName("rule_scope") val scope: String = "personal",
    @SerialName("conditions") val conditions: List<RuleConditionDto> = emptyList(),
    @SerialName("actions") val actions: List<RuleActionDto> = emptyList(),
    @SerialName("created") val created: Long = 0,
    @SerialName("updated") val updated: Long = 0,
)

@Serializable
data class RuleListDto(@SerialName("rules") val rules: List<EmailRuleDto> = emptyList())

@Serializable
data class RuleWrapperDto(@SerialName("rule") val rule: EmailRuleDto? = null)

@Serializable
data class ReorderRulesRequest(@SerialName("rule_ids") val ruleIds: List<String>)

@Serializable
data class ExecutionResultDto(
    @SerialName("type") val type: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("detail") val detail: String = "",
)

@Serializable
data class RuleExecutionDto(
    @SerialName("_id") val id: String = "",
    @SerialName("rule_id") val ruleId: String = "",
    @SerialName("mailbox_email") val mailboxEmail: String = "",
    @SerialName("message_id") val messageId: String = "",
    @SerialName("subject") val subject: String = "",
    @SerialName("uid") val uid: Int = 0,
    @SerialName("status") val status: String = "pending",
    @SerialName("attempts") val attempts: Int = 0,
    @SerialName("next_retry_at") val nextRetryAt: Long = 0,
    @SerialName("results") val results: List<ExecutionResultDto> = emptyList(),
    @SerialName("error") val error: String = "",
    @SerialName("created") val created: Long = 0,
)

@Serializable
data class RuleExecutionPageDto(
    @SerialName("executions") val executions: List<RuleExecutionDto> = emptyList(),
    @SerialName("total") val total: Int = 0,
    @SerialName("limit") val limit: Int = 25,
    @SerialName("skip") val skip: Int = 0,
)

// ── Wire → domain ────────────────────────────────────────────────────────────

/**
 * Reads a rule, dropping anything this client does not understand.
 *
 * `mapNotNull` on conditions and actions is deliberate: the service will grow operators and
 * action types before this app does, and losing one condition off a rule is far better than
 * failing the whole list and leaving the user with no rules screen at all.
 */
fun EmailRuleDto.toDomain(): EmailRule = EmailRule(
    id = id,
    name = name,
    enabled = enabled,
    priority = priority,
    stopOnMatch = stopOnMatch,
    matchType = RuleMatchType.from(matchType),
    conditions = conditions.mapNotNull { it.toDomain() },
    actions = actions.mapNotNull { it.toDomain() },
    scope = if (scope == MailboxScope.SHARED.key) MailboxScope.SHARED else MailboxScope.PERSONAL,
    createdAt = created,
    updatedAt = updated,
)

private fun RuleConditionDto.toDomain(): RuleCondition? {
    val parsedField = ConditionField.from(field) ?: return null
    val parsedOperator = ConditionOperator.from(operator) ?: return null
    // An operator the field does not accept is a rule this client cannot represent
    // faithfully; dropping it is safer than coercing it into something else.
    if (parsedOperator !in parsedField.operators) return null

    return RuleCondition(parsedField, parsedOperator, value)
}

private fun RuleActionDto.toDomain(): RuleAction? = when (RuleActionType.from(type)) {
    RuleActionType.SAVE_ATTACHMENTS_TO_DRIVE -> RuleAction.SaveAttachmentsToDrive(
        driveFolderId = driveFolderId,
        driveFolderName = driveFolderName,
        extensions = extensions,
        maxSizeBytes = maxSizeBytes,
    )

    RuleActionType.MOVE_TO_FOLDER -> RuleAction.MoveToFolder(folderName)
    RuleActionType.FORWARD_TO -> RuleAction.ForwardTo(forwardToEmail)
    RuleActionType.MARK_READ -> RuleAction.MarkRead
    null -> null
}

fun RuleExecutionDto.toDomain(): RuleExecution = RuleExecution(
    id = id,
    ruleId = ruleId,
    mailboxEmail = mailboxEmail,
    messageId = messageId,
    subject = subject,
    uid = uid,
    status = ExecutionStatus.from(status),
    attempts = attempts,
    nextRetryAt = nextRetryAt,
    results = results.map { ExecutionActionResult(it.type, it.status, it.detail) },
    error = error,
    createdAt = created,
)

// ── Domain → wire ────────────────────────────────────────────────────────────

/**
 * Builds a rule's create body.
 *
 * Assembled as JSON rather than serialized from a data class because the schemas are
 * **strict**: a `folder_name` key on a `forward_to` action is rejected outright, and a
 * `value` on a `has_attachment` condition likewise. A DTO with every optional field would
 * emit the nulls; building only the keys that belong is the contract.
 *
 * `priority` is deliberately absent — the server appends a new rule to the end, and sending
 * a position would fight the reorder endpoint.
 *
 * @param accountsMailbox writes `use_project_account_mailbox` into the **body** as the
 *   string `"true"`. Rule writes are the one exception to the query-parameter rule, and
 *   `"true"` rather than `true` is what the service accepts.
 */
fun EmailRule.toCreateBody(accountsMailbox: Boolean): JsonObject = buildJsonObject {
    put("name", name.trim())
    put("enabled", enabled)
    put("match_type", matchType.key)
    put("stop_on_match", stopOnMatch)
    put("conditions", conditionsJson())
    put("actions", actionsJson())
    if (accountsMailbox) put("use_project_account_mailbox", "true")
}

/**
 * A **partial** update — only the keys given.
 *
 * The list's enable switch sends literally `{"enabled": false}`. Sending the whole rule
 * there would make a toggle capable of overwriting an edit made on another device between
 * the list loading and the switch being tapped.
 */
fun EmailRule.toUpdateBody(
    accountsMailbox: Boolean,
    includeName: Boolean = false,
    includeEnabled: Boolean = false,
    includeConditions: Boolean = false,
    includeActions: Boolean = false,
    includeMatchType: Boolean = false,
    includeStopOnMatch: Boolean = false,
): JsonObject = buildJsonObject {
    if (includeName) put("name", name.trim())
    if (includeEnabled) put("enabled", enabled)
    if (includeMatchType) put("match_type", matchType.key)
    if (includeStopOnMatch) put("stop_on_match", stopOnMatch)
    if (includeConditions) put("conditions", conditionsJson())
    if (includeActions) put("actions", actionsJson())
    if (accountsMailbox) put("use_project_account_mailbox", "true")
}

private fun EmailRule.conditionsJson() = buildJsonArray {
    conditions.forEach { condition ->
        add(
            buildJsonObject {
                put("field", condition.field.key)
                put("operator", condition.operator.key)
                // Omitted entirely for `always` and `has_attachment`; the API rejects a
                // value on a field that takes none.
                if (condition.field.needsValue) put("value", condition.value.trim())
            },
        )
    }
}

private fun EmailRule.actionsJson() = buildJsonArray {
    actions.forEach { action ->
        add(
            buildJsonObject {
                put("type", action.type.key)

                when (action) {
                    is RuleAction.SaveAttachmentsToDrive -> {
                        put("drive_folder_id", action.driveFolderId)
                        val extensions = action.normalisedExtensions()
                        if (extensions.isNotEmpty()) {
                            put(
                                "extensions",
                                buildJsonArray { extensions.forEach { add(JsonPrimitive(it)) } },
                            )
                        }
                        // Zero means "no limit", which the service expresses by absence.
                        if (action.maxSizeBytes > 0) put("max_size_bytes", action.maxSizeBytes)
                    }

                    is RuleAction.MoveToFolder -> put("folder_name", action.folderName)
                    is RuleAction.ForwardTo -> put("forward_to_email", action.email.trim())
                    RuleAction.MarkRead -> Unit
                }
            },
        )
    }
}
