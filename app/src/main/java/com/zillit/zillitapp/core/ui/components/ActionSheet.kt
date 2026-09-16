package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/** One choice in an [ActionSheet]. */
data class SheetAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)

/**
 * A list of actions for the thing that was long-pressed or whose ⋮ was tapped.
 *
 * Replaces `DropdownMenu` for row-level menus, which needs a `Box` anchor in the row it
 * belongs to — a menu declared at the screen's root, as is natural when the target is
 * screen state, anchors to the root instead and opens in the corner or off-screen
 * entirely. A sheet has no anchor to get wrong, and it gives a 48dp target for each action
 * rather than a 32dp one at the edge of the screen.
 *
 * @param title what the actions apply to, so a sheet opened from the wrong row is obvious.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ZillitTheme.colors.textPrimary,
                    modifier = Modifier.padding(
                        start = ZillitTheme.spacing.xl,
                        end = ZillitTheme.spacing.xl,
                        bottom = ZillitTheme.spacing.md,
                    ),
                )
                HorizontalDivider(color = ZillitTheme.colors.divider)
            }

            actions.forEach { action ->
                val tint = if (action.destructive) {
                    ZillitTheme.colors.danger
                } else {
                    ZillitTheme.colors.textPrimary
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = action.onClick)
                        .padding(
                            horizontal = ZillitTheme.spacing.xl,
                            vertical = ZillitTheme.spacing.lg,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = if (action.destructive) {
                            ZillitTheme.colors.danger
                        } else {
                            ZillitTheme.colors.textSecondary
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tint,
                    )
                }
            }

            Spacer(modifier = Modifier.padding(bottom = ZillitTheme.spacing.md))
        }
    }
}
