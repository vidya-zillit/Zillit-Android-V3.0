package com.zillit.zillitapp.feature.email.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.zillit.zillitapp.core.logging.ZillitLog
import java.io.ByteArrayOutputStream
import java.io.File

/** An image placed in the body, and the `cid:` the sent mail will reference it by. */
data class InlineImage(
    val contentId: String,
    /** A `data:` URI, so the editor's WebView renders it without a network round trip. */
    val previewUri: String,
    val localPath: String,
    val fileName: String,
    val mimeType: String,
)

/**
 * Prepares a picked image for the message body.
 *
 * Two things happen here, both because a photo straight off a phone camera is unusable in a
 * mail: it is **downscaled** to a width a mail client will display, and **recompressed**.
 * A 12 MP photo is 4 MB and 4000 pixels wide; the same picture at 1200 pixels is under 200 KB
 * and looks identical in a message.
 *
 * The preview is a `data:` URI rather than a `file://` path, because the editor's WebView
 * has no file access and a `file://` image simply renders as a broken box.
 */
fun prepareInlineImage(localPath: String, fileName: String, mimeType: String): InlineImage? {
    val file = File(localPath)
    if (!file.exists()) return null

    return runCatching {
        val bitmap = BitmapFactory.decodeFile(localPath) ?: return null
        val scaled = bitmap.scaledToFit(MAX_WIDTH_PX)

        val isPng = mimeType.contains("png", ignoreCase = true)
        val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(format, QUALITY, out)
            out.toByteArray()
        }

        // Written back over the picked copy so the upload ships the downscaled bytes, not
        // the original — otherwise the recipient still gets the 4 MB photo.
        file.writeBytes(bytes)

        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val type = if (isPng) "image/png" else "image/jpeg"

        InlineImage(
            // Unique per insert rather than derived from the file, so the same picture
            // added twice is two parts and neither replaces the other.
            contentId = "${System.currentTimeMillis()}_${(0..MAX_SUFFIX).random()}",
            previewUri = "data:$type;base64,$encoded",
            localPath = localPath,
            fileName = fileName,
            mimeType = type,
        )
    }.onFailure { ZillitLog.w(TAG, "could not prepare inline image: ${it.message}") }.getOrNull()
}

/** The `<img>` that goes into the editable body, sized so it cannot overflow the message. */
fun InlineImage.asHtml(): String =
    """<br/><img src="$previewUri" data-cid="$contentId" """ +
        """style="max-width:95%;height:auto;" /><br/>"""

/**
 * Swaps every inline preview for the `cid:` the server will resolve.
 *
 * Run at send time, not at insert time: the editor needs the bytes to render the image while
 * it is being written, and the recipient needs a reference to the part instead — a mail
 * carrying half a dozen base64 photos in its body is rejected by most servers.
 */
fun String.withInlineImagesAsCids(images: List<InlineImage>): String =
    images.fold(this) { body, image ->
        body.replace(image.previewUri, "cid:${image.contentId}")
    }

private fun Bitmap.scaledToFit(maxWidth: Int): Bitmap {
    if (width <= maxWidth) return this
    val height = (this.height.toLong() * maxWidth / width).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, maxWidth, height, true)
}

private const val MAX_WIDTH_PX = 1200
private const val QUALITY = 80
private const val MAX_SUFFIX = 100_000
private const val TAG = "InlineImage"
