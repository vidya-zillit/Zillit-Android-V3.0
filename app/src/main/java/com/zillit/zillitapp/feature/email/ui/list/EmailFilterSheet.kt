package com.zillit.zillitapp.feature.email.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.FilterChipFlow
import com.zillit.zillitapp.core.ui.components.ZillitFilterChip
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.components.zillitSwitchColors

/** Read state, as the list filters on it. */
enum class EmailReadFilter { ALL, READ, UNREAD }

/**
 * The list's filter sheet — v2's `fragment_filter_bottom_sheet`.
 *
 * No Apply and no Reset: each control takes effect the moment it is touched and the list
 * behind the sheet updates under it, which is v2's behaviour and the right one for two
 * controls. The dot on the toolbar icon is what tells the user a filter is still on after
 * the sheet is gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailFilterSheet(
    readFilter: EmailReadFilter,
    hasAttachments: Boolean,
    onReadFilterChange: (EmailReadFilter) -> Unit,
    onHasAttachmentsChange: (Boolean) -> Unit,
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
                .padding(
                    start = ZillitTheme.spacing.xl,
                    end = ZillitTheme.spacing.xl,
                    bottom = ZillitTheme.spacing.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.email_filter_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = ZillitTheme.colors.textPrimary,
            )

            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                Text(
                    text = stringResource(R.string.email_filter_status),
                    style = MaterialTheme.typography.labelLarge,
                    color = ZillitTheme.colors.textSecondary,
                )

                FilterChipFlow {
                    ZillitFilterChip(
                        label = stringResource(R.string.email_filter_all),
                        selected = readFilter == EmailReadFilter.ALL,
                        onClick = { onReadFilterChange(EmailReadFilter.ALL) },
                    )
                    ZillitFilterChip(
                        label = stringResource(R.string.email_filter_read),
                        selected = readFilter == EmailReadFilter.READ,
                        onClick = { onReadFilterChange(EmailReadFilter.READ) },
                    )
                    ZillitFilterChip(
                        label = stringResource(R.string.email_filter_unread),
                        selected = readFilter == EmailReadFilter.UNREAD,
                        onClick = { onReadFilterChange(EmailReadFilter.UNREAD) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.email_filter_has_attachments),
                        style = MaterialTheme.typography.bodyLarge,
                        color = ZillitTheme.colors.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.email_filter_has_attachments_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textTertiary,
                    )
                }

                Switch(
                    checked = hasAttachments,
                    onCheckedChange = onHasAttachmentsChange,
                    colors = zillitSwitchColors(),
                )
            }
        }
    }
}
