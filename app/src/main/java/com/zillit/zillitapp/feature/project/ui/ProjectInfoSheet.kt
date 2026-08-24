package com.zillit.zillitapp.feature.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.labels.LocalLabels
import com.zillit.zillitapp.core.labels.resolveLabel
import com.zillit.zillitapp.core.common.toDateLabel
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.project.domain.Project

/**
 * Project details, opened from the info icon on a list row.
 *
 * Same fields and labels as v2's `projectInfoTap` bottom sheet — name, code, created-on,
 * type, sub-type — so the content reads identically to anyone who knows the old app.
 * Rows whose value is missing are omitted rather than shown blank, which is what v2 does
 * for sub-type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectInfoSheet(
    project: Project,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val labels = LocalLabels.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ZillitTheme.spacing.lg,
                    end = ZillitTheme.spacing.lg,
                    bottom = ZillitTheme.spacing.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.project_info),
                style = MaterialTheme.typography.titleLarge,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm),
            )

            InfoRow(stringResource(R.string.project_name), project.name)

            project.code?.takeIf { it.isNotBlank() }?.let {
                InfoRow(stringResource(R.string.project_code), it)
            }

            project.createdAt?.let {
                InfoRow(stringResource(R.string.created_on), formatDate(it))
            }

            // Type and sub-type are server label KEYS, resolved through the dictionary
            // exactly as on the card itself.
            project.type?.takeIf { it.isNotBlank() }?.let {
                InfoRow(stringResource(R.string.project_type), labels.resolveLabel(it))
            }

            project.subType?.takeIf { it.isNotBlank() }?.let {
                InfoRow(stringResource(R.string.project_sub_type), labels.resolveLabel(it))
            }

            project.companyName?.takeIf { it.isNotBlank() }?.let {
                InfoRow(stringResource(R.string.company_name), it)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1.4f),
            )
        }
        HorizontalDivider(
            color = ZillitTheme.colors.divider,
            modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
        )
    }
}

private fun formatDate(millis: Long): String =
    millis.toDateLabel()
