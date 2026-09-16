package com.zillit.zillitapp.feature.email.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.components.SettingsGroup
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * BCC presets — addresses blind-copied on every mail this mailbox sends.
 *
 * v2 reaches this by launching its **legacy** mailing module's screen, which is the last
 * live dependency the new email module has on the old one. Rebuilt here so the new module
 * stands on its own.
 *
 * The list belongs to whichever mailbox is active: the personal presets live on the user and
 * the shared mailbox keeps its own, because a preset that copies your own archive has no
 * business applying to mail the whole Accounts department sends.
 */
@Composable
fun BccPresetsScreen(
    presets: List<String>,
    draft: String,
    error: String?,
    saving: Boolean,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitTopBar(
                title = stringResource(R.string.email_bcc_presets_title),
                onBackClick = onBack,
                onHelpClick = null,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                FormTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    label = stringResource(R.string.email_bcc_enter_email),
                    placeholder = stringResource(R.string.email_bcc_enter_email),
                    errorText = error,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.weight(1f),
                )

                SecondaryButton(
                    text = stringResource(R.string.email_bcc_add),
                    onClick = onAdd,
                    enabled = !saving,
                    fillWidth = false,
                )
            }

            Text(
                text = stringResource(R.string.email_bcc_your_presets),
                style = MaterialTheme.typography.labelLarge,
                color = ZillitTheme.colors.textSecondary,
            )

            Box(modifier = Modifier.fillMaxSize()) {
                if (presets.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.Mail,
                        title = stringResource(R.string.email_bcc_empty),
                        description = stringResource(R.string.email_bcc_empty_subtitle),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = ZillitTheme.spacing.xl),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    ) {
                        items(presets, key = { it }) { address ->
                            SettingsGroup {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(ZillitTheme.spacing.md),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(
                                        ZillitTheme.spacing.md,
                                    ),
                                ) {
                                    Text(
                                        text = address,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ZillitTheme.colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = stringResource(R.string.delete),
                                        tint = ZillitTheme.colors.danger,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clickable(enabled = !saving) { onRemove(address) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
