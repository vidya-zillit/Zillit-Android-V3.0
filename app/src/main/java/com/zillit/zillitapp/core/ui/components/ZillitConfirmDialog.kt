package com.zillit.zillitapp.core.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * The app's one confirmation dialog.
 *
 * Every "are you sure?" in v2 was a hand-rolled `showDialogWithButton` call, which is why
 * the same question looked different depending on which screen asked it — different title
 * casing, different button order, sometimes Yes/No and sometimes OK/Cancel. This takes the
 * parts that legitimately vary (title, message, the two button labels) and fixes
 * everything else, so a new screen cannot invent a new style by accident.
 *
 * Defaults match the wording v2 ships and the client signed off on: an "Alert" title with
 * "Yes" and "No". Pass your own labels where the action deserves a verb ("Delete",
 * "Logout") — a named action is always clearer than "Yes".
 *
 * @param message the question. Required: a dialog with no question is a dialog with no
 *   reason to exist.
 * @param onDismiss the negative button.
 * @param onCancel backing out entirely — tapping outside, or the back gesture. Defaults to
 *   [onDismiss] because for a plain Yes/No they mean the same thing, but they must stay
 *   separable: where **both** buttons take an action ("Continuation" vs "New", "This file"
 *   vs "All files"), backing out has to do neither, and folding it into [onDismiss] would
 *   silently perform one of them.
 * @param title pass null for a message-only dialog.
 * @param dismissLabel pass null for a single-button acknowledgement.
 * @param isDestructive tints the confirm button red. For anything that deletes, removes or
 *   signs out — the colour is the last warning before it happens.
 */
@Composable
fun ZillitConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = onDismiss,
    title: String? = stringResource(R.string.alert),
    confirmLabel: String = stringResource(R.string.yes),
    dismissLabel: String? = stringResource(R.string.no),
    isDestructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = modifier,
        containerColor = ZillitTheme.colors.surface,
        title = title?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleLarge,
                    color = ZillitTheme.colors.textPrimary,
                )
            }
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (isDestructive) {
                        ZillitTheme.colors.danger
                    } else {
                        ZillitTheme.colors.brand
                    },
                )
            }
        },
        dismissButton = dismissLabel?.let {
            {
                TextButton(onClick = onDismiss) {
                    Text(text = it, color = ZillitTheme.colors.textSecondary)
                }
            }
        },
    )
}
