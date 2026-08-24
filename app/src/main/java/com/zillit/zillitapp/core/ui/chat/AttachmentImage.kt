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
        if (value != null) return@produceState

        // Full file first if it is already cached: no point showing a low-res preview
        // when the real thing is on disk.
        remoteKey?.let { key ->
            cache.cached(key, fileName)?.let { value = it; return@produceState }
        }

        // Otherwise the thumbnail, which is small and fills the bubble fast.
        thumbnailKey?.takeIf { it.isNotBlank() }?.let { key ->
            val thumbName = key.substringAfterLast('/')
            cache.cached(key, thumbName)?.let { value = it }
                ?: downloader.download(key, thumbName, module)

            downloader.states.collect { states ->
                val state = states[key]
                if (state is DownloadState.Ready) {
                    // Only upgrade if the full file has not already landed.
                    if (value == null || value is File && (value as File).name == thumbName) {
                        value = state.file
                    }
                }
            }
        }
    }
}

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
}
