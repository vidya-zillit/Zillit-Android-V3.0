package com.zillit.zillitapp.core.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.zillit.zillitapp.core.logging.ZillitLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns whatever the picker hands back into a file this app owns.
 *
 * A content URI from the gallery or a document provider is a **borrowed** handle: the
 * permission behind it can expire, the provider can go away, and it cannot be read from a
 * WorkManager job that runs an hour later. So every attachment is copied into the app's
 * own cache first, and everything downstream — upload, retry, preview — works on that copy.
 *
 * Thumbnails are generated at pick time rather than on render, because the list scrolls and
 * decoding a 12 MP photo per frame drops it.
 */
@Singleton
class MediaFileResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Everything copied here is disposable — the cache dir, so the OS can reclaim it. */
    private val stagingDir: File
        get() = File(context.cacheDir, "attachments").apply { mkdirs() }

    /**
     * Copies [uri] into staging and describes it.
     *
     * Returns null when the copy fails — an unreadable URI is normal (a revoked permission,
     * a file removed between pick and read) and must not take the picker down with it.
     */
    suspend fun resolve(uri: Uri, caption: String = ""): PickedMedia? =
        withContext(Dispatchers.IO) {
            runCatching {
                val displayName = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
                val mimeType = context.contentResolver.getType(uri) ?: guessMimeType(displayName)

                // Timestamp-prefixed so two picks of the same filename cannot collide.
                val target = File(stagingDir, "${System.currentTimeMillis()}_${displayName.sanitised()}")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: return@runCatching null

                target.toPickedMedia(uri, displayName, mimeType, caption)
            }.onFailure { ZillitLog.w(TAG, "Could not resolve $uri: ${it.message}") }
                .getOrNull()
        }

    /** Resolves a multi-select, dropping the ones that fail. */
    suspend fun resolveAll(uris: List<Uri>): List<PickedMedia> =
        uris.mapNotNull { resolve(it) }

    /**
     * Describes a file this app already owns — a camera capture, or a recording.
     *
     * No copy: the file is already in staging, so copying it would only double the space.
     */
    suspend fun fromFile(file: File, mimeType: String, caption: String = ""): PickedMedia? =
        withContext(Dispatchers.IO) {
            runCatching {
                file.takeIf { it.exists() && it.length() > 0 }
                    ?.toPickedMedia(Uri.fromFile(file), file.name, mimeType, caption)
            }.onFailure { ZillitLog.w(TAG, "Could not resolve ${file.name}: ${it.message}") }
                .getOrNull()
        }

    /**
     * A destination for the camera, plus the URI to hand the camera app.
     *
     * A `FileProvider` URI rather than a `file://` one because passing the latter to
     * another app throws `FileUriExposedException` on every supported API level.
     */
    fun newCaptureTarget(extension: String): Pair<File, Uri> {
        val file = File(stagingDir, "capture_${System.currentTimeMillis()}.$extension")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    /** Drops every staged copy. For sign-out, or reclaiming space. */
    fun clearStaging() {
        runCatching { stagingDir.listFiles()?.forEach { it.delete() } }
    }

    /** Shared tail of [resolve] and [fromFile]: measure, thumbnail, describe. */
    private fun File.toPickedMedia(
        uri: Uri,
        displayName: String,
        mimeType: String,
        caption: String,
    ): PickedMedia {
        val metadata = if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) {
            readMediaMetadata(this)
        } else {
            MediaMetadata()
        }

        return PickedMedia(
            uri = uri,
            localPath = absolutePath,
            fileName = displayName,
            mimeType = mimeType,
            sizeBytes = length(),
            caption = caption,
            thumbnailPath = generateThumbnail(this, mimeType)?.absolutePath,
            durationMs = metadata.durationMs,
            width = metadata.width,
            height = metadata.height,
        )
    }

    /**
     * A small JPEG preview, or null for a type with nothing to show.
     *
     * Images are downsampled rather than decoded whole; video takes the first frame; PDFs
     * render page one. Failure is not propagated — a missing thumbnail costs a placeholder,
     * while a thrown exception would lose the attachment itself.
     */
    private fun generateThumbnail(source: File, mimeType: String): File? = runCatching {
        val bitmap: Bitmap? = when {
            mimeType.startsWith("image/") -> decodeScaled(source)

            mimeType.startsWith("video/") -> withRetriever(source) { it.getFrameAtTime(0L) }

            mimeType == "application/pdf" -> renderPdfFirstPage(source)

            else -> null
        }

        bitmap?.let {
            val target = File(stagingDir, "thumb_${source.nameWithoutExtension}.jpg")
            FileOutputStream(target).use { out ->
                it.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
            }
            target
        }
    }.onFailure { ZillitLog.w(TAG, "Thumbnail failed for ${source.name}: ${it.message}") }
        .getOrNull()

    private fun renderPdfFirstPage(source: File): Bitmap? = runCatching {
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return@use null
                renderer.openPage(0).use { page ->
                    Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        .also { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
            }
        }
    }.onFailure { ZillitLog.w(TAG, "PDF thumbnail failed for ${source.name}: ${it.message}") }
        .getOrNull()

    /**
     * Decodes at roughly thumbnail size.
     *
     * Two passes: bounds first, then a power-of-two `inSampleSize`. Decoding full size and
     * scaling afterwards allocates the whole bitmap, which is what OOMs on a large photo.
     */
    private fun decodeScaled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        var sample = 1
        while (bounds.outWidth / sample > THUMBNAIL_MAX_PX || bounds.outHeight / sample > THUMBNAIL_MAX_PX) {
            sample *= 2
        }

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()

    private fun readMediaMetadata(file: File): MediaMetadata = runCatching {
        withRetriever(file) { retriever ->
            MediaMetadata(
                durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0,
                width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0,
                height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0,
            )
        }
    }.getOrNull() ?: MediaMetadata()

    /**
     * Opens a retriever on [file], hands it over, and always releases it.
     *
     * Written out rather than using `use`, which needs `AutoCloseable` — an interface
     * `MediaMetadataRetriever` only implements from API 29. Below that the call fails at
     * runtime, and because both callers wrap themselves in `runCatching` the failure was
     * invisible: on Android 9 every video arrived with no thumbnail, no duration and no
     * dimensions, and nothing said why. `release()` exists on every version this app
     * supports.
     */
    private fun <T> withRetriever(file: File, block: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            block(retriever)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    /** Strips anything that would break an S3 object key or a filesystem path. */
    private fun String.sanitised(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")

    private data class MediaMetadata(
        val durationMs: Long = 0,
        val width: Int = 0,
        val height: Int = 0,
    )

    private companion object {
        const val TAG = "MediaResolver"

        /** Large enough for a full-width bubble on a tablet, small enough to be cheap. */
        const val THUMBNAIL_MAX_PX = 640

        const val THUMBNAIL_QUALITY = 80
    }
}
