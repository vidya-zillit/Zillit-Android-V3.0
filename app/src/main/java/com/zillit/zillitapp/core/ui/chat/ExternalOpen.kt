package com.zillit.zillitapp.core.ui.chat

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.storage.MediaCache
import java.io.File

/**
 * Hands a cached file to whatever app on the device can open it.
 *
 * A chat client has no business rendering XLSX or DOCX, and v2 does the same — the file is
 * downloaded, then thrown at an `ACTION_VIEW` chooser. The FileProvider URI and the
 * read-permission flag are both required: a raw `file://` is rejected on every supported
 * API level, and without the grant the receiving app sees a `SecurityException`.
 */
internal fun Context.openFileExternally(file: File, cache: MediaCache) {
    val uri = runCatching { cache.shareableUri(file) }.getOrNull() ?: return
    val mime = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase())
        ?: "*/*"

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    runCatching { startActivity(intent) }.onFailure {
        // Nothing installed that handles it — saying so beats a silent no-op.
        Toast.makeText(this, getString(R.string.no_app_to_open_file), Toast.LENGTH_SHORT).show()
    }
}
