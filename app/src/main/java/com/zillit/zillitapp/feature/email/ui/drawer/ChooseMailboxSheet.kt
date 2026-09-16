package com.zillit.zillitapp.feature.email.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.MailboxScope

/**
 * Which mailbox to read — the user's own, or the project's shared "Accounts" one.
 *
 * Only ever reachable by a user entitled to the shared mailbox. Everyone else sees no
 * chevron in the drawer header and never opens this.
 *
 * The unread pill on each row matters more than it looks: switching mailbox changes what
 * every folder count means, and without it the user has no way to tell which mailbox the
 * unread mail they were told about is actually in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseMailboxSheet(
    current: MailboxScope,
    personalEmail: String,
    accountsEmail: String?,
    personalUnread: Int,
    accountsUnread: Int,
    onChoose: (MailboxScope) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.email_choose_account),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = ZillitTheme.spacing.md),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            HorizontalDivider(color = ZillitTheme.colors.divider)

            MailboxRow(
                icon = Icons.Outlined.Person,
                title = stringResource(R.string.email_mailbox_personal),
                email = personalEmail,
                unread = personalUnread,
                selected = current == MailboxScope.PERSONAL,
                onClick = { onChoose(MailboxScope.PERSONAL) },
            )

            // Absent, not disabled: a project without a shared mailbox has nothing to offer
            // here, and a greyed row would only invite the question.
            if (accountsEmail != null) {
                MailboxRow(
                    icon = Icons.Outlined.Groups,
                    title = stringResource(R.string.email_mailbox_accounts),
                    email = accountsEmail,
                    unread = accountsUnread,
                    selected = current == MailboxScope.SHARED,
                    onClick = { onChoose(MailboxScope.SHARED) },
                )
            }
        }
    }
}

@Composable
private fun MailboxRow(
    icon: ImageVector,
    title: String,
    email: String,
    unread: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(ZillitTheme.colors.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ZillitTheme.colors.brand,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = ZillitTheme.colors.textPrimary,
            )
            Text(
                text = email,
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (unread > 0) {
            Text(
                // Capped at 99+: past that the exact number tells nobody anything, and a
                // four-digit pill pushes the check mark off the row.
                text = if (unread > MAX_SHOWN) "$MAX_SHOWN+" else unread.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = ZillitTheme.colors.textOnBrand,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(ZillitTheme.colors.danger)
                    .padding(
                        horizontal = ZillitTheme.spacing.sm,
                        vertical = ZillitTheme.spacing.xxs,
                    ),
            )
        }

        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = ZillitTheme.colors.brand,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private const val MAX_SHOWN = 99
