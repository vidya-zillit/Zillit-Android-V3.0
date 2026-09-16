package com.zillit.zillitapp.feature.email.ui.settings

import android.view.WindowManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogWindowProvider
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Sets a new mailbox password.
 *
 * The field starts **empty** rather than pre-filled, and deliberately so: what the profile
 * holds is `enc:v1:…` ciphertext, not a password, and there is nothing to pre-fill it with
 * that would not be wrong.
 *
 * The dialog marks its **own** window `FLAG_SECURE`. A dialog gets a separate window from
 * the activity behind it, so the flag the credentials sheet sets does not cover this — and
 * a password typed into an unflagged window is screenshottable and appears in the recents
 * thumbnail.
 */
@Composable
fun ChangePasswordDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val emptyMessage = stringResource(R.string.password_not_empty)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_password_txt)) },
        text = {
            SecureDialogWindow()

            FormTextField(
                value = password,
                onValueChange = {
                    password = it
                    error = null
                },
                placeholder = stringResource(R.string.password_txt),
                errorText = error,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (password.isBlank()) error = emptyMessage else onConfirm(password)
                },
            ) {
                Text(stringResource(R.string.update))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        containerColor = ZillitTheme.colors.surface,
    )
}

/** Blocks capture of this dialog's window for as long as it is shown. */
@Composable
private fun SecureDialogWindow() {
    val view = LocalView.current

    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }
}
