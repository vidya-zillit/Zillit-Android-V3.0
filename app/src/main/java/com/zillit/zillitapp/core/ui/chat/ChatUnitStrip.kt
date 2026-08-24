package com.zillit.zillitapp.core.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.labels.asServerText
import com.zillit.zillitapp.core.labels.resolve
import com.zillit.zillitapp.core.ui.components.CountBadge
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.chat.model.ChatUnit

/**
 * The unit strip that sits above the Home thread.
 *
 * Every unit is its own chat, so this is the primary navigation of the Home tab, not
 * decoration. Three behaviours are carried over from v2 deliberately:
 *
 *  - **Per-unit unread badges**, so you can see which thread moved without opening it.
 *  - **An overflow affordance** once the strip runs past what fits, carrying a red dot
 *    when any unit hidden past the fold has unread messages — otherwise activity in a
 *    unit you have to scroll to reach is invisible.
 *  - **Selection follows the badge**, i.e. tapping a unit is what clears it.
 *
 * The strip scrolls horizontally rather than wrapping: a production can have a dozen
 * units, and a wrapping strip would eat half the thread on a phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatUnitStrip(
    units: List<ChatUnit>,
    selectedUnitId: String?,
    onUnitSelected: (ChatUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var showAllUnits by remember { mutableStateOf(false) }

    // Keep the active unit in view after a rotation or an external switch (a badge tap
    // from the notification screen can select a unit that is scrolled off).
    LaunchedEffect(selectedUnitId, units) {
        val index = units.indexOfFirst { it.id == selectedUnitId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    // v2 shows the arrow once the strip exceeds what fits on screen and dots it when any
    // unit past that point is unread. Same rule, same threshold.
    val hasOverflow = units.size > VISIBLE_UNIT_THRESHOLD
    val hiddenUnread = units.drop(VISIBLE_UNIT_THRESHOLD).sumOf { it.badgeCount }

    Column(modifier = modifier.fillMaxWidth().background(ZillitTheme.colors.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    horizontal = ZillitTheme.spacing.md,
                    vertical = ZillitTheme.spacing.sm,
                ),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                items(units, key = { it.id }) { unit ->
                    UnitChip(
                        unit = unit,
                        selected = unit.id == selectedUnitId,
                        onClick = { onUnitSelected(unit) },
                    )
                }
            }

            if (hasOverflow) {
                Box(
                    modifier = Modifier
                        .padding(end = ZillitTheme.spacing.md)
                        .clip(CircleShape)
                        .clickable { showAllUnits = true }
                        .padding(ZillitTheme.spacing.xs),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = stringResource(R.string.unit_show_all),
                        tint = ZillitTheme.colors.brand,
                        modifier = Modifier.size(20.dp),
                    )
                    if (hiddenUnread > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(7.dp)
                                .background(ZillitTheme.colors.danger, CircleShape),
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = ZillitTheme.colors.divider)
    }

    if (showAllUnits) {
        ModalBottomSheet(
            onDismissRequest = { showAllUnits = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ZillitTheme.colors.surface,
        ) {
            Text(
                text = stringResource(R.string.unit_all_title),
                style = MaterialTheme.typography.titleMedium,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.padding(
                    start = ZillitTheme.spacing.lg,
                    end = ZillitTheme.spacing.lg,
                    bottom = ZillitTheme.spacing.sm,
                ),
            )
            units.forEach { unit ->
                UnitRow(
                    unit = unit,
                    selected = unit.id == selectedUnitId,
                    onClick = {
                        onUnitSelected(unit)
                        showAllUnits = false
                    },
                )
            }
        }
    }
}

@Composable
private fun UnitChip(unit: ChatUnit, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
            .background(
                if (selected) ZillitTheme.colors.brand else ZillitTheme.colors.surfaceSunken,
            )
            .border(
                width = 1.dp,
                color = if (selected) ZillitTheme.colors.brand else ZillitTheme.colors.border,
                shape = RoundedCornerShape(ZillitTheme.shapes.pill),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (unit.isPrivate) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = stringResource(R.string.unit_private),
                tint = if (selected) {
                    ZillitTheme.colors.textOnBrand
                } else {
                    ZillitTheme.colors.textTertiary
                },
                modifier = Modifier.size(12.dp),
            )
        }

        Text(
            // Unit names arrive as server label KEYS (`home_unit_notices`). Resolved at
            // render rather than at write time, so switching language needs no reload and
            // no second copy of the name in Realm.
            text = unit.name.asServerText().resolve(),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                ZillitTheme.colors.textOnBrand
            } else {
                ZillitTheme.colors.textSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // The badge stays visible on the selected chip too — it clears when the unit is
        // actually marked read, not the instant it is tapped.
        if (unit.badgeCount > 0) {
            UnitBadge(count = unit.badgeCount, onBrand = selected)
        }
    }
}

/**
 * On a selected chip the pill inverts — white on the brand fill — because red on orange
 * vibrates badly at this size.
 */
@Composable
private fun UnitBadge(count: Int, onBrand: Boolean) {
    CountBadge(
        count = count,
        containerColor = if (onBrand) ZillitTheme.colors.textOnBrand else ZillitTheme.colors.danger,
        contentColor = if (onBrand) ZillitTheme.colors.brand else ZillitTheme.colors.textOnBrand,
    )
}

@Composable
private fun UnitRow(unit: ChatUnit, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .size(width = 3.dp, height = 20.dp)
                .background(
                    if (selected) ZillitTheme.colors.brand else androidx.compose.ui.graphics.Color.Transparent,
                    RoundedCornerShape(2.dp),
                ),
        )
        if (unit.isPrivate) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = stringResource(R.string.unit_private),
                tint = ZillitTheme.colors.textTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = unit.name.asServerText().resolve(),
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) ZillitTheme.colors.brand else ZillitTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (unit.badgeCount > 0) UnitBadge(count = unit.badgeCount, onBrand = false)
    }
}

/** v2 shows the overflow arrow past three units. Kept identical so the UI reads the same. */
private const val VISIBLE_UNIT_THRESHOLD = 3
