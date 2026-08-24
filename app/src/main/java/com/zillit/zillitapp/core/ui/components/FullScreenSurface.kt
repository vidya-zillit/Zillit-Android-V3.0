package com.zillit.zillitapp.core.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Hosts a surface that covers the whole screen — the media viewer, the attachment preview,
 * the image editor.
 *
 * Shared rather than duplicated per caller because the sizing is subtle and was wrong four
 * times before it was right:
 *
 *  - `usePlatformDefaultWidth = false` makes it full-bleed; without it the dialog is inset
 *    to the platform's dialog width.
 *  - The activity runs **edge-to-edge**, so its window is the whole display and
 *    `screenHeightDp` reports the full height. A dialog window does not inherit that: it
 *    fits the system decor and is shorter, so content sized to the activity overflowed and
 *    the bottom bar was drawn off-screen.
 *  - Insets read from *inside* the dialog report the status bar but not the navigation bar,
 *    so padding based on them left content one navigation-bar too tall.
 *
 * The reliable answer is to measure the **activity's** window and size the box explicitly.
 */
@Composable
fun FullScreenSurface(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val view = LocalView.current
        val density = LocalDensity.current

        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.apply {
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                WindowCompat.setDecorFitsSystemWindows(this, false)
            }
        }

        val activityView = LocalContext.current.findActivity()?.window?.decorView
        val metrics = remember(activityView) {
            val insets = activityView
                ?.let { ViewCompat.getRootWindowInsets(it) }
                ?.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout(),
                )
            Triple(
                view.resources.displayMetrics.heightPixels,
                insets?.top ?: 0,
                insets?.bottom ?: 0,
            )
        }

        val (displayHeight, topInset, bottomInset) = metrics

        with(density) {
            // Height only — no top padding. The dialog window already sits below the
            // status bar, and padding it again let the screen underneath show through.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((displayHeight - topInset - bottomInset).toDp()),
            ) {
                content()
            }
        }
    }
}

/** Walks the ContextWrapper chain to the hosting Activity. */
private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
