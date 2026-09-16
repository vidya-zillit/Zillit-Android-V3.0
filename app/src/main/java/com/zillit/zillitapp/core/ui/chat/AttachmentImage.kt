package com.zillit.zillitapp.core.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.zillit.zillitapp.core.storage.AttachmentDownloader
import com.zillit.zillitapp.core.storage.DownloadState
import com.zillit.zillitapp.core.storage.MediaCache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import java.io.File

/**
 * What a bubble should render for an attachment.
 *
 * Three stages, in the order they become available:
 *
 * 1. **Local file** — while the message is still uploading, the picture is already on the
 *    device. Showing it immediately is what makes sending feel instant.
 * 2. **Thumbnail** — fetched first because it is small, so a bubble fills in quickly even
 *    on a slow connection rather than staying blank until a 30MB original arrives.
 * 3. **Full file** — once downloaded, it replaces the thumbnail in place.
 *
 * Everything is served from [MediaCache] after the first fetch, so scrolling back through
 * a thread costs no network at all.
 */
@Composable
fun rememberAttachmentImage(
    /** Object key of the full file. */
    remoteKey: String?,
    /** Object key of the preview, when the backend generated one. */
    thumbnailKey: String?,
    fileName: String,
    /** Set while the message is still being uploaded from this device. */
    localPath: String? = null,
    module: String = "home",
): State<Any?> {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ChatAttachmentEntryPoint::class.java,
        )
    }
    val downloader = remember(entryPoint) { entryPoint.attachmentDownloader() }
    val cache = remember(entryPoint) { entryPoint.mediaCache() }

    return produceState<Any?>(
        // The local copy wins immediately when there is one — no request, no flicker.
        initialValue = localPath?.takeIf { File(it).exists() },
        remoteKey,
        thumbnailKey,
        localPath,
    ) {
        // Drop whatever the previous keys resolved to, FIRST.
        //
        // `produceState` restarts this block when a key changes but keeps its last emitted
        // value, and a lazy list reuses a row's composition for a different person as it
        // scrolls. Without this reset the row keeps the picture belonging to whoever used
        // to occupy it — and because the producer below returns early on a non-null value,
        // it never even fetches the right one. That is the "wrong profile photo after
        // scrolling" bug, and it applied to every avatar and image bubble in the app.
        value = localPath?.takeIf { File(it).exists() }

        if (value != null) return@produceState

        // Full file first if it is already cached: no point showing a low-res preview
        // when the real thing is on disk.
        remoteKey?.let { key ->
            cache.cached(key, fileName)?.let { value = it; return@produceState }
        }

        // Otherwise the thumbnail, which is small and fills the bubble fast.
        val thumbKey = thumbnailKey?.takeIf { it.isNotBlank() }
        val thumb = thumbKey?.let { key -> fetch(downloader, cache, key, key.substringAfterLast('/'), module) }
        if (thumb != null) value = thumb

        // No thumbnail — the backend did not make one, or the fetch failed — so the full
        // file is what gets shown. This is v2's rule (`getImageAndShowInViewForOthers`:
        // thumbnail when there is one, else `media`), and without it a picture with no
        // preview stays a blank tile forever. Only when the file is itself a picture: a
        // document or a video with no thumbnail has nothing an image view could draw, and
        // pulling a 200MB file to find that out is the wrong answer.
        val fullKey = remoteKey?.takeIf { it.isNotBlank() }
        if (thumb == null && fullKey != null && fileName.isImageFileName()) {
            fetch(downloader, cache, fullKey, fileName, module)?.let { value = it; return@produceState }
        }

        // Upgrade in place once the full file lands — a tap on the download control, or
        // another bubble sharing the same object — so the preview is never shown over a
        // better copy already on disk.
        if (fullKey != null) {
            downloader.states.collect { states ->
                val state = states[fullKey]
                if (state is DownloadState.Ready) value = state.file
            }
        }
    }
}

/**
 * The file for [key], downloading it if it is not cached; null when the transfer failed.
 *
 * Waits on the downloader's state rather than starting its own transfer, so two bubbles
 * pointing at the same object still share one download. A failure is read from the key
 * being **removed** from the state map after it was in flight — the downloader clears a
 * failed key rather than recording it, so the next composition can retry.
 */
private suspend fun fetch(
    downloader: AttachmentDownloader,
    cache: MediaCache,
    key: String,
    fileName: String,
    module: String,
): File? {
    cache.cached(key, fileName)?.let { return it }
    downloader.download(key, fileName, module)

    var wasInFlight = false
    return downloader.states
        .map { states -> states[key] }
        .transformWhile { state ->
            when (state) {
                is DownloadState.Ready -> { emit(state.file); false }
                is DownloadState.Failed -> { emit(null); false }
                is DownloadState.InProgress -> { wasInFlight = true; true }
                // Absent after being in flight means the download failed and was cleared.
                null -> if (wasInFlight) { emit(null); false } else true
                DownloadState.NotDownloaded -> true
            }
        }
        .firstOrNull()
}

/**
 * Whether a file name is one an image view can decode.
 *
 * Judged from the extension because that is all a remote key offers, and it is the same
 * test v2 makes (`media.getMimeType().contains("image")`) before showing `media` in place
 * of a missing thumbnail.
 */
private fun String.isImageFileName(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

/** Triggers the full-file download and reports progress, for the download button. */
@Composable
fun rememberDownloadState(
    remoteKey: String?,
    fileName: String,
    module: String = "home",
): Pair<DownloadState, () -> Unit> {
    val context = LocalContext.current
    val downloader = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ChatAttachmentEntryPoint::class.java,
        ).attachmentDownloader()
    }

    val states by downloader.states.let { flow ->
        produceState(initialValue = emptyMap<String, DownloadState>()) {
            flow.collect { value = it }
        }
    }

    val key = remoteKey.orEmpty()
    val state = states[key]
        ?: if (key.isNotBlank()) downloader.stateFor(key, fileName) else DownloadState.NotDownloaded

    return state to { if (key.isNotBlank()) downloader.download(key, fileName, module) }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ChatAttachmentEntryPoint {
    fun attachmentDownloader(): AttachmentDownloader
    fun mediaCache(): MediaCache
    fun deviceSaver(): com.zillit.zillitapp.core.storage.DeviceSaver
}
