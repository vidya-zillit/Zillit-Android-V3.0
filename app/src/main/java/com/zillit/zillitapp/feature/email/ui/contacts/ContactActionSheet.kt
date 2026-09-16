package com.zillit.zillitapp.feature.email.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailAddress

/**
 * Who this is, and whether they are in your contacts — v2's `fragment_contact_bottom_sheet`.
 *
 * Opened from "Add to Contacts" on a message. The sheet exists rather than jumping straight
 * to the editor because a thread has several addresses on it and the first useful answer is
 * usually "I already have them".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactActionSheet(
    address: EmailAddress,
    alreadySaved: Boolean,
    onAddToContacts: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = ZillitTheme.spacing.xl,
                    end = ZillitTheme.spacing.xl,
                    bottom = ZillitTheme.spacing.xxl,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            UserAvatar(initials = address.initials, size = 72.dp)

            Text(
                text = address.display,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = ZillitTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )

            HorizontalDivider(color = ZillitTheme.colors.divider)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mail,
                    contentDescription = null,
                    tint = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = address.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                )
            }

            HorizontalDivider(color = ZillitTheme.colors.divider)

            if (alreadySaved) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PersonAdd,
                        contentDescription = null,
                        tint = ZillitTheme.colors.success,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.email_saved_in_contacts),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = ZillitTheme.colors.success,
                    )
                }
            } else {
                SecondaryButton(
                    text = stringResource(R.string.email_add_to_contacts),
                    onClick = onAddToContacts,
                )
            }
        }
    }
}
