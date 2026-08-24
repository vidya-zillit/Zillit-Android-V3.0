package com.zillit.zillitapp.core.storage

import android.content.Context
import androidx.core.content.FileProvider
import com.zillit.zillitapp.core.logging.ZillitLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device copies of everything downloaded from storage.
 *
 * A chat attachment is opened far more often than it is fetched — a call sheet gets
 * reopened all day — so a file is downloaded **once** and served locally afterwards. Every
 * later open is instant and works with no connection at all, which matters on a set where
 * signal is unreliable.
 *
 * Keyed by the remote object key, flattened to a safe filename. Using the key rather than
 * the display name is what stops two different files called `callsheet.pdf` from
 * overwriting each other.
 *
 * Lives under `filesDir`, not `cacheDir`: the OS clears the cache directory under storage
 * pressure, and losing a downloaded document the moment the phone fills up is exactly when
 * someone needs it. Trimming is done here instead, on our terms — see [trimIfNeeded].
 */
@Singleton
class MediaCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val root: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    /** Where a given remote object lives locally, whether or not it is there yet. */
    fun fileFor(remoteKey: String, fileName: String): File {
        val extension = fileName.substringAfterLast('.', "")
        val safeKey = remoteKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(root, if (extension.isBlank()) safeKey else "$safeKey.$extension")
    }

    /** A complete local copy, or null when it still has to be fetched. */
    fun cached(remoteKey: String, fileName: String): File? =
        fileFor(remoteKey, fileName).takeIf { it.exists() && it.length() > 0 }

    /** Whether a complete local copy exists, without opening it. */
    fun isCached(remoteKey: String, fileName: String): Boolean =
        cached(remoteKey, fileName) != null

    /**
     * A URI another app can open — a PDF viewer, a video player.
     *
     * Goes through the FileProvider because a raw `file://` URI is rejected on every
     * supported API level.
     */
    fun shareableUri(file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Deletes the least recently used files once the cache passes [MAX_BYTES].
     *
     * By last-modified, oldest first: on a long production the newest call sheet is the one
     * being reopened, and the rushes from three weeks ago are not.
     */
    fun trimIfNeeded() {
        runCatching {
            val files = root.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_BYTES) return

            for (file in files) {
                if (total <= MAX_BYTES) break
                total -= file.length()
                file.delete()
            }
            ZillitLog.d(TAG, "Cache trimmed to ${total / 1_048_576}MB")
        }
    }

    /** For sign-out, or leaving a project. */
    fun clear() {
        runCatching { root.listFiles()?.forEach { it.delete() } }
    }

    private companion object {
        const val TAG = "MediaCache"

        /** Generous enough for a production's documents, bounded so it cannot run away. */
        const val MAX_BYTES = 512L * 1_048_576
    }
}
