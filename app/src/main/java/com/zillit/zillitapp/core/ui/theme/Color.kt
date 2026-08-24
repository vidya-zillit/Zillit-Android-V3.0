package com.zillit.zillitapp.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand constants. These are the only raw hex values in the app — everything else
 * refers to a semantic token from [ZillitColors].
 *
 * Hues are carried over from v2 (`accent_primary #FC9404`, `zillit_primary #1353D1`,
 * `zillit_text #12213F`) so v3 still reads as Zillit.
 */
private object Brand {
    val Orange = Color(0xFFFC9404)
    val OrangeDark = Color(0xFFC87603)
    val OrangeSoft = Color(0xFFFEF3E0)

    /** v2's `accent_warm` — the terracotta it uses for a destructive-but-undoable action. */
    val Terracotta = Color(0xFFC7784A)

    val Blue = Color(0xFF1353D1)
    val BlueSoft = Color(0xFFE7EFFD)

    val Ink = Color(0xFF12213F)
    val Slate = Color(0xFF5F6D88)
    val Mist = Color(0xFF8F9BB3)

    val Red = Color(0xFFE53935)
    val Green = Color(0xFF2E9E5B)
    val Amber = Color(0xFFE8930C)
}

/**
 * Every colour the UI is allowed to use, named by role rather than by hue.
 *
 * Both schemes are defined together, in the same file, on purpose. v2 kept light values
 * in `values/colors.xml` and dark ones in `values-night/`, and the dark set drifted to
 * roughly a third of the size — 143 of 407 colours — so much of the app rendered light
 * colours in dark mode. Declaring a token here forces its dark value to exist.
 */
data class ZillitColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceSunken: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnBrand: Color,

    val brand: Color,
    val brandPressed: Color,
    val brandSoft: Color,

    val accent: Color,
    val accentSoft: Color,
    /** Warm counterpart to [brand], for a "Clear" that undoes rather than deletes. */
    val accentWarm: Color,

    val border: Color,
    val borderStrong: Color,
    val divider: Color,

    val danger: Color,
    val dangerSoft: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,

    val scrim: Color,
    val isLight: Boolean,
)

val LightZillitColors = ZillitColors(
    background = Color(0xFFF5F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFEDEFF5),

    textPrimary = Brand.Ink,
    textSecondary = Brand.Slate,
    textTertiary = Brand.Mist,
    textOnBrand = Color(0xFFFFFFFF),

    brand = Brand.Orange,
    brandPressed = Brand.OrangeDark,
    brandSoft = Brand.OrangeSoft,

    accent = Brand.Blue,
    accentSoft = Brand.BlueSoft,
    accentWarm = Brand.Terracotta,

    border = Color(0xFFE8EBF0),
    borderStrong = Color(0xFFD3D9E3),
    divider = Color(0xFFEEF1F6),

    danger = Brand.Red,
    dangerSoft = Color(0xFFFDECEA),
    success = Brand.Green,
    successSoft = Color(0xFFE7F5ED),
    warning = Brand.Amber,
    warningSoft = Color(0xFFFEF3C7),

    scrim = Color(0x80000000),
    isLight = true,
)

val DarkZillitColors = ZillitColors(
    background = Color(0xFF0E1116),
    surface = Color(0xFF161A21),
    surfaceElevated = Color(0xFF1E232C),
    surfaceSunken = Color(0xFF0A0D11),

    textPrimary = Color(0xFFECEFF4),
    textSecondary = Color(0xFFA7B0C0),
    textTertiary = Color(0xFF6F7A8C),
    textOnBrand = Color(0xFF1A1000),

    // Slightly lifted so the orange keeps contrast against a dark surface rather
    // than vibrating against it.
    brand = Color(0xFFFFA726),
    brandPressed = Color(0xFFE08A18),
    brandSoft = Color(0xFF2E2113),

    accent = Color(0xFF5B8DEF),
    accentSoft = Color(0xFF16213A),
    accentWarm = Color(0xFFE0946A),

    border = Color(0xFF272D38),
    borderStrong = Color(0xFF3A4250),
    divider = Color(0xFF1F242D),

    danger = Color(0xFFFF6B66),
    dangerSoft = Color(0xFF2E1717),
    success = Color(0xFF4CC97F),
    successSoft = Color(0xFF13271C),
    warning = Color(0xFFFFB74D),
    warningSoft = Color(0xFF2C2312),

    scrim = Color(0xB3000000),
    isLight = false,
)
