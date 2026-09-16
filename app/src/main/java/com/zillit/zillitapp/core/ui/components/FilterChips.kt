package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * A togglable chip, themed once.
 *
 * Material's own `FilterChip` is fine; what was being copied screen to screen was the
 * *colour block* — brandSoft when selected, surface when not, border following suit — and
 * the leading check that only appears once selected. Three calendar screens had three
 * slightly different versions of it, and email's search adds three more chip rows
 * (folders, fields, status).
 */
@Composable
fun ZillitFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = ZillitTheme.colors.surface,
            labelColor = ZillitTheme.colors.textSecondary,
            selectedContainerColor = ZillitTheme.colors.brandSoft,
            selectedLabelColor = ZillitTheme.colors.brand,
            selectedLeadingIconColor = ZillitTheme.colors.brand,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = ZillitTheme.colors.border,
            selectedBorderColor = ZillitTheme.colors.brand,
        ),
        modifier = modifier,
    )
}

/** One row of chips, scrolling sideways. For a set too long to wrap tidily — folders. */
@Composable
fun FilterChipRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        content()
    }
}

/** Chips that wrap onto as many lines as they need. For a short, fixed set — fields. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterChipFlow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        content()
    }
}
