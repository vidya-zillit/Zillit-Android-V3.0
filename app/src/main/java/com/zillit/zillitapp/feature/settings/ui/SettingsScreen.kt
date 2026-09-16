package com.zillit.zillitapp.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
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
import com.zillit.zillitapp.core.ui.components.InfoDialog
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.components.SettingsEmphasis
import com.zillit.zillitapp.core.ui.components.SettingsGroup
import com.zillit.zillitapp.core.ui.components.SettingsNavRow
import com.zillit.zillitapp.core.ui.components.SettingsRowDivider

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
                if (index > 0) SettingsRowDivider()
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
                if (index > 0) SettingsRowDivider()
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

/**
 * A project-settings row, mapped onto the shared [SettingsNavRow].
 *
 * The mapping is all that is left of what used to be a 70-line private composable: the
 * emphasis, the attention dot, the badge and the ⓘ are the shared row's now, because the
 * email settings screen needed every one of them too.
 */
@Composable
private fun SettingsRowView(
    row: SettingsRow,
    title: String,
    onClick: () -> Unit,
    onInfo: () -> Unit,
) {
    SettingsNavRow(
        title = title,
        onClick = onClick,
        icon = row.destination.icon(),
        emphasis = when (row.emphasis) {
            SettingsRow.RowEmphasis.ADMIN -> SettingsEmphasis.ADMIN
            SettingsRow.RowEmphasis.DANGER -> SettingsEmphasis.DANGER
            SettingsRow.RowEmphasis.NORMAL -> SettingsEmphasis.NORMAL
        },
        badgeCount = row.badgeCount,
        showDot = row.showDot,
        onInfo = onInfo.takeIf { row.infoRes != null },
    )
}
