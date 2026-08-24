package com.zillit.zillitapp.core.session

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The identity every outbound request is stamped with.
 *
 * In v2 this state lived in `BaseViewModel.ProjectInfoData` — a companion object on a
 * god base class that ~every feature imported. Here it is a single injectable holder
 * with no UI coupling, so nothing has to extend anything to read it.
 *
 * Values are exposed as [StateFlow] so a screen can react to the active project
 * changing without polling.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _activeProject = MutableStateFlow<ActiveProject?>(null)
    val activeProject: StateFlow<ActiveProject?> = _activeProject.asStateFlow()

    /**
     * Returned by the server at registration/join and required by the
     * `SCANNER_DEVICE_ID` header variant and the transportation session.
     * See the QR device-linking flow.
     */
    private val _scannerDeviceId = MutableStateFlow("")
    val scannerDeviceId: StateFlow<String> = _scannerDeviceId.asStateFlow()

    /** Upload target, populated once the region/bucket config call returns. */
    private val _uploadTarget = MutableStateFlow<UploadTarget?>(null)
    val uploadTarget: StateFlow<UploadTarget?> = _uploadTarget.asStateFlow()

    /**
     * Calling uses a *separate* project/user pair from the active project —
     * v2 called these `tempProjectId`/`tempUserId`. Kept distinct on purpose:
     * an incoming call can target a project the user is not currently inside.
     */
    private val _callingIdentity = MutableStateFlow<CallingIdentity?>(null)
    val callingIdentity: StateFlow<CallingIdentity?> = _callingIdentity.asStateFlow()

    /**
     * Stable per-install identifier, and the field the backend keys every request on.
     * Resolved once and cached — it cannot change while the process lives.
     */
    val deviceId: String by lazy { readAndroidId() }

    @SuppressLint("HardwareIds")
    private fun readAndroidId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    fun setActiveProject(project: ActiveProject?) {
        _activeProject.value = project
    }

    fun setScannerDeviceId(id: String) {
        _scannerDeviceId.value = id
    }

    fun setUploadTarget(target: UploadTarget?) {
        _uploadTarget.value = target
    }

    fun setCallingIdentity(identity: CallingIdentity?) {
        _callingIdentity.value = identity
    }

    fun clear() {
        _activeProject.value = null
        _uploadTarget.value = null
        _callingIdentity.value = null
    }

    data class ActiveProject(
        val projectId: String,
        val userId: String,
        val enterpriseClientId: String? = null,
    )

    data class UploadTarget(
        val key: String?,
        val bucket: String?,
        val region: String?,
        val boxFolderId: String? = null,
    )

    data class CallingIdentity(
        val projectId: String,
        val userId: String,
        val primaryDeviceId: String,
    )
}
