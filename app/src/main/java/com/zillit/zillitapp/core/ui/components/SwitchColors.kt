package com.zillit.zillitapp.core.ui.components

import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * The app's switch colours, for both states.
 *
 * Every switch in the app was setting only `checkedThumbColor` and `checkedTrackColor` and
 * leaving the **off** state to Material's defaults. Those defaults are derived from the
 * Material colour scheme rather than from these tokens, and on the dark palette the result
 * is a near-black track on a near-black surface: a switch that is off is invisible, so there
 * is no way to tell a toggle from an empty row.
 *
 * Both states are therefore specified here, once, including the border that is what actually
 * makes an off switch findable against a dark ground.
 */
@Composable
fun zillitSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = ZillitTheme.colors.textOnBrand,
    checkedTrackColor = ZillitTheme.colors.brand,
    checkedBorderColor = ZillitTheme.colors.brand,

    // The off state: a light thumb on a sunken track, outlined so it reads as a control
    // rather than as a gap in the row.
    uncheckedThumbColor = ZillitTheme.colors.textSecondary,
    uncheckedTrackColor = ZillitTheme.colors.surfaceSunken,
    uncheckedBorderColor = ZillitTheme.colors.borderStrong,

    disabledCheckedThumbColor = ZillitTheme.colors.textOnBrand,
    disabledCheckedTrackColor = ZillitTheme.colors.brand.copy(alpha = DISABLED),
    disabledCheckedBorderColor = ZillitTheme.colors.brand.copy(alpha = DISABLED),
    disabledUncheckedThumbColor = ZillitTheme.colors.textTertiary,
    disabledUncheckedTrackColor = ZillitTheme.colors.surfaceSunken,
    disabledUncheckedBorderColor = ZillitTheme.colors.border,
)

private const val DISABLED = 0.45f
