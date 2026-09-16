package com.zillit.zillitapp.feature.email.ui.folder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailFolder
import com.zillit.zillitapp.feature.email.domain.EmailFolders
import com.zillit.zillitapp.feature.email.ui.list.folderDisplayName

/**
 * Where to move a message — v2's `fragment_folder_selection`.
 *
 * The list excludes the folder the mail is already in, plus Sent and Drafts: neither is a
 * destination anyone can move mail into, and offering them produces a server rejection with
 * no explanation.
 *
 * v2 has **no empty state here** — it shows a spinner whose only exit is a non-empty list,
 * so a mailbox with nothing to move to spins forever. That is the one behaviour in this
 * screen deliberately not reproduced.
 */
@Composable
fun FolderPickerScreen(
    folders: List<EmailFolder>,
    sourceFolder: String,
    loading: Boolean,
    onPick: (EmailFolder) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val destinations = folders.filter { it.folderName.isMovableDestination(sourceFolder) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.surface),
    ) {
        ZillitTopBar(
            title = stringResource(R.string.email_move_to_folder_title),
            onBackClick = onBack,
            onHelpClick = null,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loading -> LoadingState()

                destinations.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.FolderOff,
                    title = stringResource(R.string.email_empty_title),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(destinations, key = { it.folderName }) { folder ->
                        FolderPickerRow(folder = folder, onClick = { onPick(folder) })
                        HorizontalDivider(
                            color = ZillitTheme.colors.divider,
                            thickness = 0.5.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPickerRow(folder: EmailFolder, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(56.dp)
            .padding(horizontal = ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Icon(
            imageVector = folderIcon(folder.folderName),
            contentDescription = null,
            tint = ZillitTheme.colors.textSecondary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = folderDisplayName(folder.folderName),
            style = MaterialTheme.typography.bodyLarge,
            color = ZillitTheme.colors.textPrimary,
        )
    }
}

/** Sent and Drafts are not destinations, and neither is where the mail already is. */
private fun String.isMovableDestination(sourceFolder: String): Boolean =
    this != sourceFolder && this != EmailFolders.SENT && this != EmailFolders.DRAFTS

private fun folderIcon(folderName: String): ImageVector = when (folderName) {
    EmailFolders.INBOX -> Icons.Outlined.Inbox
    EmailFolders.SENT -> Icons.Outlined.Send
    EmailFolders.DRAFTS -> Icons.Outlined.Drafts
    EmailFolders.TRASH -> Icons.Outlined.DeleteOutline
    EmailFolders.SPAM, EmailFolders.JUNK -> Icons.Outlined.Report
    else -> Icons.Outlined.Folder
}
