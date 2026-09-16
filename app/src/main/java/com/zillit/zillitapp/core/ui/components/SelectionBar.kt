package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * One action offered while a selection is active.
 *
 * [enabled] exists because email's actions are conditional on *where* the selection is —
 * Move is meaningless in Drafts, Delete Permanently only makes sense in Trash — and a
 * greyed action that explains itself beats an action bar whose buttons appear and vanish
 * as the user selects different rows.
 */
data class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /** Destructive actions tint red. Everything else follows the brand. */
    val destructive: Boolean = false,
)

/**
 * The bar shown while rows are selected: a count, a way out, and the things you can do.
 *
 * Chat had this first ([com.zillit.zillitapp.core.ui.chat.ChatSelectionBar]) with exactly
 * one confirm action, because a chat selection is only ever "delete these" or "share
 * these". Email selects up to thirty mails and then offers delete / delete-permanently /
 * move, so the action is a **list** here rather than a single lambda.
 *
 * @param limit when set, the count renders as "3 / 30" — email caps a selection at 30 and
 *   silently ignoring the 31st tap is the kind of thing users read as a broken screen.
 */
@Composable
fun SelectionBar(
    count: Int,
    actions: List<SelectionAction>,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    limit: Int? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = ZillitTheme.colors.divider)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surface)
                .navigationBarsPadding()
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = ZillitTheme.colors.textSecondary,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onCancel),
            )

            Text(
                text = if (limit == null) {
                    "$count ${stringResource(R.string.selected)}"
                } else {
                    "$count / $limit ${stringResource(R.string.selected)}"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )

            actions.forEach { action ->
                SelectionActionButton(action)
            }
        }
    }
}

@Composable
private fun SelectionActionButton(action: SelectionAction) {
    val tint: Color = when {
        !action.enabled -> ZillitTheme.colors.textTertiary
        action.destructive -> ZillitTheme.colors.danger
        else -> ZillitTheme.colors.brand
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(enabled = action.enabled, onClick = action.onClick)
            .padding(horizontal = ZillitTheme.spacing.xs),
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
