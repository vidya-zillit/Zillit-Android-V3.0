package com.zillit.zillitapp.core.ui.html

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import com.zillit.zillitapp.core.logging.ZillitLog
import kotlinx.coroutines.flow.Flow

/** One document to print: a job name, and the HTML to lay out. */
data class PrintableHtml(val jobName: String, val html: String)

/**
 * Prints HTML through the system print service.
 *
 * The existing [com.zillit.zillitapp.core.ui.chat.PrintLauncher] takes a `File` and handles
 * PDFs and images — the right thing for a chat attachment, and no use for a mail body, which
 * only exists as markup. `WebView` is the only HTML layout engine on the platform, so
 * printing one means loading it into a WebView and taking that view's print adapter.
 *
 * Two things this has to get right, both easy to get wrong:
 *
 *  - **The adapter is taken after the page finishes.** Asking for it mid-load prints a blank
 *    sheet, because there is nothing laid out yet.
 *  - **The WebView is held alive until the job is handed over.** The print framework pulls
 *    pages from the adapter lazily, and a WebView collected in the meantime prints nothing.
 *    Keeping the reference in the closure is what prevents that.
 *
 * JavaScript stays off: a mail body is attacker-controlled, and that does not change because
 * it is being printed.
 */
@Composable
fun HtmlPrintLauncher(requests: Flow<PrintableHtml>) {
    val context = LocalContext.current

    LaunchedEffect(requests) {
        requests.collect { request -> context.printHtml(request) }
    }
}

private fun Context.printHtml(request: PrintableHtml) {
    val printManager = getSystemService<PrintManager>() ?: return

    // Not attached to the window: a WebView can lay out and print off-screen, and adding
    // one to the hierarchy just to print it would flash the page over the screen.
    val webView = WebView(this)

    webView.settings.javaScriptEnabled = false
    webView.settings.loadsImagesAutomatically = true

    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            val page = view ?: return

            runCatching {
                printManager.print(
                    request.jobName,
                    page.createPrintDocumentAdapter(request.jobName),
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .build(),
                )
            }.onFailure {
                ZillitLog.w(TAG, "Print failed for ${request.jobName}: ${it.message}")
            }
        }
    }

    webView.loadDataWithBaseURL(null, request.html, "text/html", "utf-8", null)
}

private const val TAG = "HtmlPrinter"
