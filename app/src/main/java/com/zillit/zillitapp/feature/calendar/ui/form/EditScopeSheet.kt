package com.zillit.zillitapp.feature.calendar.ui.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.model.EditScope
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * How much of a recurring series an edit or delete applies to.
 *
 * Always asked, never inferred: changing one occurrence and changing the whole series are
 * different outcomes, and neither can be undone into the other. Defaults to the single
 * occurrence, which is the least destructive of the three.
 *
 * @param title overridden by delete, which asks the same question about a different verb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScopeSheet(
    onConfirm: (EditScope) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.calendar_apply_to),
    confirmLabel: String = stringResource(R.string.action_done),
    isDestructive: Boolean = false,
) {
    var scope by rememberSaveable { mutableStateOf(EditScope.SINGLE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = ZillitTheme.spacing.lg)
                .padding(bottom = ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isDestructive) {
                    ZillitTheme.colors.danger
                } else {
                    ZillitTheme.colors.textPrimary
                },
            )

            EditScope.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = scope == option, onClick = { scope = option })
                        .padding(vertical = ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = scope == option, onClick = { scope = option })
                    Text(
                        text = stringResource(option.scopeLabelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textPrimary,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                SecondaryButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = confirmLabel,
                    onClick = { onConfirm(scope) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun EditScope.scopeLabelRes(): Int = when (this) {
    EditScope.SINGLE -> R.string.calendar_scope_single
    EditScope.THIS_AND_FUTURE -> R.string.calendar_scope_this_and_future
    EditScope.ALL -> R.string.calendar_scope_all
}
