package com.zillit.zillitapp.feature.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.labels.LabelRepository
import com.zillit.zillitapp.core.notification.BadgeSyncCoordinator
import com.zillit.zillitapp.core.preferences.AppPreferences
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.socket.SocketManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SplashDestination {
    /** Still deciding. */
    data object Undecided : SplashDestination
    data object ProjectList : SplashDestination
}

/**
 * Warms up the session and decides the first screen.
 *
 * Deliberately *not* a timer. v2's splash ran a `CountDownTimer` and navigated when it
 * fired, which meant the app either waited longer than it needed to on a fast device or
 * moved on before its work finished on a slow one. Here navigation happens when startup
 * work is actually done — the system splash screen stays up until then, so there is no
 * blank frame either way.
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val session: SessionStore,
    private val socketManager: SocketManager,
    private val labelRepository: LabelRepository,
    private val badgeSyncCoordinator: BadgeSyncCoordinator,
    private val directoryRealtime: com.zillit.zillitapp.core.directory.DirectoryRealtime,
    private val tokenSession: com.zillit.zillitapp.core.auth.TokenSession,
    private val storageCredentials: com.zillit.zillitapp.core.storage.StorageCredentialsStore,
    private val deviceRegistrar: com.zillit.zillitapp.core.notification.DeviceRegistrar,
) : ViewModel() {

    private val _destination = MutableStateFlow<SplashDestination>(SplashDestination.Undecided)
    val destination: StateFlow<SplashDestination> = _destination.asStateFlow()

    init {
        viewModelScope.launch {
            // Restore the QR-linked scanner id before any API call, since the
            // SCANNER_DEVICE_ID header variant reads it straight off the session.
            preferences.scannerDeviceId.first()?.let(session::setScannerDeviceId)

            // Start the socket now. It is idempotent and network-aware, so calling it
            // here costs nothing if there is no connection yet — it will come up on its
            // own once one appears.
            socketManager.start()

            // Starts watching the three conditions that require a badge resync:
            // network restored, socket (re)connected, and inbound realtime events.
            // Idempotent, and a no-op until a project is active.
            badgeSyncCoordinator.start()

            // Keeps the project directory current: profile edits, admin rights and
            // department changes arrive over the socket and are written straight into
            // Realm, so every screen reading a person picks them up without asking.
            directoryRealtime.start()

            // Bearer-token session. The probe — `POST /session/device` — is what decides
            // the mode now; the old `token_auth_enabled` flag no longer exists server-side.
            // Not awaited: requests establish the session on demand, and this only keeps
            // the first burst from each paying for it.
            tokenSession.probe()

            // Hands the FCM token to the backend. Without this the server has no address
            // to push to, so no notification arrives and realtime badges never fire —
            // regardless of how correct the receiving pipeline is.
            deviceRegistrar.registerIfNeeded()

            // Server label dictionaries. Server-supplied content (project types,
            // departments, backend messages) arrives as KEYS and is unreadable without
            // them.
            //
            // Version-gated, not unconditional: this only hits the network when Firebase
            // Remote Config's `locale_version` differs from the cached one, or the device
            // language changed. On a normal launch it is a no-op and the cached
            // dictionaries are used. Failure is non-fatal — the cache keeps working, and
            // a first run without network degrades to humanised keys rather than blocking
            // startup here.
            labelRepository.syncIfStale()

            _destination.value = SplashDestination.ProjectList
        }
    }
}
