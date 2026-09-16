package com.zillit.zillitapp.feature.email.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.SettingsGroup
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.html.HtmlBodyView
import com.zillit.zillitapp.core.ui.html.RichTextEditor
import com.zillit.zillitapp.core.ui.html.RichTextEditorState
import com.zillit.zillitapp.core.ui.html.RichTextToolbar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailSignature

/**
 * Signatures — v2's `fragment_signature_list` and `item_signature`.
 *
 * **At most one.** v2 enforces that by hiding the add button once a signature exists, and
 * this does the same — it is the server's limit, not a UI preference.
 */
@Composable
fun SignatureListScreen(
    signatures: List<EmailSignature>,
    loading: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (EmailSignature) -> Unit,
    onDelete: (EmailSignature) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitTopBar(
                title = stringResource(R.string.email_signatures),
                onBackClick = onBack,
                onHelpClick = null,
            )
        },
        floatingActionButton = {
            // One signature is the limit, so the way to add a second is simply absent.
            if (!loading && signatures.isEmpty()) {
                FloatingActionButton(
                    onClick = onAdd,
                    containerColor = ZillitTheme.colors.brand,
                    contentColor = ZillitTheme.colors.textOnBrand,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.email_signature_add),
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                loading -> LoadingState()

                signatures.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Draw,
                    title = stringResource(R.string.email_signature_empty_title),
                    description = stringResource(R.string.email_signature_empty_subtitle),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(ZillitTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    items(signatures, key = { it.id }) { signature ->
                        SignatureCard(
                            signature = signature,
                            onEdit = { onEdit(signature) },
                            onDelete = { onDelete(signature) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignatureCard(
    signature: EmailSignature,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    SettingsGroup {
      Column(modifier = Modifier.padding(ZillitTheme.spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = signature.name.ifBlank {
                    stringResource(R.string.email_signature_untitled)
                },
                style = MaterialTheme.typography.titleSmall,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )

            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.edit),
                tint = ZillitTheme.colors.brand,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onEdit),
            )
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(R.string.delete),
                tint = ZillitTheme.colors.danger,
                modifier = Modifier
                    .padding(start = ZillitTheme.spacing.md)
                    .size(22.dp)
                    .clickable(onClick = onDelete),
            )
        }

        // The point of a signature is what it looks like, so the card renders it rather
        // than describing it.
        if (signature.content.isBlank()) {
            Text(
                text = stringResource(R.string.email_no_content),
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textTertiary,
                modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
            )
        } else {
            HtmlBodyView(
                html = signature.content,
                modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
            )
        }
      }
    }
}

/**
 * The signature editor — v2's `fragment_edit_signature`.
 *
 * v2 gives this a bare `contenteditable` WebView with **no formatting toolbar at all**,
 * while the composer next door has ten buttons — so a signature can only be styled by
 * pasting styled text in from somewhere else. Here it is the same [RichTextEditor] the
 * composer uses, toolbar included.
 */
@Composable
fun EditSignatureScreen(
    name: String,
    editorState: RichTextEditorState,
    isNew: Boolean,
    nameError: String?,
    saving: Boolean,
    onNameChange: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = ZillitTheme.colors.surface,
        topBar = {
            ZillitTopBar(
                title = stringResource(
                    if (isNew) {
                        R.string.email_signature_add_title
                    } else {
                        R.string.email_signature_edit_title
                    },
                ),
                onBackClick = onBack,
                onHelpClick = null,
                actions = {
                    Text(
                        text = stringResource(R.string.save),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (saving) {
                            ZillitTheme.colors.textTertiary
                        } else {
                            ZillitTheme.colors.brand
                        },
                        modifier = Modifier
                            .clickable(enabled = !saving, onClick = onSave)
                            .padding(ZillitTheme.spacing.sm),
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            FormTextField(
                value = name,
                onValueChange = onNameChange,
                label = stringResource(R.string.email_signature_name_hint),
                placeholder = stringResource(R.string.email_signature_name_hint),
                errorText = nameError,
                modifier = Modifier.padding(ZillitTheme.spacing.lg),
            )

            Text(
                text = stringResource(R.string.email_signature_content_label),
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.padding(
                    start = ZillitTheme.spacing.lg,
                    bottom = ZillitTheme.spacing.sm,
                ),
            )

            Box(modifier = Modifier.weight(1f)) {
                RichTextEditor(
                    state = editorState,
                    placeholder = stringResource(R.string.email_signature_placeholder),
                )
            }

            RichTextToolbar(state = editorState)
        }
    }
}
