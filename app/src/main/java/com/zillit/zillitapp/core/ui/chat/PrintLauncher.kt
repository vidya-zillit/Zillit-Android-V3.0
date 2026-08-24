package com.zillit.zillitapp.core.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.print.PrintHelper
import com.zillit.zillitapp.core.logging.ZillitLog
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Sends a cached attachment to Android's print service.
 *
 * Two paths, because the platform has two: images go through `PrintHelper`, which scales
 * and centres them on the page, and a PDF is streamed to the printer as-is via a custom
 * adapter. v2 only handles the image case (`printPhoto`); a call sheet is the thing people
 * actually print, so the PDF path is here too.
 */
@Composable
fun PrintLauncher(requests: Flow<File>) {
    val context = LocalContext.current

    LaunchedEffect(requests) {
        requests.collect { file ->
            when {
                file.extension.equals("pdf", ignoreCase = true) -> context.printPdf(file)
                else -> context.printImage(file)
            }
        }
    }
}

private fun Context.printImage(file: File) {
    val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    if (bitmap == null) {
        ZillitLog.w(TAG, "Nothing printable in ${file.name}")
        return
    }

    PrintHelper(this).apply {
        scaleMode = PrintHelper.SCALE_MODE_FIT
    }.printBitmap(file.name, bitmap)
}

private fun Context.printPdf(file: File) {
    val printManager = getSystemService<PrintManager>() ?: return
    runCatching {
        printManager.print(
            file.name,
            PdfPrintAdapter(file),
            PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .build(),
        )
    }.onFailure { ZillitLog.w(TAG, "Print failed for ${file.name}: ${it.message}") }
}

private const val TAG = "PrintLauncher"
