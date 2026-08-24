package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Pick one of a short list — theme, font size, language, sort order.
 *
 * Generic over the option type with a [label] lambda rather than taking pre-rendered
 * strings, so the caller keeps its enum and the dialog still resolves each label in the
 * current locale at render time.
 *
 * Selecting closes the dialog by convention: the caller's [onSelected] is expected to also
 * dismiss. There is no confirm button because a one-of-N choice with an OK step asks the
 * user to agree with themselves.
 *
 * @param label how to render one option. Composable so it can call `stringResource`.
 */
@Composable
fun <T> ZillitChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = ZillitTheme.colors.surface,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = ZillitTheme.colors.textPrimary,
            )
        },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(option) }
                            .padding(vertical = ZillitTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    ) {
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.bodyLarge,
                            color = ZillitTheme.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (option == selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = ZillitTheme.colors.brand,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.cancel),
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        },
    )
}
