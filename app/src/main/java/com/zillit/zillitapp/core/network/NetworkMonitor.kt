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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks real connectivity, not just "an interface exists".
 *
 * Two signals, because they mean different things to a long-lived socket:
 *
 *  - [isOnline] — whether a network that carries the internet exists.
 *
 *    Deliberately **not** `NET_CAPABILITY_VALIDATED`. Validation is Android's own reachability
 *    probe, and it stays false on any network that blocks or intercepts it — a corporate
 *    Wi-Fi, a VPN, some mobile carriers — while ordinary API traffic works perfectly. Gating
 *    on it reported "No internet connection" on a phone whose every request was succeeding,
 *    and, worse, silently short-circuited the mail sync and held the outbox shut.
 *
 *    It also **fails open**: if connectivity cannot be determined, the answer is online.
 *    Wrongly attempting a request costs one failed call; wrongly skipping it blocks the app
 *    on a working connection. This is v2's rule, for the same reasons.
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

        observeForeground()
    }

    /**
     * Re-reads connectivity whenever the app comes back to the foreground.
     *
     * The callback above is the only thing that updates [isOnline], and Android suppresses
     * and coalesces those for a backgrounded process — so a change that happens while the
     * phone is locked can leave this holding a stale answer that nothing ever corrects.
     * That is what put "No internet connection" on screen after an unlock while every
     * request was succeeding.
     *
     * v2 has no cached state at all: it asks the system on every check, so it cannot go
     * stale. Keeping the callback for instant reaction and re-reading on resume gets both.
     */
    private fun observeForeground() {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) refresh()
                },
            )
        }
    }

    /** Asks the system now, rather than trusting what the last callback left behind. */
    fun refresh() {
        _isOnline.value = currentlyOnline()
    }

    fun stop() {
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }

    private fun currentlyOnline(): Boolean = runCatching {
        val cm = connectivityManager ?: return@runCatching true
        // No active network at all is the one case that genuinely means offline. A network
        // whose capabilities cannot be read is not evidence of anything, so it reads online.
        val network = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching true
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

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
