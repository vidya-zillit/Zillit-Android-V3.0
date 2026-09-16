package com.zillit.zillitapp.core.attachment

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VideoCameraBack
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.zillitapp.R

/**
 * What the attachment sheet can offer, and what each option needs to work.
 *
 * The label and icon travel with the option so a caller only ever passes the *set* it
 * allows — Call Sheet passes `setOf(DOCUMENT)`, an ordinary unit passes the full set —
 * exactly as v2's `openPicker(pickerList = …)` does, but without the caller also having
 * to know which permissions each choice implies.
 *
 * Permissions are attached here rather than requested at the call site because getting
 * them wrong is silent: on Android 13+ `READ_EXTERNAL_STORAGE` is simply never granted,
 * so a picker that asks for it appears to work and then returns nothing.
 */
enum class AttachmentOption(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    /** Take a photo now. */
    CAMERA(R.string.attachment_photo, Icons.Outlined.PhotoCamera),

    /** Pick images from the in-app gallery. */
    GALLERY(R.string.attachment_gallery, Icons.Outlined.PhotoLibrary),

    /** Record a video now. */
    VIDEO(R.string.attachment_video, Icons.Outlined.VideoCameraBack),

    /** Pick videos from the in-app gallery. */
    VIDEO_GALLERY(R.string.attachment_video_gallery, Icons.Outlined.VideoLibrary),

    DOCUMENT(R.string.attachment_document, Icons.Outlined.Description),

    AUDIO(R.string.attachment_audio, Icons.Outlined.AudioFile),

    LOCATION(R.string.attachment_location, Icons.Outlined.LocationOn),

    CONTACT(R.string.attachment_contact, Icons.Outlined.Contacts),
    ;

    /**
     * The runtime permissions this option needs on the current OS version.
     *
     * Empty means none — the document picker and the camera-capture contract both go
     * through the system picker/`FileProvider`, which grants access per file, so asking
     * for storage there is both unnecessary and a prompt the user will resent.
     */
    val permissions: Array<String>
        get() = when (this) {
            // ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE only need CAMERA, and only when
            // the app declares it in the manifest — which it does, for the QR scanner.
            CAMERA, VIDEO -> arrayOf(Manifest.permission.CAMERA)

            // The in-app gallery reads MediaStore directly, so it needs real read access.
            // Android 13 split this into per-type permissions and stopped granting
            // READ_EXTERNAL_STORAGE at all.
            GALLERY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            VIDEO_GALLERY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            AUDIO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            // None. The system contact picker returns a URI this app is granted access
            // to for that one row, so READ_CONTACTS is never needed — and asking for it
            // meant a denial silently blocked a flow that would have worked.
            CONTACT -> emptyArray()

            LOCATION -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )

            // Storage Access Framework — scoped grant per file, no permission needed.
            DOCUMENT -> emptyArray()
        }

    /**
     * Rationale shown when the permission was refused.
     *
     * Per option rather than one generic sentence: "Zillit needs access" tells a user
     * nothing about what to enable, and the settings screen lists permissions by name.
     */
    @get:StringRes
    val permissionRationaleRes: Int
        get() = when (this) {
            CAMERA, VIDEO -> R.string.attachment_permission_camera
            GALLERY, VIDEO_GALLERY, AUDIO -> R.string.attachment_permission_media
            CONTACT -> R.string.attachment_permission_contacts
            LOCATION -> R.string.attachment_permission_location
            DOCUMENT -> R.string.attachment_permission_media
        }

    companion object {
        /** What an ordinary chat unit offers — v2's `allList`, minus the unused entries. */
        val CHAT_DEFAULT: Set<AttachmentOption> = setOf(
            CAMERA, GALLERY, VIDEO, VIDEO_GALLERY, AUDIO, DOCUMENT, LOCATION, CONTACT,
        )

        /**
         * Call Sheet. Documents only — v2 passes exactly `setOf(PICKER_ITEM_DOCUMENT)`,
         * because a call sheet is a document that replaces the previous one.
         */
        val CALL_SHEET: Set<AttachmentOption> = setOf(DOCUMENT)
    }
}
