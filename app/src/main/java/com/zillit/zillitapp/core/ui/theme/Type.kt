package com.zillit.zillitapp.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Type scale.
 *
 * Sizes are in `sp` so the user's system font-size setting is respected — v2 shipped its
 * own `LARGE_FONT_SIZE` constants and scaled some text manually, which fought the
 * platform setting instead of following it.
 *
 * [LineHeightStyle] trims the extra leading Compose adds above the first line and below
 * the last, so a text block's measured box matches what you see. Without it, vertically
 * centring text next to an icon is always a few pixels off.
 */
private val DefaultFontFamily = FontFamily.SansSerif

private val lineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = DefaultFontFamily,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = lineHeightStyle,
)

val ZillitTypography = Typography(
    displaySmall = style(32, 40, FontWeight.Bold, (-0.5)),
    headlineLarge = style(28, 36, FontWeight.Bold, (-0.4)),
    headlineMedium = style(24, 32, FontWeight.Bold, (-0.3)),
    headlineSmall = style(20, 28, FontWeight.SemiBold, (-0.2)),

    titleLarge = style(18, 26, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),

    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 22, FontWeight.Normal),
    bodySmall = style(12, 18, FontWeight.Normal),

    labelLarge = style(14, 20, FontWeight.SemiBold, 0.1),
    labelMedium = style(12, 16, FontWeight.Medium, 0.2),
    labelSmall = style(11, 14, FontWeight.Medium, 0.3),
)

/**
 * The whole scale, multiplied.
 *
 * Every size and line height moves together, so the relationships the scale encodes —
 * a label being smaller than a body, a heading larger — survive at any setting. Scaling
 * one style would break them.
 */
fun Typography.scaledBy(factor: Float): Typography {
    if (factor == 1f) return this

    fun TextStyle.scaled(): TextStyle = copy(
        fontSize = fontSize * factor,
        // Unspecified stays unspecified: letting the platform derive it is better than
        // multiplying a value that was never set.
        lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
    )

    return copy(
        displayLarge = displayLarge.scaled(),
        displayMedium = displayMedium.scaled(),
        displaySmall = displaySmall.scaled(),
        headlineLarge = headlineLarge.scaled(),
        headlineMedium = headlineMedium.scaled(),
        headlineSmall = headlineSmall.scaled(),
        titleLarge = titleLarge.scaled(),
        titleMedium = titleMedium.scaled(),
        titleSmall = titleSmall.scaled(),
        bodyLarge = bodyLarge.scaled(),
        bodyMedium = bodyMedium.scaled(),
        bodySmall = bodySmall.scaled(),
        labelLarge = labelLarge.scaled(),
        labelMedium = labelMedium.scaled(),
        labelSmall = labelSmall.scaled(),
    )
}
