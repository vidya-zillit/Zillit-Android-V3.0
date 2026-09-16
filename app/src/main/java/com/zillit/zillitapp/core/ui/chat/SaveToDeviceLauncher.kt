package com.zillit.zillitapp.core.ui.chat

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import dagger.hilt.android.EntryPointAccessors

/**
 * One file the view model wants put somewhere the person can find it.
 *
 * The file is already local by the time this is asked for: the view model downloads first,
 * so a Save with no connection fails as a download rather than half-way through a copy.
 */
data class SaveRequest(
    val file: File,
    val fileName: String,
    val mimeType: String,
)

/**
 * Turns a save request into a file in the device's own Pictures, Movies or Downloads.
 *
 * Lives in the screen for the same reason the share chooser does: on API 28 this needs a
 * runtime permission, and asking for one needs an Activity the view model must not hold.
 * Above API 28 there is no permission and the request goes straight through.
 *
 * The toast is deliberate. Saving produces nothing visible in the app, so without a word of
 * confirmation the only feedback is the absence of an error.
 */
@Composable
fun SaveToDeviceLauncher(requests: Flow<SaveRequest>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Reached through an entry point rather than a parameter: the saver is a detail of how
    // this works, and every screen that shows a chat would otherwise have to inject and
    // forward one to get a toast.
    val saver = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ChatAttachmentEntryPoint::class.java,
        ).deviceSaver()
    }

    val savedMessage = stringResource(R.string.chat_saved_to_device)
    val failedMessage = stringResource(R.string.something_went_wrong)

    // Held across the permission round trip: the request arrives, the prompt goes up, and
    // the answer comes back with no reference to what was being saved.
    var pending by remember { mutableStateOf<SaveRequest?>(null) }

    fun perform(request: SaveRequest) {
        scope.launch {
            val ok = saver.save(request.file, request.fileName, request.mimeType)
            Toast.makeText(
                context,
                if (ok) savedMessage else failedMessage,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pending
        pending = null
        when {
            request == null -> Unit
            granted -> perform(request)
            // Refused is an answer, not an error: say nothing beyond the fact it did not
            // happen, and do not ask again on this attempt.
            else -> Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(requests) {
        requests.collect { request ->
            if (saver.needsLegacyPermission()) {
                pending = request
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                perform(request)
            }
        }
    }
}
