package com.zillit.zillitapp.core.ui.window

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How wide the window is right now, in terms the UI can act on.
 *
 * The whole app reads this instead of branching on device type or orientation, so a
 * phone in landscape, a tablet in portrait, a foldable and a split-screen window all
 * fall out of the same rule. v2 had no alternate layout resources at all — every one of
 * its layouts was a single portrait design that stretched — so there is nothing to port
 * here; this is the mechanism that replaces it.
 */
enum class WindowSize {
    /** Phone portrait, or a narrow split-screen pane. */
    Compact,

    /** Phone landscape, small tablet portrait, half a large foldable. */
    Medium,

    /** Tablet landscape, desktop window. */
    Expanded,
    ;

    val isCompact: Boolean get() = this == Compact
    val isAtLeastMedium: Boolean get() = this != Compact
    val isExpanded: Boolean get() = this == Expanded
}

val LocalWindowSize = staticCompositionLocalOf { WindowSize.Compact }

@Composable
@ReadOnlyComposable
fun currentWindowSize(): WindowSize = LocalWindowSize.current

fun WindowSizeClass.toWindowSize(): WindowSize = when (widthSizeClass) {
    WindowWidthSizeClass.Compact -> WindowSize.Compact
    WindowWidthSizeClass.Medium -> WindowSize.Medium
    else -> WindowSize.Expanded
}

/**
 * Horizontal page padding per size. Content stays readable on a tablet instead of
 * running edge to edge across 1,200dp.
 */
@Composable
@ReadOnlyComposable
fun WindowSize.horizontalPadding(): Dp = when (this) {
    WindowSize.Compact -> 16.dp
    WindowSize.Medium -> 24.dp
    WindowSize.Expanded -> 32.dp
}

/**
 * Cap on the width of a centred content column. Long lines are hard to read, so on
 * wide windows the content is centred rather than stretched.
 */
@Composable
@ReadOnlyComposable
fun WindowSize.contentMaxWidth(): Dp = when (this) {
    WindowSize.Compact -> Dp.Unspecified
    WindowSize.Medium -> 640.dp
    WindowSize.Expanded -> 840.dp
}

/** Grid columns for card lists. */
fun WindowSize.gridColumns(): Int = when (this) {
    WindowSize.Compact -> 1
    WindowSize.Medium -> 2
    WindowSize.Expanded -> 3
}
