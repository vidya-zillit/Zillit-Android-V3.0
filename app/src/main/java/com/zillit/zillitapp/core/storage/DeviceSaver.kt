package com.zillit.zillitapp.core.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.zillit.zillitapp.core.logging.ZillitLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a downloaded file somewhere the person can actually find it.
 *
 * "Save" used to mean "download into the app's own directory", which is where every
 * attachment already goes in order to be shown. Nothing appeared in Files or in the
 * gallery, so the option did nothing a user could observe. This is the other half.
 *
 * Two routes, because the platform changed under this:
 *
 * - API 29 and up: `MediaStore`, which needs no permission at all and files the copy under
 *   Pictures, Movies or Downloads according to its type.
 * - API 28: the public directory directly, which does need `WRITE_EXTERNAL_STORAGE`. The
 *   caller is responsible for having it; [save] reports failure rather than throwing so a
 *   refusal is a message, not a crash.
 */
@Singleton
class DeviceSaver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** True when the copy landed. */
    suspend fun save(source: File, fileName: String, mimeType: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!source.exists() || source.length() == 0L) {
                ZillitLog.w(TAG, "nothing to save: $fileName")
                return@withContext false
            }

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(source, fileName, mimeType)
                } else {
                    saveToPublicDirectory(source, fileName, mimeType)
                }
            }.getOrElse { error ->
                ZillitLog.w(TAG, "save failed for $fileName: ${error.message}")
                false
            }
        }

    /**
     * Whether this device needs storage permission before [save] can work.
     *
     * Only ever true on API 28, the one version this app supports that predates scoped
     * storage. Asked rather than assumed so the caller does not prompt for a permission the
     * platform would ignore.
     */
    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * Annotated rather than relying on the caller's version check.
     *
     * `MediaStore.Downloads` is itself an API 29 class, so on API 28 the reference resolves
     * to nothing the moment this method is entered. The guard in [save] does keep it from
     * being entered, but nothing in the method said so — the compiler and the next reader
     * both had to take it on trust, and lint would not.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(source: File, fileName: String, mimeType: String): Boolean {
        val collection = when {
            mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }

        val relative = when {
            mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relative/$APP_FOLDER")
            // Hidden from other apps until the bytes are all there, so a gallery cannot
            // show a half-copied image.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val target = context.contentResolver.insert(collection, values) ?: return false

        context.contentResolver.openOutputStream(target)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: return false

        context.contentResolver.update(
            target,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )

        ZillitLog.d(TAG, "saved $fileName to $relative/$APP_FOLDER")
        return true
    }

    private fun saveToPublicDirectory(source: File, fileName: String, mimeType: String): Boolean {
        val directory = when {
            mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_DOWNLOADS
        }

        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStoragePublicDirectory(directory), APP_FOLDER)
        if (!folder.exists() && !folder.mkdirs()) return false

        // Never overwrite: two files of the same name from different conversations are two
        // different files, and silently replacing one is not what Save means.
        val target = folder.uniqueChild(fileName)
        source.inputStream().use { input -> target.outputStream().use(input::copyTo) }

        ZillitLog.d(TAG, "saved ${target.name} to $directory/$APP_FOLDER")
        return true
    }

    /** `report.pdf`, then `report (1).pdf`, and so on. */
    private fun File.uniqueChild(fileName: String): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"

        var candidate = File(this, fileName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(this, "$base ($index)$suffix")
            index++
        }
        return candidate
    }

    private companion object {
        const val TAG = "DeviceSaver"

        /** One folder, so saved files are findable together rather than scattered. */
        const val APP_FOLDER = "Zillit"
    }
}
