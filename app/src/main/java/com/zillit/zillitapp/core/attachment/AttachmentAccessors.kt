package com.zillit.zillitapp.core.attachment

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import com.zillit.zillitapp.core.attachment.editor.EditorRasterizer
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hilt access for the picker's collaborators.
 *
 * The sheet is a plain composable rather than a screen with a ViewModel — it is opened
 * from inside other screens (chat composer, profile, Drive) that already have their own —
 * so it reaches the singletons through an entry point instead of forcing every caller to
 * inject and pass them down.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AttachmentEntryPoint {
    fun mediaStoreSource(): MediaStoreSource
    fun mediaFileResolver(): MediaFileResolver
    fun editorRasterizer(): EditorRasterizer
}

@Composable
internal fun rememberEditorRasterizer(): EditorRasterizer {
    val context = LocalContext.current
    return remember(context) { context.attachmentEntryPoint().editorRasterizer() }
}

@Composable
internal fun rememberMediaStoreSource(): MediaStoreSource {
    val context = LocalContext.current
    return remember(context) { context.attachmentEntryPoint().mediaStoreSource() }
}

/**
 * The resolver plus a scope, so the sheet can start a copy from a non-suspending callback
 * (an activity result) without each call site remembering its own scope.
 */
@Composable
internal fun rememberMediaFileResolver(): MediaFileResolverHandle {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolver = remember(context) { context.attachmentEntryPoint().mediaFileResolver() }
    return remember(resolver, scope) { MediaFileResolverHandle(resolver, scope) }
}

internal class MediaFileResolverHandle(
    private val resolver: MediaFileResolver,
    private val scope: CoroutineScope,
) {
    suspend fun resolve(uri: Uri, caption: String = ""): PickedMedia? = resolver.resolve(uri, caption)

    fun resolveAsync(uris: List<Uri>, onDone: (List<PickedMedia>) -> Unit) {
        scope.launch { onDone(resolver.resolveAll(uris)) }
    }

    fun fromFileAsync(file: File, mimeType: String, onDone: (PickedMedia?) -> Unit) {
        scope.launch { onDone(resolver.fromFile(file, mimeType)) }
    }

    fun newCaptureTarget(extension: String): Pair<File, Uri> = resolver.newCaptureTarget(extension)
}

private fun Context.attachmentEntryPoint(): AttachmentEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, AttachmentEntryPoint::class.java)
