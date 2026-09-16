package com.zillit.zillitapp.feature.email.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.RuleAction
import com.zillit.zillitapp.feature.email.domain.RuleActionType

/**
 * Create or edit a rule — v2's `activity_edit_email_rule`.
 *
 * The three condition rows are not a simplification of the model, they *are* what this
 * screen can express: From, Subject and Has-the-words, always `contains`, combined with AND,
 * plus a has-attachment checkbox. A rule using anything else opens read-only rather than
 * being quietly rewritten — see [RuleDraft.readOnly].
 *
 * The readback under the conditions is the one line that says what the rule will actually
 * do. Without it, three text fields read as a search form.
 */
@Composable
fun EditEmailRuleScreen(
    draft: RuleDraft,
    isNew: Boolean,
    saving: Boolean,
    loading: Boolean,
    /** Set after a failed save, so the offending field is reddened rather than only toasted. */
    issue: RuleIssue?,
    folderNames: List<String>,
    senderSuggestions: List<String>,
    onDraftChange: (RuleDraft) -> Unit,
    onAddAction: (RuleActionType) -> Unit,
    onPickDriveFolder: (Int) -> Unit,
    onCreateFolder: (Int) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showAddAction by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitTopBar(
                title = stringResource(
                    if (isNew) R.string.email_rule_create else R.string.email_rule_edit_title,
                ),
                onBackClick = onBack,
                onHelpClick = null,
            )
        },
    ) { padding ->
        if (loading || saving) {
            LoadingOverlay(
                message = stringResource(
                    if (saving) R.string.email_rule_saving else R.string.email_rule_loading,
                ),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        Column(modifier = Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                if (draft.readOnly || draft.matchesAny) {
                    NoticeBox(
                        text = stringResource(
                            if (draft.readOnly) {
                                R.string.email_rule_advanced_readonly
                            } else {
                                R.string.email_rule_matches_any_notice
                            },
                        ),
                    )
                }

                SectionTitle(stringResource(R.string.email_rule_section_rule))

                FormTextField(
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it)) },
                    label = stringResource(R.string.email_rule_hint_name),
                    placeholder = stringResource(R.string.email_rule_hint_name),
                    enabled = !draft.readOnly,
                    errorText = issue
                        ?.takeIf { it.field == RuleField.NAME }
                        ?.let { stringResource(it.messageRes) },
                )

                SectionTitle(stringResource(R.string.email_rule_section_conditions))
                Text(
                    text = stringResource(R.string.email_rule_section_when),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )

                val conditionError = issue
                    ?.takeIf { it.field == RuleField.CONDITIONS }
                    ?.let { stringResource(it.messageRes) }

                // Senders the mailbox has actually heard from, offered as you type. The
                // list was loaded and passed in but never rendered, so the field behaved
                // as a plain text box.
                var senderFocused by remember { mutableStateOf(false) }
                val matches = remember(draft.from, senderSuggestions) {
                    if (draft.from.isBlank()) {
                        emptyList()
                    } else {
                        senderSuggestions
                            .filter { it.contains(draft.from, ignoreCase = true) && it != draft.from }
                            .take(SENDER_SUGGESTION_LIMIT)
                    }
                }

                Box {
                    FormTextField(
                        value = draft.from,
                        onValueChange = { onDraftChange(draft.copy(from = it)) },
                        label = stringResource(R.string.email_rule_row_from),
                        placeholder = stringResource(R.string.email_rule_row_from_sub),
                        enabled = !draft.readOnly,
                        modifier = Modifier.onFocusChanged { senderFocused = it.isFocused },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        // Validated as you type as well as on save: a domain typo is much
                        // easier to see next to what you just typed.
                        errorText = when {
                            issue?.field == RuleField.FROM -> stringResource(issue.messageRes)

                            draft.from.isNotBlank() && !isValidSender(draft.from) ->
                                stringResource(R.string.email_rule_validation_from_invalid)

                            else -> conditionError
                        },
                    )

                    DropdownMenu(
                        expanded = senderFocused && matches.isNotEmpty(),
                        onDismissRequest = { senderFocused = false },
                        // Focus stays in the field so the keyboard does not close under
                        // the list while the user is still typing.
                        properties = PopupProperties(focusable = false),
                    ) {
                        matches.forEach { sender ->
                            DropdownMenuItem(
                                text = { Text(sender) },
                                onClick = {
                                    onDraftChange(draft.copy(from = sender))
                                    senderFocused = false
                                },
                            )
                        }
                    }
                }

                FormTextField(
                    value = draft.subject,
                    onValueChange = { onDraftChange(draft.copy(subject = it)) },
                    label = stringResource(R.string.email_rule_row_subject),
                    placeholder = stringResource(R.string.email_rule_row_subject),
                    enabled = !draft.readOnly,
                    errorText = conditionError,
                )

                FormTextField(
                    value = draft.hasWords,
                    onValueChange = { onDraftChange(draft.copy(hasWords = it)) },
                    label = stringResource(R.string.email_rule_row_has_words),
                    placeholder = stringResource(R.string.email_rule_row_has_words_sub),
                    enabled = !draft.readOnly,
                    errorText = conditionError,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    Checkbox(
                        checked = draft.hasAttachment,
                        onCheckedChange = { onDraftChange(draft.copy(hasAttachment = it)) },
                        enabled = !draft.readOnly,
                        colors = CheckboxDefaults.colors(checkedColor = ZillitTheme.colors.brand),
                    )
                    Text(
                        text = stringResource(R.string.email_rule_row_has_attachment),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textPrimary,
                    )
                }

                Text(
                    text = stringResource(R.string.email_rule_conditions_helper),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textTertiary,
                )

                NoticeBox(
                    text = RuleSummary.readback(
                        context = context,
                        from = draft.from,
                        subject = draft.subject,
                        hasWords = draft.hasWords,
                        hasAttachment = draft.hasAttachment,
                    ),
                    emphasised = true,
                )

                SectionTitle(stringResource(R.string.email_rule_section_then))

                draft.actions.forEachIndexed { index, action ->
                    RuleActionCard(
                        action = action,
                        onChange = { updated ->
                            onDraftChange(
                                draft.copy(
                                    actions = draft.actions.toMutableList().also {
                                        it[index] = updated
                                    },
                                ),
                            )
                        },
                        onRemove = {
                            onDraftChange(
                                draft.copy(
                                    actions = draft.actions.filterIndexed { i, _ -> i != index },
                                ),
                            )
                        },
                        onPickDriveFolder = { onPickDriveFolder(index) },
                        onCreateFolder = { onCreateFolder(index) },
                        folderNames = folderNames,
                        enabled = !draft.readOnly,
                        folderMissing = (action as? RuleAction.MoveToFolder)
                            ?.folderName in draft.missingFolderNames,
                        missingDriveFolder = (action as? RuleAction.SaveAttachmentsToDrive)
                            ?.driveFolderId in draft.missingDriveFolderIds,
                    )
                }

                if (issue?.field == RuleField.ACTIONS) {
                    Text(
                        text = stringResource(issue.messageRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = ZillitTheme.colors.danger,
                    )
                }

                if (!draft.readOnly) {
                    SecondaryButton(
                        text = stringResource(R.string.email_rule_add_action),
                        onClick = { showAddAction = true },
                        fillWidth = false,
                    )
                }

                // Only when a rule is doing what the forwarding setting does better.
                if (draft.shouldSuggestForwardingSetting) {
                    NoticeBox(text = stringResource(R.string.email_rule_forwarding_hint))
                }
            }

            PrimaryButton(
                text = stringResource(R.string.email_rule_save),
                onClick = onSave,
                enabled = !draft.readOnly,
                modifier = Modifier.padding(ZillitTheme.spacing.lg),
            )
        }
    }

    if (showAddAction) {
        AddActionDialog(
            onPick = { type ->
                showAddAction = false
                onAddAction(type)
            },
            onDismiss = { showAddAction = false },
        )
    }
}

/**
 * Which action to add.
 *
 * Three of the four types. `mark_read` is deliberately not offered — it round-trips fine on
 * a rule that already has one, but a rule whose only effect is to mark mail read is a way to
 * make mail disappear without noticing, so it is not something to create on a phone.
 */
@Composable
private fun AddActionDialog(onPick: (RuleActionType) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.email_rule_add_action)) },
        text = {
            Column {
                ActionChoice(R.string.email_rule_action_save_to_drive) {
                    onPick(RuleActionType.SAVE_ATTACHMENTS_TO_DRIVE)
                }
                ActionChoice(R.string.email_rule_action_move_to_folder) {
                    onPick(RuleActionType.MOVE_TO_FOLDER)
                }
                ActionChoice(R.string.email_rule_action_forward_to) {
                    onPick(RuleActionType.FORWARD_TO)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        containerColor = ZillitTheme.colors.surface,
    )
}

@Composable
private fun ActionChoice(labelRes: Int, onClick: () -> Unit) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.bodyLarge,
        color = ZillitTheme.colors.textPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = ZillitTheme.spacing.md),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = ZillitTheme.colors.textPrimary,
        modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
    )
}

/** A bordered block of explanatory text. [emphasised] is the live readback. */
@Composable
private fun NoticeBox(text: String, emphasised: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (emphasised) {
            ZillitTheme.colors.textPrimary
        } else {
            ZillitTheme.colors.textSecondary
        },
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (emphasised) {
                    ZillitTheme.colors.brandSoft
                } else {
                    ZillitTheme.colors.surface
                },
                shape = RoundedCornerShape(8.dp),
            )
            .padding(ZillitTheme.spacing.md),
    )
}

@Composable
private fun LoadingOverlay(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            CircularProgressIndicator(color = ZillitTheme.colors.brand)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

/** Enough to choose from without burying the field. */
private const val SENDER_SUGGESTION_LIMIT = 6
