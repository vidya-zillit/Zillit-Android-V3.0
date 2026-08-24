package com.zillit.zillitapp.feature.calendar.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * Answering an invitation.
 *
 * A bottom sheet rather than a dialog, and deliberately: v2 started with an alert dialog
 * and the keyboard pushed the Cancel / Decline buttons off screen the moment the user
 * tapped the reason field — an alert window is fixed-size and does not reflow. A sheet
 * with `imePadding` keeps the buttons above the keyboard.
 *
 * For a recurring event the scope is asked **before** the request, never inferred:
 * declining one Tuesday and declining every Tuesday are not recoverable from each other.
 *
 * @param isRecurring shows the scope choice. A one-off answers with [EditScope.ALL], which
 *   is what the server expects for an event with nothing to scope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcceptDeclineSheet(
    isAccept: Boolean,
    isRecurring: Boolean,
    onConfirm: (scope: EditScope, reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Survives rotation: a half-typed reason should not be lost to a screen turn.
    var scope by rememberSaveable { mutableStateOf(EditScope.SINGLE) }
    var reason by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = ZillitTheme.spacing.lg)
                .padding(bottom = ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Text(
                text = stringResource(
                    if (isAccept) {
                        R.string.calendar_accept_invitation
                    } else {
                        R.string.calendar_decline_invitation
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ZillitTheme.colors.textPrimary,
            )

            if (isRecurring) {
                Text(
                    text = stringResource(R.string.calendar_apply_to),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textTertiary,
                )

                EditScope.entries.forEach { option ->
                    ScopeOption(
                        scope = option,
                        selected = scope == option,
                        onSelect = { scope = option },
                    )
                }
            } else {
                Text(
                    text = stringResource(
                        if (isAccept) {
                            R.string.calendar_confirm_accept
                        } else {
                            R.string.calendar_confirm_decline
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                )
            }

            if (!isAccept) {
                Text(
                    text = stringResource(R.string.calendar_decline_reason_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textTertiary,
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    placeholder = { Text(stringResource(R.string.calendar_decline_reason_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                SecondaryButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = stringResource(
                        if (isAccept) R.string.calendar_accept else R.string.calendar_decline,
                    ),
                    onClick = {
                        // A one-off has nothing to scope; the server wants `all` there.
                        onConfirm(if (isRecurring) scope else EditScope.ALL, reason.trim())
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ScopeOption(scope: EditScope, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = stringResource(scope.labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )
    }
}

private val EditScope.labelRes: Int
    get() = when (this) {
        EditScope.SINGLE -> R.string.calendar_scope_single
        EditScope.THIS_AND_FUTURE -> R.string.calendar_scope_this_and_future
        EditScope.ALL -> R.string.calendar_scope_all
    }
