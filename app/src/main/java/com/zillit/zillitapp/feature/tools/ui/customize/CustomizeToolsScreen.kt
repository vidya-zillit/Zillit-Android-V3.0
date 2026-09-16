package com.zillit.zillitapp.feature.tools.ui.customize

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
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
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Switching the project's tools on and off.
 *
 * A tick rather than a switch, and one Update button: the ticks are local until it is
 * pressed, so turning several tools off is one decision.
 */
@Composable
fun CustomizeToolsScreen(
    state: CustomizeToolsUiState,
    onToggle: (CustomizableTool) -> Unit,
    onSave: () -> Unit,
    onManageGroups: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background),
    ) {
        ZillitTopBar(
            title = stringResource(R.string.tools_customize_title),
            onBackClick = onBack,
            onHelpClick = null,
        )

        // Switching a tool on is half the job; the other half is deciding which section it
        // appears in, which is a different endpoint and a different screen.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surface)
                .clickable(onClick = onManageGroups)
                .padding(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.tool_groups_title),
                style = MaterialTheme.typography.bodyLarge,
                color = ZillitTheme.colors.brand,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = ZillitTheme.colors.textTertiary,
            )
        }
        HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)

        Box(modifier = Modifier.weight(1f)) {
            if (state.loading) {
                LoadingState()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.tools, key = { it.identifier }) { tool ->
                        ToolToggleRow(tool = tool, onClick = { onToggle(tool) })
                        HorizontalDivider(
                            color = ZillitTheme.colors.divider,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 64.dp),
                        )
                    }
                }
            }
        }

        PrimaryButton(
            text = stringResource(R.string.update),
            onClick = onSave,
            enabled = !state.saving && !state.loading,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(ZillitTheme.spacing.lg),
        )
    }
}

@Composable
private fun ToolToggleRow(tool: CustomizableTool, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(
                horizontal = ZillitTheme.spacing.md,
                vertical = ZillitTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ZillitTheme.colors.warningSoft,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = null,
                    tint = ZillitTheme.colors.accentWarm,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Text(
            text = tool.title,
            style = MaterialTheme.typography.bodyLarge,
            color = ZillitTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )

        // Only drawn when on. An empty space for "off" is what the design does, and it
        // keeps the eye on what is enabled rather than on a column of controls.
        if (tool.enabled) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = tool.title,
                tint = ZillitTheme.colors.textPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
