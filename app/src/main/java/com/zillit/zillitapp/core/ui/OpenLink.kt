package com.zillit.zillitapp.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands a URL to whatever the device uses to open it.
 *
 * Nothing in the app navigates itself from a link — a tapped link in a mail body, a tutorial
 * video, a help page all leave. An unopenable one is ignored rather than crashing: there is
 * no useful thing to tell the user about a device with no browser.
 */
fun Context.openLink(url: String) {
    if (url.isBlank()) return

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { startActivity(intent) }
}
