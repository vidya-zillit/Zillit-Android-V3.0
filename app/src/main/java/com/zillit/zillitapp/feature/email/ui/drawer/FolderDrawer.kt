package com.zillit.zillitapp.feature.email.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailFolder
import com.zillit.zillitapp.feature.email.domain.EmailFolders
import com.zillit.zillitapp.feature.email.domain.MailboxScope
import com.zillit.zillitapp.feature.email.ui.list.folderDisplayName

/** What the drawer draws. */
data class FolderDrawerState(
    val folders: List<EmailFolder> = emptyList(),
    val selectedFolder: String = EmailFolders.INBOX,
    /** The mailbox the header describes — "Accounts", or the person. */
    val mailboxName: String = "",
    val mailboxEmail: String = "",
    val avatarInitials: String = "",
    val avatarPictureKey: String? = null,
    val avatarThumbnailKey: String? = null,
    /**
     * Whether the header opens the mailbox chooser.
     *
     * False for the great majority of users, who only have their own mailbox — and for them
     * the chevron is absent entirely rather than present and inert.
     */
    val canSwitchMailbox: Boolean = false,
    /** The user's own address. Shown in the chooser even while the shared one is active. */
    val personalEmail: String = "",
    /** The project's shared address, or null when it has none. */
    val accountsEmail: String? = null,
    /** Which of the two is currently being read. */
    val scope: MailboxScope = MailboxScope.PERSONAL,
)

/**
 * The folder drawer — v2's `fragment_folder_drawer`.
 *
 * Three bands: who you are, where your mail is, and where else you can go. The middle band
 * is the only one that scrolls, so the account header and the section nav stay reachable in
 * a mailbox with forty folders.
 *
 * The "Folders" header is **always** present, even with no custom folders, because it is
 * also the only way to create one — v2 does the same, and it is the reason an empty mailbox
 * still shows a header with nothing under it.
 */
@Composable
fun FolderDrawer(
    state: FolderDrawerState,
    onFolderClick: (EmailFolder) -> Unit,
    onFolderMore: (EmailFolder) -> Unit,
    onCreateFolder: () -> Unit,
    onSwitchMailbox: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.surface),
    ) {
        MailboxHeader(
            state = state,
            onClick = onSwitchMailbox.takeIf { state.canSwitchMailbox },
            modifier = Modifier.statusBarsPadding(),
        )

        HorizontalDivider(color = ZillitTheme.colors.divider)

        val systemFolders = state.folders.filter { it.isSystem }
        val customFolders = state.folders.filterNot { it.isSystem }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = ZillitTheme.spacing.sm),
        ) {
            items(systemFolders, key = { it.folderName }) { folder ->
                FolderRow(
                    folder = folder,
                    selected = folder.folderName == state.selectedFolder,
                    onClick = { onFolderClick(folder) },
                    onMore = null,
                )
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.email_folders_header),
                    actionIcon = Icons.Outlined.CreateNewFolder,
                    actionDescription = stringResource(R.string.email_create_folder_title),
                    onAction = onCreateFolder,
                )
            }

            items(customFolders, key = { it.folderName }) { folder ->
                FolderRow(
                    folder = folder,
                    selected = folder.folderName == state.selectedFolder,
                    onClick = { onFolderClick(folder) },
                    onMore = { onFolderMore(folder) },
                )
            }
        }

        HorizontalDivider(color = ZillitTheme.colors.divider)

        SectionNav(
            onOpenCalendar = onOpenCalendar,
            onOpenContacts = onOpenContacts,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun MailboxHeader(
    state: FolderDrawerState,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        UserAvatar(
            initials = state.avatarInitials,
            pictureKey = state.avatarPictureKey,
            thumbnailKey = state.avatarThumbnailKey,
            size = 48.dp,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.mailboxName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.mailboxEmail,
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (onClick != null) {
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = stringResource(R.string.email_choose_account),
                tint = ZillitTheme.colors.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * One folder.
 *
 * The selected row is a filled pill rather than a tint across the full drawer width —
 * inset, it reads as "this one", and the unread pill beside it stays legible because the
 * two do not share an edge.
 */
@Composable
private fun FolderRow(
    folder: EmailFolder,
    selected: Boolean,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = 1.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) ZillitTheme.colors.brandSoft else ZillitTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Icon(
            imageVector = folderIcon(folder.folderName),
            contentDescription = null,
            tint = if (selected) ZillitTheme.colors.brand else ZillitTheme.colors.textSecondary,
            modifier = Modifier.size(22.dp),
        )

        Text(
            text = folderDisplayName(folder.folderName),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) ZillitTheme.colors.brand else ZillitTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (folder.unreadCount > 0) {
            Text(
                text = folder.unreadCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) {
                    ZillitTheme.colors.textOnBrand
                } else {
                    ZillitTheme.colors.textSecondary
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) {
                            ZillitTheme.colors.brand
                        } else {
                            ZillitTheme.colors.textTertiary.copy(alpha = 0.15f)
                        },
                    )
                    .padding(
                        horizontal = ZillitTheme.spacing.sm,
                        vertical = ZillitTheme.spacing.xxs,
                    ),
            )
        }

        // Only a folder the user made can be renamed or deleted.
        if (onMore != null) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.email_folder_options),
                tint = ZillitTheme.colors.textTertiary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = onMore),
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actionIcon: ImageVector,
    actionDescription: String,
    onAction: () -> Unit,
) {
    Column {
        HorizontalDivider(
            color = ZillitTheme.colors.divider,
            modifier = Modifier.padding(
                horizontal = ZillitTheme.spacing.lg,
                vertical = ZillitTheme.spacing.xs,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ZillitTheme.spacing.xl,
                    end = ZillitTheme.spacing.md,
                    top = ZillitTheme.spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onAction, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = actionDescription,
                    tint = ZillitTheme.colors.brand,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Mail / Calendar / Contacts / Settings.
 *
 * Mail is the current section, shown active and doing nothing when tapped. v2 renders it
 * the same way and simply never attaches a listener — same outcome, but here it is a
 * decision the code states rather than an omission.
 */
@Composable
private fun SectionNav(
    onOpenCalendar: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavCell(Icons.Outlined.Mail, stringResource(R.string.email_nav_mail), active = true, onClick = null)
        NavCell(Icons.Outlined.CalendarMonth, stringResource(R.string.email_nav_calendar), false, onOpenCalendar)
        NavCell(Icons.Outlined.Contacts, stringResource(R.string.email_nav_contacts), false, onOpenContacts)
        NavCell(Icons.Outlined.Settings, stringResource(R.string.email_nav_settings), false, onOpenSettings)
    }
}

@Composable
private fun NavCell(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: (() -> Unit)?,
) {
    val tint = if (active) ZillitTheme.colors.brand else ZillitTheme.colors.textTertiary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

private fun folderIcon(folderName: String): ImageVector = when (folderName) {
    EmailFolders.INBOX -> Icons.Outlined.Inbox
    EmailFolders.SENT -> Icons.Outlined.Send
    EmailFolders.DRAFTS -> Icons.Outlined.Drafts
    EmailFolders.TRASH -> Icons.Outlined.DeleteOutline
    EmailFolders.SPAM, EmailFolders.JUNK -> Icons.Outlined.Report
    else -> Icons.Outlined.Folder
}
