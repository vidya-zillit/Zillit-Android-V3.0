package com.zillit.zillitapp.feature.tools.ui.customize

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.tools.data.ToolGroup

/**
 * The sections themselves: what exists, and which tool is in which.
 *
 * Two lists rather than one nested one. A group is renamed or removed from the top list; a
 * tool is moved from the bottom one. Nesting the tools under their groups would make
 * "which section is this tool in" the hard question, and that is the one being answered.
 */
@Composable
fun ManageToolGroupsScreen(
    state: ManageToolGroupsUiState,
    onBack: () -> Unit,
    onAddGroup: () -> Unit,
    onGroupClick: (ToolGroup) -> Unit,
    onToolClick: (GroupedTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background),
    ) {
        ZillitTopBar(
            title = stringResource(R.string.tool_groups_title),
            onBackClick = onBack,
            onHelpClick = null,
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SectionLabel(stringResource(R.string.tool_groups_section_groups))
            }

            items(state.groups, key = { it.identifier }) { group ->
                RowItem(
                    title = group.name,
                    // A default group can be reordered and filled but not renamed or
                    // deleted, so tapping it would offer nothing.
                    subtitle = null,
                    enabled = !group.systemDefined,
                    onClick = { onGroupClick(group) },
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ZillitTheme.colors.surface)
                        .clickable(onClick = onAddGroup)
                        .padding(ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = ZillitTheme.colors.brand,
                    )
                    Text(
                        text = stringResource(R.string.tool_groups_add),
                        style = MaterialTheme.typography.bodyLarge,
                        color = ZillitTheme.colors.brand,
                    )
                }
            }

            item {
                SectionLabel(stringResource(R.string.tool_groups_section_tools))
            }

            items(state.tools, key = { it.identifier }) { tool ->
                RowItem(
                    title = tool.title,
                    subtitle = tool.groupName.ifBlank {
                        stringResource(R.string.tools_ungrouped)
                    },
                    enabled = true,
                    onClick = { onToolClick(tool) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = ZillitTheme.colors.accentWarm,
        modifier = Modifier.padding(
            start = ZillitTheme.spacing.lg,
            end = ZillitTheme.spacing.lg,
            top = ZillitTheme.spacing.lg,
            bottom = ZillitTheme.spacing.xs,
        ),
    )
}

@Composable
private fun RowItem(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = ZillitTheme.colors.surface,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                    .padding(
                        horizontal = ZillitTheme.spacing.lg,
                        vertical = ZillitTheme.spacing.md,
                    ),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        ZillitTheme.colors.textPrimary
                    } else {
                        ZillitTheme.colors.textTertiary
                    },
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
            }
            HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)
        }
    }
}
