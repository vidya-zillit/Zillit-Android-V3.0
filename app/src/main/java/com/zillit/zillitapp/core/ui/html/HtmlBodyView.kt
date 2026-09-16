package com.zillit.zillitapp.core.ui.html

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import kotlinx.coroutines.delay

/**
 * An HTML email body, rendered.
 *
 * The app already has [com.zillit.zillitapp.core.ui.text.fromSimpleHtml], which handles
 * `<b>`, `<strong>` and `<br>` and throws the rest away. That is right for a chat message
 * with a little markup in it and hopeless for a mail body, which is a full document —
 * tables, inline styles, images, a signature block someone built in Outlook. So this one
 * is a real renderer.
 *
 * ### Why JavaScript stays off
 * The body is **attacker-controlled**: anyone who knows the address can put a script in it.
 * With JS disabled a malicious body is inert markup. The cost is that the page cannot
 * measure itself and report back, which is why the height is polled from
 * [WebView.contentHeight] rather than read from a JS bridge — see [rememberContentHeight].
 *
 * ### Links
 * Nothing navigates inside this view. Every tap is handed to [onLinkClick], and a link the
 * caller does not handle is ignored rather than loaded — a mail body must never be able to
 * replace itself with a page of the sender's choosing.
 */
@Composable
fun HtmlBodyView(
    html: String,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit = {},
    /**
     * Rewrites `cid:` references to something loadable — the S3 URL for that part.
     *
     * Inline images arrive as separate attachment parts referenced by content id, so
     * without this every embedded image in the mail renders as a broken box.
     */
    resolveContentId: (String) -> String? = { null },
    /**
     * v2 loads remote images unconditionally, which is also how it loads every tracking
     * pixel in every marketing mail. Kept as the default for parity, exposed so a
     * "block remote images" setting has something to switch.
     */
    allowRemoteImages: Boolean = true,
) {
    val colors = ZillitTheme.colors
    val density = LocalDensity.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    val heightPx = rememberContentHeight(webView, html)

    val document = remember(html, colors.isLight) {
        wrapInDocument(
            // Bare URLs the sender never wrapped in an anchor are made clickable here,
            // at render time only — nothing stored or sent changes.
            body = linkifyHtml(rewriteContentIds(html, resolveContentId)),
            textColor = colors.textPrimary.toArgb(),
            linkColor = colors.brand.toArgb(),
        )
    }

    AndroidView(
        factory = { context ->
            @SuppressLint("SetJavaScriptEnabled")
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.loadsImagesAutomatically = true
                settings.blockNetworkImage = !allowRemoteImages
                // The body decides its own width; without this a wide table forces a
                // horizontal scroll on the whole message instead of scaling to fit.
                settings.useWideViewPort = false
                settings.loadWithOverviewMode = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                // The WebView paints its own white behind the page, which is a white
                // rectangle in the middle of a dark-theme thread.
                setBackgroundColor(AndroidColor.TRANSPARENT)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        request?.url?.toString()?.let(onLinkClick)
                        // Always true: nothing in a mail body may navigate this view.
                        return true
                    }
                }

                webView = this
            }
        },
        update = { view ->
            view.settings.blockNetworkImage = !allowRemoteImages
            if (view.getTag(TAG_DOCUMENT) != document) {
                view.setTag(TAG_DOCUMENT, document)
                view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (heightPx > 0) {
                    Modifier.height(with(density) { heightPx.toDp() })
                } else {
                    // Something to occupy while the page lays out, so the row does not
                    // collapse to nothing and then jump.
                    Modifier.height(48.dp)
                },
            ),
    )
}

/**
 * The rendered height of the page, in pixels.
 *
 * A WebView inside a `LazyColumn` cannot be `WRAP_CONTENT` — the list measures its children
 * before the page has laid out, gets zero, and the message disappears. With JS off the page
 * cannot post its own height either, so [WebView.contentHeight] is polled until it stops
 * changing: images arriving late make it grow a few times, and a single read right after
 * `onPageFinished` catches it mid-flight.
 */
@Composable
private fun rememberContentHeight(webView: WebView?, html: String): Int {
    var height by remember(html) { mutableIntStateOf(0) }

    LaunchedEffect(webView, html) {
        val view = webView ?: return@LaunchedEffect
        var stableFor = 0
        var last = 0

        // ~6s of watching, then whatever we have. Bounded deliberately: an animated GIF
        // never settles, and the alternative is polling for the life of the screen.
        repeat(MAX_POLLS) {
            delay(POLL_INTERVAL_MS)
            val measured = (view.contentHeight * view.scale).toInt()

            if (measured > 0 && measured == last) {
                stableFor++
                if (stableFor >= STABLE_POLLS) {
                    height = measured
                    return@LaunchedEffect
                }
            } else {
                stableFor = 0
            }

            if (measured > 0) {
                last = measured
                height = measured
            }
        }
    }

    return height
}

/**
 * Points `cid:` sources at something that will actually load.
 *
 * v2 does this with a string replace and a **hard-coded bucket name** as the fallback, so
 * inline images break the day the bucket is renamed. Here an unresolvable id is left as it
 * is: a broken image is honest, a wrong URL is a mystery.
 */
private fun rewriteContentIds(html: String, resolve: (String) -> String?): String =
    CID_PATTERN.replace(html) { match ->
        val contentId = match.groupValues[1].trim().trim('<', '>')
        resolve(contentId)?.let { "\"$it\"" } ?: match.value
    }

/**
 * Wraps the body in a document that respects the app's theme and the phone's width.
 *
 * Mail bodies almost never carry a viewport, so without this a desktop-composed mail
 * renders at 980 CSS pixels and the user reads a third of each line.
 */
private fun wrapInDocument(body: String, textColor: Int, linkColor: Int): String = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  html, body {
    margin: 0; padding: 0;
    background: transparent;
    color: ${textColor.toCssColor()};
    font-family: -apple-system, Roboto, sans-serif;
    font-size: 15px; line-height: 1.5;
    word-wrap: break-word; overflow-wrap: anywhere;
  }
  a { color: ${linkColor.toCssColor()}; }
  img { max-width: 100% !important; height: auto !important; }
  /* Wide tables scroll inside themselves rather than widening the message. */
  table { max-width: 100% !important; }
  pre { white-space: pre-wrap; word-wrap: break-word; }
  blockquote {
    margin: 0 0 0 8px; padding-left: 10px;
    border-left: 2px solid ${textColor.toCssColor()}40;
  }
</style>
</head>
<body>$body</body>
</html>
""".trimIndent()

private fun Int.toCssColor(): String = "#%06X".format(0xFFFFFF and this)

private val CID_PATTERN = Regex("""["']cid:([^"']+)["']""", RegexOption.IGNORE_CASE)

private const val TAG_DOCUMENT = 0x7f0a0001
private const val POLL_INTERVAL_MS = 120L
private const val MAX_POLLS = 50
private const val STABLE_POLLS = 2
