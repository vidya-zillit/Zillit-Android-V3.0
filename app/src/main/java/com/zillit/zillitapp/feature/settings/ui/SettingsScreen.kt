package com.zillit.zillitapp.feature.settings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.directory.ProjectUser
import com.zillit.zillitapp.core.labels.asLabel
import com.zillit.zillitapp.core.help.HelpLinksFor
import com.zillit.zillitapp.core.ui.components.CountBadge
import com.zillit.zillitapp.core.ui.components.InfoDialog
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * The settings landing page.
 *
 * Same rows, same words and same rules as v2 — including its alphabetical ordering, which is
 * how people have learned to find things here. The presentation is not the same: v2 draws one
 * long undivided list of identical rows, so Admin Settings and Leave This Project are only
 * distinguishable by their colour. Here the destructive and privileged rows are separated
 * into their own group, and each row's ⓘ blurb is one tap away rather than hidden behind an
 * icon the size of a full stop.
 */
@Composable
fun SettingsScreen(
    onOpen: (SettingsDestination) -> Unit,
    onEditProfile: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Which row's ⓘ is open, and whether Leave is being confirmed.
    var info by remember { mutableStateOf<SettingsRow?>(null) }
    var leaving by remember { mutableStateOf(false) }

    // Sorted by what the user actually reads, not by the order the list was built in — v2
    // sorts by title too, and sorting the resolved text keeps it right in every language.
    val ordered = state.rows.filter { it.emphasis == SettingsRow.RowEmphasis.NORMAL }
        .map { it to stringResource(it.titleRes) }
        .sortedBy { (_, title) -> title.lowercase() }
    val pinned = state.rows.filter { it.emphasis != SettingsRow.RowEmphasis.NORMAL }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ProfileHeader(user = state.user, onEdit = onEditProfile)

        SettingsGroup {
            ordered.forEachIndexed { index, (row, title) ->
                if (index > 0) RowDivider()
                SettingsRowView(
                    row = row,
                    title = title,
                    onClick = { onOpen(row.destination) },
                    onInfo = { info = row },
                )
            }
        }

        SettingsGroup {
            pinned.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                SettingsRowView(
                    row = row,
                    title = stringResource(row.titleRes),
                    onClick = {
                        if (row.destination == SettingsDestination.LEAVE_PROJECT) {
                            leaving = true
                        } else {
                            onOpen(row.destination)
                        }
                    },
                    onInfo = { info = row },
                )
            }
        }
    }

    info?.let { row ->
        val message = row.infoRes?.let { res ->
            row.infoArg?.let { stringResource(res, it) } ?: stringResource(res)
        }
        InfoDialog(
            title = stringResource(row.titleRes),
            message = message.orEmpty(),
            links = viewModel.linksFor(row) ?: HelpLinksFor(),
            onDismiss = { info = null },
        )
    }

    if (leaving) {
        ZillitConfirmDialog(
            title = stringResource(R.string.leave_this_project),
            message = stringResource(R.string.are_you_sure_you_want_to_leave_the_project),
            confirmLabel = stringResource(R.string.yes),
            dismissLabel = stringResource(R.string.no),
            isDestructive = true,
            onConfirm = {
                leaving = false
                onOpen(SettingsDestination.LEAVE_PROJECT)
            },
            onDismiss = { leaving = false },
        )
    }
}

/**
 * Who you are on this project.
 *
 * The designation matters as much as the name — a production has several people called Sam
 * and only one 1st AD — so it is shown, resolved from its label key.
 */
@Composable
private fun ProfileHeader(user: ProjectUser?, onEdit: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ZillitTheme.colors.brandSoft,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            UserAvatar(
                initials = user?.initials.orEmpty(),
                pictureKey = user?.profilePictureUrl,
                thumbnailKey = user?.profileThumbnailKey,
                size = 56.dp,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user?.displayName.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                user?.designationName?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it.asLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.edit_my_profile),
                    tint = ZillitTheme.colors.brand,
                )
            }
        }
    }
}

/** A card of rows. Grouping is what separates "settings" from "leaving the project". */
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ZillitTheme.colors.surface,
        border = BorderStroke(1.dp, ZillitTheme.colors.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = ZillitTheme.colors.divider,
        modifier = Modifier.padding(start = 64.dp),
    )
}

@Composable
private fun SettingsRowView(
    row: SettingsRow,
    title: String,
    onClick: () -> Unit,
    onInfo: () -> Unit,
) {
    val tint = when (row.emphasis) {
        SettingsRow.RowEmphasis.ADMIN -> ZillitTheme.colors.accent
        SettingsRow.RowEmphasis.DANGER -> ZillitTheme.colors.danger
        SettingsRow.RowEmphasis.NORMAL -> ZillitTheme.colors.brand
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .background(tint.copy(alpha = 0.12f), CircleShape),
        ) {
            Icon(
                imageVector = row.destination.icon(),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = when (row.emphasis) {
                SettingsRow.RowEmphasis.NORMAL -> FontWeight.Normal
                else -> FontWeight.SemiBold
            },
            color = when (row.emphasis) {
                SettingsRow.RowEmphasis.NORMAL -> ZillitTheme.colors.textPrimary
                else -> tint
            },
            modifier = Modifier.weight(1f),
        )

        if (row.showDot) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(ZillitTheme.colors.danger, CircleShape),
            )
            Spacer(Modifier.size(ZillitTheme.spacing.xs))
        }

        CountBadge(count = row.badgeCount)

        if (row.infoRes != null) {
            IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = title,
                    tint = ZillitTheme.colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** One icon per destination, so a row is recognisable before it is read. */
private fun SettingsDestination.icon(): ImageVector = when (this) {
    SettingsDestination.APP_PREFERENCES -> Icons.Outlined.Tune
    SettingsDestination.LINKED_DEVICES -> Icons.Outlined.Laptop
    SettingsDestination.EDIT_PROFILE -> Icons.Outlined.Edit
    SettingsDestination.INVITE_USERS -> Icons.Outlined.PersonAdd
    SettingsDestination.PRIVACY_PREFERENCES -> Icons.Outlined.PrivacyTip
    SettingsDestination.RECOVERY_CODE -> Icons.Outlined.Key
    SettingsDestination.UPDATE_APP -> Icons.Outlined.SystemUpdate
    SettingsDestination.HELP -> Icons.AutoMirrored.Outlined.HelpOutline
    SettingsDestination.ACCOUNT_SETTINGS -> Icons.Outlined.ReceiptLong
    SettingsDestination.CATERING_SETTINGS -> Icons.Outlined.Restaurant
    SettingsDestination.ADMIN_SETTINGS -> Icons.Outlined.AdminPanelSettings
    SettingsDestination.LEAVE_PROJECT -> Icons.AutoMirrored.Outlined.Logout
}
