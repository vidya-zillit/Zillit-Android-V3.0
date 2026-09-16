package com.zillit.zillitapp.core.attachment

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.attachment.editor.ImageEditorScreen
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.components.FullScreenSurface
import com.zillit.zillitapp.core.ui.location.LocationPickerScreen
import com.zillit.zillitapp.core.ui.location.PickedLocation
import java.io.File
import kotlinx.coroutines.launch

/**
 * The attachment sheet — one implementation for every screen that attaches a file.
 *
 * A caller passes the options it allows and gets an [AttachmentResult] back:
 *
 * ```kotlin
 * if (showPicker) {
 *     AttachmentPickerSheet(
 *         options = AttachmentOption.CHAT_DEFAULT,   // or .CALL_SHEET for documents only
 *         onResult = { result -> … },
 *         onDismiss = { showPicker = false },
 *     )
 * }
 * ```
 *
 * Permissions are handled **here**, not by the caller. Each option declares what it needs
 * (see [AttachmentOption.permissions]), the sheet requests it on tap, and a refusal shows
 * a rationale with a route to Settings rather than silently doing nothing — which is what
 * a picker that opens onto an empty grid looks like to a user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerSheet(
    options: Set<AttachmentOption>,
    onResult: (AttachmentResult) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Cap on a single selection. v2 uses 100. */
    maxSelectable: Int = DEFAULT_MAX_SELECTABLE,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Which option is mid-flight, so a permission result knows what to resume.
    var pendingOption by remember { mutableStateOf<AttachmentOption?>(null) }
    var deniedOption by remember { mutableStateOf<AttachmentOption?>(null) }
    var galleryFilter by remember { mutableStateOf<GalleryFilter?>(null) }

    // Everything that produces files lands here first. The preview is where captions are
    // written and images are edited, so it must sit between picking and returning —
    // otherwise each caller would have to host it, and they would drift apart.
    var previewing by remember { mutableStateOf<List<PickedMedia>?>(null) }

    // Set while the gallery is reopened from the preview. The existing selection is held
    // here and the new picks are appended to it, so nothing already captioned is lost.
    var addingMoreTo by remember { mutableStateOf<List<PickedMedia>?>(null) }
    var editing by remember { mutableStateOf<PickedMedia?>(null) }
    var editedResult by remember { mutableStateOf<PickedMedia?>(null) }

    // The camera writes straight into a file we own, so the capture target has to be
    // created before the intent launches and remembered across the result.
    var captureTarget by remember { mutableStateOf<Pair<File, String>?>(null) }

    /** True while the map screen is up. */
    var pickingLocation by remember { mutableStateOf(false) }

    val resolver = rememberMediaFileResolver()

    /**
     * The system contact picker.
     *
     * Android's own, rather than a list of our own: it already handles search, accounts and
     * the per-pick grant, so reading the whole address book to draw a list would ask for far
     * more access than sharing one card needs.
     *
     * Picks a **phone number**, not a contact. `PickContact()` returns a row of the Contacts
     * table, which has no number column — the query in [readContact] threw on it, the
     * failure was swallowed, and every pick came back as cancelled. A row of the Phone table
     * carries the name and the number together, and the per-row grant still covers it.
     */
    val contactLauncher = rememberLauncherForActivityResult(
        PickPhoneNumber,
    ) { uri ->
        if (uri == null) {
            onResult(AttachmentResult.Cancelled)
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        val contact = context.readContact(uri)
        if (contact == null) {
            onResult(AttachmentResult.Cancelled)
        } else {
            onResult(contact)
        }
        onDismiss()
    }

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) {
            onDismiss()
        } else {
            resolver.resolveAsync(uris.take(maxSelectable)) { media ->
                if (media.isEmpty()) {
                    onResult(AttachmentResult.Cancelled)
                    onDismiss()
                } else {
                    previewing = media
                }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val target = captureTarget
        if (!saved || target == null) {
            onDismiss()
        } else {
            resolver.fromFileAsync(target.first, target.second) { media ->
                if (media == null) {
                    onResult(AttachmentResult.Cancelled)
                    onDismiss()
                } else {
                    previewing = listOf(media)
                }
            }
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { saved ->
        val target = captureTarget
        if (!saved || target == null) {
            onDismiss()
        } else {
            resolver.fromFileAsync(target.first, target.second) { media ->
                if (media == null) {
                    onResult(AttachmentResult.Cancelled)
                    onDismiss()
                } else {
                    previewing = listOf(media)
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val option = pendingOption ?: return@rememberLauncherForActivityResult
        pendingOption = null

        // Location asks for fine and coarse together and either is enough, so this is
        // "any granted", not "all granted".
        if (grants.values.any { it }) {
            launchOption(
                option = option,
                context = context,
                resolver = resolver,
                onOpenGallery = { galleryFilter = it },
                onLaunchDocument = { documentLauncher.launch(DOCUMENT_MIME_TYPES) },
                onOpenLocation = { pickingLocation = true },
                onLaunchContact = { contactLauncher.launch(null) },
                onLaunchCamera = { file, mime, uri ->
                    captureTarget = file to mime
                    cameraLauncher.launch(uri)
                },
                onLaunchVideo = { file, mime, uri ->
                    captureTarget = file to mime
                    videoLauncher.launch(uri)
                },
            )
        } else {
            deniedOption = option
        }
    }

    fun handle(option: AttachmentOption) {
        val missing = option.permissions.filterNot { context.hasPermission(it) }
        if (missing.isEmpty()) {
            launchOption(
                option = option,
                context = context,
                resolver = resolver,
                onOpenGallery = { galleryFilter = it },
                onLaunchDocument = { documentLauncher.launch(DOCUMENT_MIME_TYPES) },
                onLaunchCamera = { file, mime, uri ->
                    captureTarget = file to mime
                    cameraLauncher.launch(uri)
                },
                onLaunchVideo = { file, mime, uri ->
                    captureTarget = file to mime
                    videoLauncher.launch(uri)
                },
                onOpenLocation = { pickingLocation = true },
                onLaunchContact = { contactLauncher.launch(null) },
            )
        } else {
            pendingOption = option
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // The map replaces the sheet, like the gallery does, rather than stacking on it.
    if (pickingLocation) {
        FullScreenSurface(onDismiss = { pickingLocation = false }) {
            LocationPickerScreen(
                initial = PickedLocation(),
                onBack = {
                    pickingLocation = false
                    onResult(AttachmentResult.Cancelled)
                    onDismiss()
                },
                onConfirm = { picked ->
                    pickingLocation = false
                    // A point is the whole message. An address with no coordinates is a
                    // search someone typed and never resolved, and a pin nobody can open
                    // is worse than no pin.
                    if (picked.hasPoint) {
                        onResult(
                            AttachmentResult.Location(
                                latitude = picked.latitude ?: 0.0,
                                longitude = picked.longitude ?: 0.0,
                                address = picked.address,
                                snapshotPath = picked.snapshotPath,
                                snapshotWidth = picked.snapshotWidth,
                                snapshotHeight = picked.snapshotHeight,
                            ),
                        )
                    } else {
                        onResult(AttachmentResult.Cancelled)
                    }
                    onDismiss()
                },
            )
        }
    }

    // The in-app gallery replaces the sheet rather than stacking on it — two sheets deep
    // makes the back gesture ambiguous.
    galleryFilter?.let { filter ->
        MediaGallerySheet(
            filter = filter,
            maxSelectable = maxSelectable,
            onConfirm = { media ->
                galleryFilter = null
                // Append for "add more", replace for a fresh pick. Deduplicated on path
                // so picking the same file twice does not send it twice.
                previewing = addingMoreTo
                    ?.let { existing ->
                        existing + media.filterNot { new ->
                            existing.any { it.localPath == new.localPath }
                        }
                    }
                    ?: media
                addingMoreTo = null
            },
            onDismiss = {
                galleryFilter = null
                // Cancelling an "add more" returns to the preview rather than out of the
                // picker; the first selection must not be discarded.
                addingMoreTo = null
                if (previewing == null) onDismiss()
            },
        )
        return
    }

    // Preview and editor are full-window surfaces, not sheet content. Composed inline
    // they render inside the host screen's bounds — the chat header, unit strip and
    // bottom bar stay visible around them — so both go in a Dialog with the platform
    // width constraint off, which is what actually covers the window.
    // The editor is composed OVER the preview, never instead of it.
    //
    // Returning early to show the editor unmounted the preview, and its `remember`ed
    // working copy went with it — so on coming back the list was rebuilt from the original
    // parameter, discarding both the edit and any caption already typed. Keeping the
    // preview mounted underneath preserves all of it.
    previewing?.let { media ->
        FullScreenSurface(
            onDismiss = {
                previewing = null
                onResult(AttachmentResult.Cancelled)
                onDismiss()
            },
        ) {
            MediaPreviewScreen(
                media = media,
                onSend = { edited ->
                    previewing = null
                    onResult(AttachmentResult.Media(edited))
                    onDismiss()
                },
                onEdit = { editing = it },
                edited = editedResult,
                onAddMore = {
                    // `previewing` is deliberately NOT cleared: clearing it unmounted the
                    // preview before the gallery mounted, and the chat screen showed
                    // through for a beat. The gallery simply takes priority below.
                    addingMoreTo = media
                    galleryFilter = GalleryFilter.IMAGES_AND_VIDEOS
                },
                onClose = {
                    previewing = null
                    onResult(AttachmentResult.Cancelled)
                    onDismiss()
                },
            )
        }

        // Composed OVER the preview, never instead of it.
        //
        // Returning early to show the editor unmounted the preview, and its remembered
        // working copy went with it — so on coming back the list was rebuilt from the
        // original parameter, discarding both the edit and any caption already typed.
        // Keeping the preview mounted underneath preserves all of it.
        editing?.let { item ->
            val rasterizer = rememberEditorRasterizer()
            val editorScope = rememberCoroutineScope()

            FullScreenSurface(onDismiss = { editing = null }) {
                ImageEditorScreen(
                    media = item,
                    onDone = { editorState ->
                        editorScope.launch {
                            editedResult = rasterizer.render(item, editorState) ?: item
                            editing = null
                        }
                    },
                    onCancel = { editing = null },
                )
            }
        }
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ZillitTheme.colors.surface,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.attachment_choose_below),
            style = MaterialTheme.typography.titleLarge,
            color = ZillitTheme.colors.textPrimary,
            modifier = Modifier.padding(
                start = ZillitTheme.spacing.lg,
                end = ZillitTheme.spacing.lg,
                bottom = ZillitTheme.spacing.md,
            ),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            contentPadding = PaddingValues(
                horizontal = ZillitTheme.spacing.md,
                vertical = ZillitTheme.spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        ) {
            // Iterated over the enum, not the passed set, so the order on screen is
            // always the same regardless of which subset a caller allows.
            items(AttachmentOption.entries.filter { it in options }) { option ->
                AttachmentOptionCell(option = option, onClick = { handle(option) })
            }
        }
    }

    deniedOption?.let { option ->
        PermissionDeniedDialog(
            option = option,
            onDismiss = { deniedOption = null },
            onOpenSettings = {
                deniedOption = null
                context.openAppSettings()
            },
        )
    }
}

@Composable
private fun AttachmentOptionCell(option: AttachmentOption, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = ZillitTheme.spacing.xs),
    ) {
        Box(
            modifier = Modifier.size(56.dp).background(ZillitTheme.colors.brandSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = ZillitTheme.colors.brand,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = stringResource(option.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = ZillitTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Shown when a permission is refused.
 *
 * Names what was refused and offers Settings, because a second in-app request is ignored
 * by the OS once "don't ask again" is in effect — leaving a button that appears broken.
 */
@Composable
private fun PermissionDeniedDialog(
    option: AttachmentOption,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ZillitConfirmDialog(
        title = stringResource(R.string.attachment_permission_title),
        message = stringResource(option.permissionRationaleRes),
        confirmLabel = stringResource(R.string.attachment_open_settings),
        dismissLabel = stringResource(R.string.cancel),
        onConfirm = onOpenSettings,
        onDismiss = onDismiss,
    )
}

private fun launchOption(
    option: AttachmentOption,
    context: Context,
    resolver: MediaFileResolverHandle,
    onOpenGallery: (GalleryFilter) -> Unit,
    onLaunchDocument: () -> Unit,
    onLaunchCamera: (File, String, Uri) -> Unit,
    onLaunchVideo: (File, String, Uri) -> Unit,
    onOpenLocation: () -> Unit,
    onLaunchContact: () -> Unit,
) {
    when (option) {
        AttachmentOption.CAMERA -> {
            val (file, uri) = resolver.newCaptureTarget("jpg")
            onLaunchCamera(file, "image/jpeg", uri)
        }

        AttachmentOption.VIDEO -> {
            val (file, uri) = resolver.newCaptureTarget("mp4")
            onLaunchVideo(file, "video/mp4", uri)
        }

        AttachmentOption.GALLERY -> onOpenGallery(GalleryFilter.IMAGES)
        AttachmentOption.VIDEO_GALLERY -> onOpenGallery(GalleryFilter.VIDEOS)
        AttachmentOption.AUDIO -> onOpenGallery(GalleryFilter.AUDIO)
        AttachmentOption.DOCUMENT -> onLaunchDocument()

        // A pin is picked on the app's own map screen; a contact comes from the system
        // picker, which needs no screen of ours. Both used to fall through to `Unit` here,
        // so the tiles were tappable and did nothing.
        AttachmentOption.LOCATION -> onOpenLocation()
        AttachmentOption.CONTACT -> onLaunchContact()
    }
}

/**
 * Hosts a full-window surface.
 *
 * `usePlatformDefaultWidth = false` is what makes it full-bleed — without it the dialog is
 * inset to the platform's dialog width and the screen underneath shows through.
 *
 * `decorFitsSystemWindows` is deliberately left at its default (true). With it off, the
 * dialog window spans the whole display while the content still measured against the full
 * height *and* reserved the status bar again via its own insets — so the content ended up
 * one status-bar taller than the window and the bottom bar was clipped off the screen.
 * Fitting the decor means the window is exactly the usable area, and the children need no
 * inset padding of their own.
 */
private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
    )
}

/**
 * Everything except images and video, plus a wildcard.
 *
 * v2 lets the document picker return anything; narrowing it here would silently hide file
 * types productions actually send (CAD exports, archives, subtitle files).
 */
private val DOCUMENT_MIME_TYPES = arrayOf("*/*")

private const val GRID_COLUMNS = 4

/** v2's cap. */
private const val DEFAULT_MAX_SELECTABLE = 100

/**
 * `ACTION_PICK` over the Phone table.
 *
 * The stock [ActivityResultContracts.PickContact] picks from the Contacts table, whose rows
 * have no number; this asks the same system picker for a phone-number row instead, so one
 * query answers with both the name and the number and no `READ_CONTACTS` is needed.
 */
private object PickPhoneNumber : androidx.activity.result.contract.ActivityResultContract<Unit?, Uri?>() {
    override fun createIntent(context: Context, input: Unit?): Intent =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.takeIf { resultCode == Activity.RESULT_OK }?.data
}

/**
 * Read the name and number off a picked Phone row.
 *
 * v2 offers a Contact tile too, and the code behind it is commented out from end to end —
 * so this is new rather than ported. Deliberately minimal: a name and a number are what a
 * production crew shares, and pulling every email, address and note would turn a one-line
 * message into a form.
 *
 * Returns null when the row has no usable number, which is the one case where sharing it
 * would send an empty card.
 */
private fun Context.readContact(uri: Uri): AttachmentResult.Contact? = runCatching {
    // The picker grants access to this row only, so no READ_CONTACTS check is needed for
    // the URI it handed back.
    contentResolver.query(
        uri,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null

        val name = cursor.getString(0).orEmpty().trim()
        val phone = cursor.getString(1).orEmpty().trim()
        if (phone.isBlank()) return@use null

        AttachmentResult.Contact(name = name, phone = phone)
    }
}.getOrNull()
