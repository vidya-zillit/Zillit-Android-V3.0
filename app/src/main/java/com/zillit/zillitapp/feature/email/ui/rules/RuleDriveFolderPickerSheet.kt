package com.zillit.zillitapp.feature.email.ui.rules

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.RuleDriveFolder

/** Which part of Drive is being browsed. */
enum class DriveSection { MINE, SHARED }

data class DriveFolderPickerState(
    val section: DriveSection = DriveSection.MINE,
    /** Deepest last. Empty means the section's root. */
    val breadcrumbs: List<RuleDriveFolder> = emptyList(),
    val folders: List<RuleDriveFolder> = emptyList(),
    val loading: Boolean = false,
) {
    val openFolder: RuleDriveFolder? get() = breadcrumbs.lastOrNull()

    /**
     * Whether the folder currently open can be selected.
     *
     * False at the root — there is nothing open to select — and false inside a folder the
     * user may read but not write, which is a real case for anything shared with them.
     */
    val canSelect: Boolean get() = openFolder?.canEdit == true
}

/**
 * Where a rule files attachments — v2's `sheet_rule_drive_folder_picker`.
 *
 * **You select the folder you are standing in, not one you tap.** Tapping a row opens it;
 * the button at the bottom selects whatever is open. That is v2's model and it is the right
 * one here, because a rule usually files into a nested folder and a tap-to-select picker
 * makes the leaf unreachable.
 *
 * The button says which of three states it is in — nothing open, open but read-only, or
 * ready — rather than being an inert grey rectangle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleDriveFolderPickerSheet(
    state: DriveFolderPickerState,
    onSectionChange: (DriveSection) -> Unit,
    onOpenFolder: (RuleDriveFolder) -> Unit,
    onNavigateUp: () -> Unit,
    onCreateFolder: () -> Unit,
    onSelect: (RuleDriveFolder) -> Unit,
    onDismiss: () -> Unit,
) {
    // Back walks up the folder tree before it closes the sheet — otherwise three taps in
    // and one back gesture loses the whole path.
    BackHandler(enabled = state.breadcrumbs.isNotEmpty()) { onNavigateUp() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.9f)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.brand,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(ZillitTheme.spacing.xs),
                )

                Text(
                    text = stringResource(R.string.email_rule_drive_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ZillitTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )

                IconButton(onClick = onCreateFolder) {
                    Icon(
                        imageVector = Icons.Outlined.CreateNewFolder,
                        contentDescription = stringResource(
                            R.string.email_rule_drive_create_folder,
                        ),
                        tint = ZillitTheme.colors.brand,
                    )
                }
            }

            TabRow(
                selectedTabIndex = state.section.ordinal,
                containerColor = ZillitTheme.colors.surface,
                contentColor = ZillitTheme.colors.brand,
            ) {
                Tab(
                    selected = state.section == DriveSection.MINE,
                    onClick = { onSectionChange(DriveSection.MINE) },
                    text = { Text(stringResource(R.string.email_drive_section_mine)) },
                )
                Tab(
                    selected = state.section == DriveSection.SHARED,
                    onClick = { onSectionChange(DriveSection.SHARED) },
                    text = { Text(stringResource(R.string.email_drive_section_shared)) },
                )
            }

            Breadcrumb(state.breadcrumbs)

            HorizontalDivider(color = ZillitTheme.colors.divider)

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.loading -> LoadingState()

                    state.folders.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.email_rule_drive_picker_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZillitTheme.colors.textTertiary,
                        )
                    }

                    else -> LazyColumn {
                        items(state.folders, key = { it.id }) { folder ->
                            DriveFolderRow(
                                folder = folder,
                                viewOnly = !folder.canEdit,
                                onClick = { onOpenFolder(folder) },
                            )
                        }
                    }
                }
            }

            PrimaryButton(
                text = when {
                    state.openFolder == null ->
                        stringResource(R.string.email_rule_drive_picker_open_to_select)

                    !state.canSelect ->
                        stringResource(R.string.email_rule_drive_view_only_folder)

                    else -> stringResource(
                        R.string.email_rule_drive_select_this,
                        state.openFolder?.name.orEmpty(),
                    )
                },
                onClick = { state.openFolder?.let(onSelect) },
                enabled = state.canSelect,
                modifier = Modifier.padding(ZillitTheme.spacing.md),
            )
        }
    }
}

/** `Drive  ›  Invoices  ›  2026` — where you are, in one scrolling line. */
@Composable
private fun Breadcrumb(breadcrumbs: List<RuleDriveFolder>) {
    val path = (listOf(stringResource(R.string.email_rule_drive_root)) + breadcrumbs.map { it.name })
        .joinToString("  ›  ")

    Text(
        text = path,
        style = MaterialTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = ZillitTheme.spacing.lg,
                vertical = ZillitTheme.spacing.sm,
            ),
    )
}

/**
 * One folder — v2's `item_rule_drive_folder`.
 *
 * A view-only folder is still browsable: its children may well be writable, and blocking the
 * row would hide them. Only the selection is refused, by the button.
 */
@Composable
private fun DriveFolderRow(
    folder: RuleDriveFolder,
    viewOnly: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = ZillitTheme.colors.brand,
            modifier = Modifier.size(22.dp),
        )

        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyLarge,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (viewOnly) {
            Text(
                text = stringResource(R.string.email_rule_drive_view_only),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.textTertiary,
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}
