package com.zillit.zillitapp.feature.email.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Unconditional forwarding — v2's `bottom_sheet_email_forwarding`.
 *
 * One address, forwarded at the mail-server level. Worth preferring over a forwarding
 * **rule**: it keeps working when the app's API is down and costs nothing per message —
 * which is exactly what the rules editor's own hint tells the user.
 *
 * Remove has no confirmation, matching v2. It is reversible in one step by typing the
 * address back in, so a dialog would be friction without a purpose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailForwardingSheet(
    address: String,
    configured: Boolean,
    busy: Boolean,
    error: String?,
    onAddressChange: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(
                    start = ZillitTheme.spacing.xl,
                    end = ZillitTheme.spacing.xl,
                    bottom = ZillitTheme.spacing.xl,
                ),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.email_forwarding),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = ZillitTheme.colors.textPrimary,
            )

            Text(
                text = stringResource(R.string.email_forwarding_description),
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )

            FormTextField(
                value = address,
                onValueChange = onAddressChange,
                placeholder = stringResource(R.string.email_forwarding_address_hint),
                label = stringResource(R.string.email_forwarding_address_hint),
                errorText = error,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done,
                ),
            )

            PrimaryButton(
                text = stringResource(R.string.save),
                onClick = onSave,
                enabled = !busy,
            )

            // Only offered once something is actually configured — v2 shows it always and
            // it does nothing on a mailbox with no forwarding set.
            if (configured) {
                SecondaryButton(
                    text = stringResource(R.string.email_forwarding_remove),
                    onClick = onRemove,
                    enabled = !busy,
                )
            }
        }
    }
}
