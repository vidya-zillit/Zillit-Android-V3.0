package com.zillit.zillitapp.feature.tools.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.components.EmptyState
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import com.zillit.zillitapp.feature.tools.ui.customize.ManageToolGroupsViewModel
import com.zillit.zillitapp.feature.tools.ui.customize.ManageToolGroupsScreen
import com.zillit.zillitapp.feature.tools.ui.customize.GroupedTool
import com.zillit.zillitapp.feature.tools.data.ToolGroup
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.components.SheetAction
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.components.ActionSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.Icons
import com.zillit.zillitapp.core.ui.components.MessageBarBox
import com.zillit.zillitapp.core.ui.components.ShowOnce
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import com.zillit.zillitapp.core.ui.components.rememberMessageBar
import com.zillit.zillitapp.core.ui.resolve
import com.zillit.zillitapp.core.ui.toUiText
import com.zillit.zillitapp.feature.tools.ui.customize.CustomizeToolsScreen
import com.zillit.zillitapp.feature.tools.ui.customize.CustomizeToolsViewModel
import com.zillit.zillitapp.feature.tools.ui.list.ReorderGroupsSheet
import com.zillit.zillitapp.feature.tools.ui.list.ToolInfoDialog
import com.zillit.zillitapp.feature.tools.ui.list.ToolRowState
import com.zillit.zillitapp.feature.tools.ui.list.ToolsScreen
import com.zillit.zillitapp.feature.tools.ui.list.ToolsViewModel

/**
 * The Tools tab.
 *
 * Tapping a tile opens the tool. Until a tool's own screen exists its spec has no
 * destination, and the tile says where the tool can be used instead of doing nothing — which
 * is what a missing branch in v2's dispatch `when` does today.
 */
@Composable
fun ToolsRoute(
    onOpenCustomize: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var reordering by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<ToolRowState?>(null) }
    var unavailable by remember { mutableStateOf<ToolRowState?>(null) }

    val messages = rememberMessageBar()
    messages.ShowOnce(error?.toUiText()?.resolve()) { viewModel.consumeError() }

    MessageBarBox(state = messages, modifier = modifier) {
        ToolsScreen(
            state = state,
            onQueryChange = viewModel::setQuery,
            onOpenCustomize = onOpenCustomize,
            onReorder = { reordering = true },
            onToolClick = { tool -> if (!tool.openable) unavailable = tool },
            onToolInfo = { info = it },
        )
    }

    // The ⓘ explainer, with the tutorial and help links this tool has.
    info?.let { tool ->
        ToolInfoDialog(tool = tool, onDismiss = { info = null })
    }

    unavailable?.let { tool ->
        ZillitConfirmDialog(
            title = tool.title,
            message = stringResource(R.string.tools_not_on_mobile, tool.title),
            confirmLabel = stringResource(R.string.ok),
            dismissLabel = null,
            onConfirm = { unavailable = null },
            onDismiss = { unavailable = null },
        )
    }

    if (reordering) {
        val groups by viewModel.groupsForReorder.collectAsStateWithLifecycle()
        val order by viewModel.orderForReorder.collectAsStateWithLifecycle()

        ReorderGroupsSheet(
            groups = groups,
            order = order,
            onSave = {
                viewModel.saveOrder(it)
                reordering = false
            },
            onDismiss = { reordering = false },
        )
    }
}

@Composable
fun CustomizeToolsRoute(
    onBack: () -> Unit,
    onManageGroups: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CustomizeToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()

    var confirming by remember { mutableStateOf<String?>(null) }

    // Checked here as well as on the card that opens it. This screen's Update is a
    // project-wide change, so reaching it another way must not be enough to make one.
    if (!isAdmin) {
        AdminOnly(onBack = onBack, modifier = modifier)
        return
    }

    val messages = rememberMessageBar()
    messages.ShowOnce(error?.toUiText()?.resolve()) { viewModel.consumeError() }

    MessageBarBox(state = messages, modifier = modifier) {
        CustomizeToolsScreen(
            state = state,
            // Switching a tool **off** that was on when the screen opened is the one thing
            // here with consequences beyond this screen, so it asks first. Turning one on
            // does not.
            onToggle = { tool ->
                if (viewModel.needsWarning(tool.identifier)) {
                    confirming = tool.identifier
                } else {
                    viewModel.toggle(tool.identifier)
                }
            },
            onSave = { viewModel.save(onBack) },
            onManageGroups = onManageGroups,
            onBack = onBack,
        )
    }

    confirming?.let { identifier ->
        ZillitConfirmDialog(
            title = stringResource(R.string.alert),
            message = stringResource(R.string.tools_disable_warning),
            confirmLabel = stringResource(R.string.yes),
            dismissLabel = stringResource(R.string.cancel),
            isDestructive = true,
            onConfirm = {
                confirming = null
                viewModel.toggle(identifier)
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
fun ManageToolGroupsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManageToolGroupsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()

    var naming by remember { mutableStateOf<ToolGroup?>(null) }
    var creating by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf<ToolGroup?>(null) }
    var deleting by remember { mutableStateOf<ToolGroup?>(null) }
    var moving by remember { mutableStateOf<GroupedTool?>(null) }

    if (!isAdmin) {
        AdminOnly(onBack = onBack, modifier = modifier)
        return
    }

    val messages = rememberMessageBar()
    messages.ShowOnce(error?.toUiText()?.resolve()) { viewModel.consumeError() }

    MessageBarBox(state = messages, modifier = modifier) {
        ManageToolGroupsScreen(
            state = state,
            onBack = onBack,
            onAddGroup = { creating = true },
            onGroupClick = { groupMenu = it },
            onToolClick = { moving = it },
        )
    }

    if (creating) {
        GroupNameDialog(
            title = stringResource(R.string.tool_groups_add),
            initial = "",
            onConfirm = {
                creating = false
                viewModel.createGroup(it)
            },
            onDismiss = { creating = false },
        )
    }

    naming?.let { group ->
        GroupNameDialog(
            title = stringResource(R.string.tool_groups_rename),
            initial = group.name,
            onConfirm = {
                naming = null
                viewModel.renameGroup(group, it)
            },
            onDismiss = { naming = null },
        )
    }

    groupMenu?.let { group ->
        ActionSheet(
            title = group.name,
            onDismiss = { groupMenu = null },
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.tool_groups_rename),
                    icon = Icons.Outlined.DriveFileRenameOutline,
                    onClick = {
                        groupMenu = null
                        naming = group
                    },
                ),
                SheetAction(
                    label = stringResource(R.string.tool_groups_delete),
                    icon = Icons.Outlined.DeleteOutline,
                    destructive = true,
                    onClick = {
                        groupMenu = null
                        deleting = group
                    },
                ),
            ),
        )
    }

    deleting?.let { group ->
        ZillitConfirmDialog(
            title = stringResource(R.string.tool_groups_delete),
            message = stringResource(R.string.tool_groups_delete_message, group.name),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(R.string.cancel),
            isDestructive = true,
            onConfirm = {
                deleting = null
                viewModel.deleteGroup(group)
            },
            onDismiss = { deleting = null },
        )
    }

    // Every group, plus "remove from group" — which the service models as a move to a blank
    // identifier rather than as a delete of the membership.
    moving?.let { tool ->
        ActionSheet(
            title = tool.title,
            onDismiss = { moving = null },
            actions = state.groups.map { group ->
                SheetAction(
                    label = group.name,
                    icon = Icons.Outlined.Folder,
                    onClick = {
                        moving = null
                        viewModel.move(tool, group.identifier)
                    },
                )
            } + SheetAction(
                label = stringResource(R.string.tool_groups_remove_from_group),
                icon = Icons.Outlined.FolderOff,
                onClick = {
                    moving = null
                    viewModel.move(tool, "")
                },
            ),
        )
    }
}

/** One field, one button. Create and rename differ only in the title. */
@Composable
private fun GroupNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FormTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.tool_groups_name_hint),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        containerColor = ZillitTheme.colors.surface,
    )
}

/**
 * What a non-admin sees if they reach an admin screen.
 *
 * Says so rather than closing itself: a screen that vanishes on open looks like a crash,
 * and the user has no way to tell that the refusal was deliberate.
 */
@Composable
private fun AdminOnly(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ZillitTopBar(
            title = stringResource(R.string.tools_customize_title),
            onBackClick = onBack,
            onHelpClick = null,
        )
        EmptyState(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.tools_admin_only),
            description = "",
        )
    }
}
