package com.zillit.zillitapp.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import com.zillit.zillitapp.core.preferences.ChatFontSize
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** The three-state preference v2 stored as ints; modelled here as a real type. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromStorage(value: Int): ThemeMode = entries.getOrElse(value) { SYSTEM }
    }
}

/**
 * Spacing scale. Every gap and padding in the app comes from here, so density changes
 * in one place instead of across 1,338 layout files.
 */
data class ZillitSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
)

data class ZillitShapeTokens(
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val pill: Dp = 999.dp,
)

private val LocalZillitColors = staticCompositionLocalOf { LightZillitColors }
private val LocalZillitSpacing = staticCompositionLocalOf { ZillitSpacing() }
private val LocalZillitShapes = staticCompositionLocalOf { ZillitShapeTokens() }

/**
 * Access point for the design tokens: `ZillitTheme.colors.textSecondary`.
 *
 * Material's own `MaterialTheme.colorScheme` is still populated below so Material 3
 * components pick up the right colours, but app code should prefer these semantic
 * tokens — they say what a colour is *for*, which is what keeps dark mode complete.
 */
object ZillitTheme {
    val colors: ZillitColors
        @Composable @ReadOnlyComposable get() = LocalZillitColors.current

    val spacing: ZillitSpacing
        @Composable @ReadOnlyComposable get() = LocalZillitSpacing.current

    val shapes: ZillitShapeTokens
        @Composable @ReadOnlyComposable get() = LocalZillitShapes.current
}

@Composable
fun ZillitTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * The user's chat text-size preference.
     *
     * Applied to the whole typography scale rather than to chat bubbles alone: someone who
     * needs larger text on set needs it in the composer and the unit strip too, and a
     * message that grows while its timestamp does not looks broken.
     */
    fontSize: ChatFontSize = ChatFontSize.MEDIUM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colors = if (darkTheme) DarkZillitColors else LightZillitColors

    // Dynamic colour is deliberately not used: Zillit is a branded product and the
    // orange is part of the brand, not a wallpaper-derived accent.
    val materialScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.brand,
            onPrimary = colors.textOnBrand,
            secondary = colors.accent,
            onSecondary = colors.textOnBrand,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.brand,
            onPrimary = colors.textOnBrand,
            secondary = colors.accent,
            onSecondary = colors.textOnBrand,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            // Status-bar icons flip with the theme so they stay legible in both.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalZillitColors provides colors,
        LocalZillitSpacing provides ZillitSpacing(),
        LocalZillitShapes provides ZillitShapeTokens(),
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = ZillitTypography.scaledBy(fontSize.scale),
            content = content,
        )
    }
}
