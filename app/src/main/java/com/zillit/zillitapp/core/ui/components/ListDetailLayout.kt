package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.window.LocalWindowSize
import com.zillit.zillitapp.core.ui.window.WindowSize

/**
 * A list beside the thing it opens — the canonical list-detail layout, sized by the window.
 *
 * One component for every screen shaped this way (mail, conversations, home units), so a
 * foldable, a tablet and a phone in landscape all behave alike and no feature decides the
 * rule for itself. The decision is the window's width, never the device: a fold is a phone
 * closed and a tablet open, and split-screen makes any tablet a phone.
 *
 * | Window | Panes |
 * |---|---|
 * | [WindowSize.Compact] | one — the list, or [detail] on its own when [showDetail] |
 * | [WindowSize.Medium] | list + detail |
 * | [WindowSize.Expanded] | [navigation] + list + detail |
 *
 * On a single pane the caller owns the transition between list and detail — usually a
 * navigation route, so back and process death work as they do everywhere else — and passes
 * [showDetail] = false to keep the list up. Multi-pane always draws both, with
 * [detailPlaceholder] filling the detail pane until something is selected.
 *
 * Widths follow Material's list-detail guidance: the list pane keeps a fixed width so its
 * rows do not reflow as the detail changes, and the detail takes the remainder.
 */
@Composable
fun ListDetailLayout(
    /** Whether the caller wants the detail drawn — on Compact this replaces the list. */
    showDetail: Boolean,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** A permanent navigation column, drawn on Expanded only. Null means none. */
    navigation: (@Composable () -> Unit)? = null,
    /** What the detail pane shows while nothing is selected. */
    detailPlaceholder: @Composable () -> Unit = {},
    windowSize: WindowSize = LocalWindowSize.current,
    listWidth: Dp = ListDetailDefaults.ListWidth,
    navigationWidth: Dp = ListDetailDefaults.NavigationWidth,
    /** The list — last, so it reads as the layout's content at the call site. */
    list: @Composable () -> Unit,
) {
    when (windowSize) {
        WindowSize.Compact -> Box(modifier.fillMaxSize()) {
            if (showDetail) detail() else list()
        }

        WindowSize.Medium, WindowSize.Expanded -> Row(modifier.fillMaxSize()) {
            if (windowSize.isExpanded && navigation != null) {
                Box(Modifier.width(navigationWidth).fillMaxHeight()) { navigation() }
                PaneDivider()
            }

            Box(Modifier.width(listWidth).fillMaxHeight()) { list() }
            PaneDivider()

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(ZillitTheme.colors.background),
            ) {
                if (showDetail) detail() else detailPlaceholder()
            }
        }
    }
}

/** The hairline between panes — the theme's divider, one pixel wide on every density. */
@Composable
private fun PaneDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(ZillitTheme.colors.divider),
    )
}

object ListDetailDefaults {
    /** Wide enough for a sender, a subject and a date on one row without truncating all three. */
    val ListWidth: Dp = 380.dp

    /** A folder name and its count, with room for a nested folder's indent. */
    val NavigationWidth: Dp = 280.dp
}

/**
 * Whether this window draws the detail beside the list rather than instead of it.
 *
 * The question every list screen has to ask before it acts on a tap: navigate to the
 * detail, or select it in place. Answered here so the rule lives with the layout.
 */
val WindowSize.isMultiPane: Boolean get() = isAtLeastMedium
