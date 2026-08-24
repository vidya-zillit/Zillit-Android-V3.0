package com.zillit.zillitapp.core.ui.chat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.storage.MediaCache
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * What to hand to the OS chooser.
 *
 * Assembled by the ViewModel — which knows the messages — and turned into an Intent here,
 * which is the only layer allowed to hold a Context.
 */
data class ShareRequest(
    val files: List<File>,
    val text: String,
)

/**
 * Opens the system chooser whenever [requests] emits.
 *
 * A composable rather than a call the ViewModel makes: starting an Activity needs a
 * Context, and a ViewModel that holds one leaks it across a rotation. Collecting here also
 * means a request that arrives while the screen is away is delivered when it returns
 * instead of being fired into a dead window.
 */
@Composable
fun ShareLauncher(requests: Flow<ShareRequest>, cache: MediaCache) {
    val context = LocalContext.current
    val truncateMessage = stringResource(R.string.text_truncate_msg, TEXT_LIMIT)
    val chooserTitle = stringResource(R.string.app_name)

    LaunchedEffect(requests) {
        requests.collect { request ->
            // Every chooser target has its own limit and some silently drop an oversized
            // extra; v2 trims and says so rather than letting the text vanish.
            val text = if (request.text.length > TEXT_LIMIT) {
                Toast.makeText(context, truncateMessage, Toast.LENGTH_SHORT).show()
                request.text.take(TEXT_LIMIT)
            } else {
                request.text
            }

            context.startChooser(
                uris = request.files.map(cache::shareableUri),
                text = text,
                title = chooserTitle,
            )
        }
    }
}

private fun Context.startChooser(
    uris: List<android.net.Uri>,
    text: String,
    title: String,
) {
    if (uris.isEmpty() && text.isBlank()) return

    val intent = Intent(
        // ACTION_SEND for a lone item: some targets accept only SEND, and offering
        // SEND_MULTIPLE for a single file narrows the list for no reason.
        if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND,
    ).apply {
        when {
            uris.size > 1 -> {
                type = MIXED_TYPE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }

            uris.size == 1 -> {
                type = MIXED_TYPE
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }

            else -> type = TEXT_TYPE
        }
        if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
        // The receiving app gets read access for the life of its task; without this the
        // URI resolves to a SecurityException on the other side.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    runCatching {
        startActivity(
            Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** v2's `Constants.TEXT_LIMIT`. */
private const val TEXT_LIMIT = 2000
private const val MIXED_TYPE = "*/*"
private const val TEXT_TYPE = "text/plain"
