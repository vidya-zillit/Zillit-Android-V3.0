package com.zillit.zillitapp.feature.tools.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.rememberReorderableListState
import com.zillit.zillitapp.core.ui.components.reorderable
import com.zillit.zillitapp.core.ui.components.reorderableDragHandle
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.tools.data.ToolGroup

/**
 * Drag the sections of the Tools page into the order you want them.
 *
 * Per user, not per project, and the sheet says so — one person reordering their page must
 * not reorder everyone else's.
 *
 * Nothing is saved until Save is pressed: a drag is cheap to undo with Cancel, and a save
 * per crossing would be a dozen requests for one rearrangement.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReorderGroupsSheet(
    groups: List<ToolGroup>,
    order: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Seeded from the current order, then owned by the sheet. The live order keeps updating
    // underneath — a socket event, another device — and adopting those mid-drag would move
    // rows out from under the finger.
    var working by remember {
        mutableStateOf(
            (order.filter { id -> groups.any { it.identifier == id } } +
                groups.map { it.identifier }.filterNot { it in order }).distinct(),
        )
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableListState(
        listState = listState,
        onMove = { from, to ->
            working = working.toMutableList().apply { add(to, removeAt(from)) }
        },
        onDrop = { },
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.background,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                Text(
                    text = stringResource(R.string.tools_reorder_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ZillitTheme.colors.textPrimary,
                )
                TextButton(onClick = { onSave(working) }) {
                    Text(
                        text = stringResource(R.string.action_save),
                        color = ZillitTheme.colors.accentWarm,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text(
                text = stringResource(R.string.tools_reorder_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.padding(
                    horizontal = ZillitTheme.spacing.lg,
                    vertical = ZillitTheme.spacing.sm,
                ),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(horizontal = ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                itemsIndexed(working) { index, identifier ->
                    val name = groups.firstOrNull { it.identifier == identifier }?.name
                        ?: identifier

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ZillitTheme.colors.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .reorderable(reorderState, index)
                            // The gesture is on the whole row, not on the handles. A 24dp
                            // icon is a hard thing to find and hold, and the row is the
                            // obvious target — the handles stay as the affordance that says
                            // the row can be moved.
                            .reorderableDragHandle(reorderState, index),
                    ) {
                        Row(
                            modifier = Modifier.padding(ZillitTheme.spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // A handle at each edge, so the row can be grabbed from
                            // whichever side the thumb is already on.
                            DragHandleIcon()
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = ZillitTheme.colors.textPrimary,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = ZillitTheme.spacing.md),
                            )
                            DragHandleIcon()
                        }
                    }
                }
            }
        }
    }
}

/** Affordance only — the whole row carries the gesture. */
@Composable
private fun DragHandleIcon() {
    Icon(
        imageVector = Icons.Outlined.DragHandle,
        contentDescription = null,
        tint = ZillitTheme.colors.textTertiary,
        modifier = Modifier.size(24.dp),
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    items: List<String>,
    content: @Composable (Int, String) -> Unit,
) = items(items.size, key = { items[it] }) { index -> content(index, items[index]) }
