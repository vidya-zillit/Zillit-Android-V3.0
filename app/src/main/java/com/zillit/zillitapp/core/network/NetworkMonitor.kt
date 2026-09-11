package com.zillit.zillitapp.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks real connectivity, not just "an interface exists".
 *
 * Two signals, because they mean different things to a long-lived socket:
 *
 *  - [isOnline] — whether a validated internet connection exists at all. Note this
 *    requires `NET_CAPABILITY_VALIDATED`, so a Wi-Fi network that is associated but has
 *    no working internet (captive portal, router with no uplink) reads as offline. That
 *    is exactly the "low/!bad internet" case: the socket would fail anyway, and we would
 *    rather know than keep retrying into a dead network.
 *
 *  - [networkChanges] — fires when the *active network changes identity*, e.g. Wi-Fi to
 *    cellular. This matters because an existing TCP connection is bound to the old
 *    network and is already dead even though nothing has told the socket yet. Waiting for
 *    the ping timeout to notice can take tens of seconds; reacting to this signal
 *    reconnects immediately.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _networkChanges = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val networkChanges: SharedFlow<Unit> = _networkChanges.asSharedFlow()

    /** Identity of the network we last saw, so we can tell a switch from a blip. */
    private var activeNetworkHandle: Long? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            val handle = network.networkHandle
            val previous = activeNetworkHandle
            activeNetworkHandle = handle
            _isOnline.value = currentlyOnline()
            if (previous != null && previous != handle) {
                // Switched networks — any existing connection is bound to the old one.
                _networkChanges.tryEmit(Unit)
            }
        }

        override fun onLost(network: Network) {
            if (activeNetworkHandle == network.networkHandle) activeNetworkHandle = null
            _isOnline.value = currentlyOnline()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            // Covers the captive-portal case: the network stays available but only
            // becomes VALIDATED once it actually reaches the internet.
            val wasOnline = _isOnline.value
            val nowOnline = currentlyOnline()
            _isOnline.value = nowOnline
            if (!wasOnline && nowOnline) _networkChanges.tryEmit(Unit)
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager?.registerNetworkCallback(request, callback) }
    }

    fun stop() {
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }

    private fun currentlyOnline(): Boolean {
        val cm = connectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Which pipe the device is on — for the error-log envelope's `network` field. */
    enum class Transport { WIFI, CELLULAR, NONE, OTHER }

    fun transportKind(): Transport {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            ?: return Transport.OTHER
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Transport.NONE
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> Transport.OTHER
        }
    }
}
