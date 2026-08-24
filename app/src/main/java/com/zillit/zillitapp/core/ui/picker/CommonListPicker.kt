package com.zillit.zillitapp.core.ui.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.labels.LocalLabels
import com.zillit.zillitapp.core.labels.resolveLabel
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * One selectable row.
 *
 * v3's equivalent of v2's `CommonListModel(param1, param2, …)`, but with named fields —
 * `param1`/`param2` gave no clue which was the title and which the subtitle, so call
 * sites had to be read to find out.
 */
data class CommonListItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /**
     * True when [title]/[subtitle] are server label KEYS rather than display text.
     * The picker resolves them through the dictionary, so callers do not each repeat it.
     */
    val isLabelKey: Boolean = false,
)

/**
 * Reusable searchable selection list, shown as a bottom sheet.
 *
 * Replaces v2's `CommonSearchListActivity`, which was launched with intent extras and
 * returned a result — workable, but it meant every caller serialised its list into an
 * Intent and unpacked a result code. Here the caller passes a list and gets a callback,
 * so a picker can be opened from anywhere including inside another sheet.
 *
 * Supports single and multi select. Search filters on the *resolved* text, so a user
 * searching "Television" matches even though the underlying value is
 * `television_label` — searching raw keys is what v2 got wrong here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommonListPicker(
    title: String,
    items: List<CommonListItem>,
    onDismiss: () -> Unit,
    onSingleSelected: (CommonListItem) -> Unit = {},
    onMultiSelected: (List<CommonListItem>) -> Unit = {},
    selectedIds: Set<String> = emptySet(),
    isMultiSelect: Boolean = false,
    searchable: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val labels = LocalLabels.current

    // Saveable so a rotate mid-selection does not discard the query or the ticks.
    var query by rememberSaveable { mutableStateOf("") }
    var checked by rememberSaveable { mutableStateOf(selectedIds) }

    val resolved = remember(items, labels) {
        items.map { item ->
            if (!item.isLabelKey) {
                item
            } else {
                item.copy(
                    title = labels.resolveLabel(item.title),
                    subtitle = item.subtitle?.let { labels.resolveLabel(it) },
                )
            }
        }
    }

    val filtered = remember(resolved, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            resolved
        } else {
            resolved.filter {
                it.title.contains(trimmed, ignoreCase = true) ||
                    it.subtitle?.contains(trimmed, ignoreCase = true) == true
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg)
                .padding(bottom = ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = ZillitTheme.colors.textPrimary,
            )

            if (searchable && resolved.size >= SEARCH_THRESHOLD) {
                PickerSearchField(query = query, onQueryChange = { query = it })
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp)) {
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.project_search_empty_title),
                        description = stringResource(
                            R.string.project_search_empty_description,
                            query,
                        ),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT),
                ) {
                    items(items = filtered, key = { it.id }) { item ->
                        PickerRow(
                            item = item,
                            isSelected = item.id in checked,
                            isMultiSelect = isMultiSelect,
                            onClick = {
                                if (isMultiSelect) {
                                    checked = if (item.id in checked) {
                                        checked - item.id
                                    } else {
                                        checked + item.id
                                    }
                                } else {
                                    onSingleSelected(item)
                                    onDismiss()
                                }
                            },
                        )
                    }
                }
            }

            // Confirm only exists for multi-select; a single-select row commits on tap,
            // and an extra confirm there is a step with no purpose.
            if (isMultiSelect) {
                PrimaryButton(
                    text = stringResource(R.string.action_done),
                    onClick = {
                        onMultiSelected(resolved.filter { it.id in checked })
                        onDismiss()
                    },
                    enabled = checked.isNotEmpty(),
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    item: CommonListItem,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isMultiSelect) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(checkedColor = ZillitTheme.colors.brand),
                )
            } else if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = ZillitTheme.colors.brand,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = ZillitTheme.colors.border,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        HorizontalDivider(color = ZillitTheme.colors.divider)
    }
}

@Composable
private fun PickerSearchField(query: String, onQueryChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = ZillitTheme.colors.textPrimary,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(ZillitTheme.colors.brand),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = ZillitTheme.colors.surfaceSunken,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(
                    ZillitTheme.shapes.pill,
                ),
            )
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.md),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = ZillitTheme.colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.weight(1f).padding(start = ZillitTheme.spacing.sm)) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.action_search),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZillitTheme.colors.textTertiary,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

/** Below this many rows a search field is noise rather than help. */
private const val SEARCH_THRESHOLD = 8

private val LIST_MAX_HEIGHT = 420.dp
