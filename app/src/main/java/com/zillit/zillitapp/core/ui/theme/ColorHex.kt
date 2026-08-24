package com.zillit.zillitapp.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A `#rrggbb` or `#aarrggbb` string as a [Color], or [fallback] if it is anything else.
 *
 * Colours arrive from the server as free text — an event's colour, a project's accent — and
 * a value the app cannot parse must cost that one swatch, never the screen it is on. The
 * shorthand `#rgb` form is accepted too, since the web client emits it.
 */
fun String?.toColorOrDefault(fallback: Color): Color {
    val hex = this?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() } ?: return fallback

    val normalised = when (hex.length) {
        // #rgb -> #rrggbb
        3 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return fallback
    }

    val value = normalised.toULongOrNull(radix = 16) ?: return fallback
    // A 6-digit value carries no alpha, so it has to be added or the colour is transparent.
    return if (normalised.length == 6) {
        Color(value.toLong() or 0xFF000000L)
    } else {
        Color(value.toLong())
    }
}
