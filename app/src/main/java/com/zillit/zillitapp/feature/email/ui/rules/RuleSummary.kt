package com.zillit.zillitapp.feature.email.ui.rules

import android.content.Context
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.labels.isLabelKey
import com.zillit.zillitapp.core.labels.ServerDictionaries
import com.zillit.zillitapp.feature.email.domain.ConditionField
import com.zillit.zillitapp.feature.email.domain.ConditionOperator
import com.zillit.zillitapp.feature.email.domain.EmailRule
import com.zillit.zillitapp.feature.email.domain.RuleAction
import com.zillit.zillitapp.feature.email.domain.RuleActionType
import com.zillit.zillitapp.feature.email.domain.RuleMatchType

/**
 * Turning a rule into a sentence.
 *
 * A rule is a set of conditions and a list of actions, and neither is legible as a data
 * structure. Every surface that shows a rule — the list row, the editor's live readback,
 * the run history — needs the same translation, so it lives in one place.
 *
 * Takes a [Context] rather than being `@Composable` so the same text can be produced for a
 * notification or a log line.
 */
object RuleSummary {

    /** "If the sender contains acme.com → Move to folder" — the list row. */
    fun row(context: Context, rule: EmailRule): String {
        val joiner = context.getString(
            if (rule.matchType == RuleMatchType.ANY) {
                R.string.email_rule_joiner_or
            } else {
                R.string.email_rule_joiner_and
            },
        )

        val conditions = rule.conditions
            .joinToString(" $joiner ") { condition(context, it.field, it.operator, it.value) }
            .ifBlank { context.getString(R.string.email_rule_summary_no_conditions) }

        val actions = rule.actions
            .joinToString(", ") { actionLabel(context, it) }
            .ifBlank { context.getString(R.string.email_rule_summary_no_actions) }

        return context.getString(R.string.email_rule_summary_format, conditions, actions)
    }

    /**
     * The editor's live readback — "When an email arrives and the subject contains invoice."
     *
     * Recomputed on every keystroke. It is the only thing in the editor that says what the
     * rule will actually *do*, since the three fields alone read as a search form.
     */
    fun readback(
        context: Context,
        from: String,
        subject: String,
        hasWords: String,
        hasAttachment: Boolean,
    ): String {
        val parts = buildList {
            if (from.isNotBlank()) {
                add(context.getString(R.string.email_rule_readback_from, from))
            }
            if (subject.isNotBlank()) {
                add(context.getString(R.string.email_rule_readback_subject, subject))
            }
            if (hasWords.isNotBlank()) {
                add(context.getString(R.string.email_rule_readback_words, hasWords))
            }
            if (hasAttachment) {
                add(context.getString(R.string.email_rule_readback_attachment))
            }
        }

        return if (parts.isEmpty()) {
            context.getString(R.string.email_rule_readback_empty)
        } else {
            context.getString(R.string.email_rule_readback_format, parts.joinToString(", "))
        }
    }

    fun actionLabel(context: Context, action: RuleAction): String =
        actionTypeLabel(context, action.type)

    /**
     * The name of an action kind, from the type key alone.
     *
     * The run history has only the key — there is no action object on a past run — so this
     * takes the type rather than the action. Without it the history read
     * "save_attachments_to_drive: …" instead of "Save attachments to Drive: …".
     */
    fun actionTypeLabel(context: Context, type: RuleActionType?): String = when (type) {
        RuleActionType.SAVE_ATTACHMENTS_TO_DRIVE ->
            context.getString(R.string.email_rule_action_save_to_drive)

        RuleActionType.MOVE_TO_FOLDER -> context.getString(R.string.email_rule_action_move_to_folder)
        RuleActionType.FORWARD_TO -> context.getString(R.string.email_rule_action_forward_to)
        RuleActionType.MARK_READ -> context.getString(R.string.email_rule_action_mark_read)
        null -> ""
    }

    /**
     * Resolves a run's `detail` into something a person can act on.
     *
     * The server sends i18n keys for the failures it expects. Anything unrecognised is shown
     * as it arrived rather than swallowed — an unexplained failure is worse than an ugly one.
     */
    fun executionDetail(
        context: Context,
        detail: String,
        labels: ServerDictionaries = ServerDictionaries(),
    ): String = when (detail) {
        "email_rule_drive_folder_not_found", "email_rule_drive_folder_required" ->
            context.getString(R.string.email_rule_exec_drive_folder_missing)

        "email_rule_action_folder_required", "email_rule_folder_not_found" ->
            context.getString(R.string.email_rule_exec_folder_missing)

        "email_rule_attachment_empty" ->
            context.getString(R.string.email_rule_exec_attachment_empty)

        "email_rule_too_many_duplicate_file_names" ->
            context.getString(R.string.email_rule_exec_duplicate_names)

        "email_rule_forward_email_required" ->
            context.getString(R.string.email_rule_exec_forward_invalid)

        // Anything else that looks like a key goes through the server dictionary, then
        // through the humaniser. Free text the server wrote passes straight through.
        else -> if (detail.isLabelKey()) labels.resolve(detail) else detail
    }

    private fun condition(
        context: Context,
        field: ConditionField,
        operator: ConditionOperator,
        value: String,
    ): String = when (field) {
        ConditionField.HAS_ATTACHMENT -> context.getString(
            R.string.email_rule_readback_attachment,
        )

        ConditionField.ALWAYS -> context.getString(R.string.email_rule_summary_no_conditions)

        ConditionField.FROM, ConditionField.FROM_DOMAIN ->
            context.getString(R.string.email_rule_readback_from, value)

        ConditionField.SUBJECT ->
            context.getString(R.string.email_rule_readback_subject, value)

        ConditionField.BODY, ConditionField.SUBJECT_OR_BODY ->
            context.getString(R.string.email_rule_readback_words, value)
    }
}
