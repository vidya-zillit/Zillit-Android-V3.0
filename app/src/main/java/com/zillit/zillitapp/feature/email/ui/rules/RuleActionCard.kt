package com.zillit.zillitapp.feature.email.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.DropdownField
import com.zillit.zillitapp.core.ui.components.FieldBox
import com.zillit.zillitapp.core.ui.components.FieldLabel
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.components.SettingsGroup
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.RuleAction
import com.zillit.zillitapp.feature.email.domain.isPlausibleEmail

/**
 * One action in the editor — v2's `item_rule_action`.
 *
 * Each action type owns a different set of inputs, and only its own are shown. That is not
 * cosmetic: the API's schemas are strict and a stray `folder_name` on a `forward_to` action
 * is rejected outright, so "the type decides the fields" is enforced by the sealed
 * [RuleAction] hierarchy rather than by hiding views.
 *
 * @param folderNames destinations offered by a move action. Only Junk, Trash and folders the
 *   user made — mail cannot be filed into Inbox, Sent or Drafts by a rule.
 * @param missingDriveFolder true when the saved Drive folder has since been deleted, which
 *   the server rejects at save time, so it is said here instead.
 */
@Composable
fun RuleActionCard(
    action: RuleAction,
    onChange: (RuleAction) -> Unit,
    onRemove: () -> Unit,
    onPickDriveFolder: () -> Unit,
    folderNames: List<String>,
    onCreateFolder: () -> Unit,
    modifier: Modifier = Modifier,
    folderMissing: Boolean = false,
    missingDriveFolder: Boolean = false,
    enabled: Boolean = true,
) {
    val context = LocalContext.current

    SettingsGroup(modifier = modifier) {
        Column(
            modifier = Modifier.padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = RuleSummary.actionLabel(context, action),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ZillitTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (enabled) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.email_rule_remove_action),
                        tint = ZillitTheme.colors.textTertiary,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(onClick = onRemove),
                    )
                }
            }

            val createFolderEntry = stringResource(R.string.email_rule_create_folder_entry)

            when (action) {
                is RuleAction.MoveToFolder -> DropdownField(
                    label = stringResource(R.string.email_rule_hint_folder),
                    value = action.folderName,
                    // The last entry makes the folder rather than picking one. Filing into
                    // a folder that does not exist yet otherwise means abandoning a
                    // half-written rule to go and create it in the drawer.
                    options = folderNames + createFolderEntry,
                    optionLabel = { it },
                    onPick = { picked ->
                        if (picked == createFolderEntry) {
                            onCreateFolder()
                        } else {
                            onChange(action.copy(folderName = picked))
                        }
                    },
                    enabled = enabled,
                    error = when {
                        action.folderName.isBlank() ->
                            stringResource(R.string.email_rule_error_folder_required)

                        folderMissing ->
                            stringResource(R.string.email_rule_folder_unavailable)

                        else -> null
                    },
                )

                is RuleAction.ForwardTo -> FormTextField(
                    value = action.email,
                    onValueChange = { onChange(action.copy(email = it)) },
                    label = stringResource(R.string.email_rule_hint_forward_email),
                    placeholder = stringResource(R.string.email_rule_hint_forward_email),
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    ),
                    errorText = stringResource(R.string.valid_email)
                        .takeIf { action.email.isNotBlank() && !action.email.isPlausibleEmail() },
                )

                is RuleAction.SaveAttachmentsToDrive -> DriveFolderField(
                    folderName = action.driveFolderName,
                    hasFolder = action.driveFolderId.isNotBlank(),
                    missing = missingDriveFolder,
                    enabled = enabled,
                    onClick = onPickDriveFolder,
                )

                RuleAction.MarkRead -> Text(
                    text = stringResource(R.string.email_rule_no_config),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textTertiary,
                )
            }
        }
    }
}

/**
 * The Drive destination.
 *
 * A row that opens a picker, never a text field: the folder id is checked against real Drive
 * folders when the rule is saved, so a typed name could never be valid.
 */
@Composable
private fun DriveFolderField(
    folderName: String,
    hasFolder: Boolean,
    missing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FieldLabel(stringResource(R.string.email_rule_action_save_to_drive))

        FieldBox(
            onClick = onClick.takeIf { enabled },
            borderColor = if (missing) ZillitTheme.colors.danger else null,
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = ZillitTheme.colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = when {
                    missing -> stringResource(R.string.email_rule_drive_folder_unavailable)
                    hasFolder -> folderName
                    else -> stringResource(R.string.email_rule_select_drive_folder)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    missing -> ZillitTheme.colors.danger
                    hasFolder -> ZillitTheme.colors.textPrimary
                    else -> ZillitTheme.colors.textTertiary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = ZillitTheme.spacing.sm),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = ZillitTheme.colors.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
