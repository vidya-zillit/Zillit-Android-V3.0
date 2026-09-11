package com.zillit.zillitapp.core.auth

import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bearer-token session: what the app presents instead of `moduledata`.
 *
 * Three credentials, with deliberately different homes:
 *
 *  - **refresh token** — 90 days, the session itself. Sealed in the Keystore
 *    ([SecureTokenStore]) because it is the only one worth stealing.
 *  - **device access token** — an hour. Memory only. Mints project tokens, and is what the
 *    socket presents at its handshake.
 *  - **project access token** — an hour, one per project. Memory only. Attached to every
 *    ordinary API call.
 *
 * Project tokens are kept **per project id** rather than as a single current one: the app
 * legitimately talks to another project mid-session — the forward picker posts into one —
 * and a single slot would have each switch evict the other's token and re-mint it.
 *
 * ### The rule that matters
 * Refreshing is **single-flight**. A rotated refresh token is single-use, and the server
 * treats a replay as theft and kills the session — so two concurrent 401s must never both
 * rotate. Everyone shares one [Deferred]; the rest wait on it and retry with what it
 * produced.
 */
@Singleton
class TokenSession @Inject constructor(
    private val api: SessionApi,
    private val secureStore: SecureTokenStore,
    private val session: SessionStore,
    @com.zillit.zillitapp.core.di.ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Whether to use tokens at all — `token_auth_enabled` from `GET /configuration`.
     *
     * Off by default, so this whole class is dormant until the server says otherwise. The
     * flag is also turned off locally by the kill-switch response (section 7).
     */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var deviceToken: AccessToken? = null

    private val projectTokens = ConcurrentHashMap<String, AccessToken>()

    /** Guards `refreshInFlight` itself; the refresh runs outside the lock. */
    private val refreshLock = Mutex()

    @Volatile
    private var refreshInFlight: Deferred<Boolean>? = null

    private val establishLock = Mutex()

    /** Raised when the session is unrecoverable — the caller signs the user out. */
    private val _sessionLost = MutableStateFlow(false)
    val sessionLost: StateFlow<Boolean> = _sessionLost.asStateFlow()

    /**
     * Follows `token_auth_enabled` from the configuration store.
     *
     * Wired here, once, rather than at every fetch site — the store already re-publishes
     * whenever configuration is refreshed.
     */
    fun followConfiguration(flags: kotlinx.coroutines.flow.Flow<Boolean>) {
        scope.launch {
            flags.collect { enabled ->
                val wasEnabled = _enabled.value
                setEnabled(enabled)

                // The flag usually lands *after* the project has opened — the configuration
                // fetch is part of the project bootstrap — so the establish() at open time
                // ran while this was still false and did nothing. The moment the flag turns
                // on, catch up for whatever project is active; without this the app stays on
                // moduledata until the next project switch.
                if (enabled && !wasEnabled) {
                    session.activeProject.value?.projectId?.let { establish(it) }
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        ZillitLog.d(TAG, "token auth ${if (enabled) "enabled" else "disabled"} by configuration")
        _enabled.value = enabled
    }

    /**
     * The token the socket presents at its handshake.
     *
     * Read at **every** connect attempt, never cached by the caller: a reconnect hours later
     * must not re-ship the token captured at startup. Returns null when there is nothing
     * usable, which the socket reads as "use moduledata".
     */
    fun currentDeviceToken(): String? =
        deviceToken?.takeIf { !it.isExpired() }?.value.takeIf { _enabled.value }

    /** The token for an ordinary API call, or null to fall back to moduledata. */
    fun currentProjectToken(projectId: String?): String? {
        if (!_enabled.value || projectId.isNullOrBlank()) return null
        return projectTokens[projectId]?.takeIf { !it.isExpired() }?.value
    }

    /**
     * Exchanges the current credential for a session, then mints the project's token.
     *
     * Called when a project opens. Safe to call repeatedly — an existing valid token for
     * that project short-circuits.
     */
    suspend fun establish(projectId: String): Boolean {
        if (!_enabled.value) return false

        return establishLock.withLock {
            projectTokens[projectId]?.takeIf { !it.isExpired() }?.let { return@withLock true }

            // The device session first: either resumed from the stored refresh token, or
            // minted from moduledata, which is what `POST /session/device` expects.
            if (deviceToken?.isExpired() != false && !resumeOrCreateDeviceSession()) return@withLock false

            mintProjectToken(projectId)
        }
    }

    /**
     * Restores the session at launch without asking anyone to log in.
     *
     * The refresh token outlives the process, so a cold start rotates it rather than
     * starting from moduledata. Failure here is not fatal — the app falls back to
     * moduledata and tries again when a project opens.
     */
    suspend fun bootstrap(): Boolean {
        if (!_enabled.value) return false
        val stored = secureStore.readRefreshToken() ?: return false
        return refreshDevice(stored)
    }

    /**
     * Refreshes, and re-mints the project token.
     *
     * The **only** entry point for renewal. Everything that discovers a dead token — the
     * 401 path, the socket's rejected handshake, the proactive timer — comes through here,
     * which is what makes the single-flight rule hold across all of them.
     */
    suspend fun refresh(projectId: String? = null): Boolean {
        if (!_enabled.value) return false

        val existing = refreshLock.withLock {
            refreshInFlight?.let { return@withLock it }
            scope.async { performRefresh(projectId) }.also { refreshInFlight = it }
        }

        return runCatching { existing.await() }.getOrDefault(false).also {
            refreshLock.withLock { if (refreshInFlight === existing) refreshInFlight = null }
        }
    }

    private suspend fun performRefresh(projectId: String?): Boolean {
        val stored = secureStore.readRefreshToken()
        if (stored.isNullOrBlank()) {
            ZillitLog.d(TAG, "no refresh token stored — cannot renew")
            return false
        }

        if (!refreshDevice(stored)) return false

        // The project token is minted from the *new* device token, so it has to follow the
        // refresh rather than be renewed on its own.
        val target = projectId ?: session.activeProject.value?.projectId
        return target?.let { mintProjectToken(it) } ?: true
    }

    /** `POST /session/device/refresh` — rotates both tokens, old refresh discarded. */
    private suspend fun refreshDevice(refreshToken: String): Boolean {
        val result = api.refreshDevice(refreshToken)

        return when (result) {
            is SessionResult.Success -> {
                store(result.data)
                true
            }

            is SessionResult.Fatal -> {
                // Unknown, expired, revoked — or a replay the server read as theft. Either
                // way the session is gone and only a fresh login brings it back.
                ZillitLog.w(TAG, "refresh rejected (${result.message}) — session over")
                clear()
                _sessionLost.value = true
                false
            }

            is SessionResult.Disabled -> {
                setEnabled(false)
                false
            }

            is SessionResult.Failure -> {
                ZillitLog.w(TAG, "refresh failed: ${result.message}")
                false
            }
        }
    }

    /** `POST /session/device` — the moduledata-for-tokens exchange. */
    private suspend fun resumeOrCreateDeviceSession(): Boolean {
        secureStore.readRefreshToken()?.takeIf { it.isNotBlank() }?.let { stored ->
            if (refreshDevice(stored)) return true
            // A dead refresh token is not the end here: moduledata still works, so the
            // session is re-minted rather than sending the user to a login screen.
            _sessionLost.value = false
        }

        return when (val result = api.createDeviceSession()) {
            is SessionResult.Success -> {
                store(result.data)
                true
            }

            is SessionResult.Disabled -> {
                setEnabled(false)
                false
            }

            else -> {
                ZillitLog.w(TAG, "could not create a device session")
                false
            }
        }
    }

    private suspend fun mintProjectToken(projectId: String): Boolean {
        val device = deviceToken?.takeIf { !it.isExpired() } ?: return false

        return when (val result = api.createProjectSession(device.value, projectId)) {
            is SessionResult.Success -> {
                val data = result.data
                projectTokens[projectId] = AccessToken(
                    value = data.accessToken,
                    expiresAt = expiryFrom(data.expiresIn),
                )
                scheduleProactiveRefresh(data.expiresIn, projectId)
                ZillitLog.d(TAG, "project token minted for $projectId, scope=${data.scope}")
                true
            }

            is SessionResult.Disabled -> {
                setEnabled(false)
                false
            }

            else -> {
                ZillitLog.w(TAG, "could not mint a project token for $projectId")
                false
            }
        }
    }

    private suspend fun store(data: DeviceSessionData) {
        deviceToken = AccessToken(data.accessToken, expiryFrom(data.expiresIn))
        // Written before anything else can rotate again: the old one is dead the moment the
        // server answered, so losing the new one would strand the session.
        secureStore.writeRefreshToken(data.refreshToken)
        _sessionLost.value = false
    }

    /**
     * Renews at 80% of the token's life, per the spec.
     *
     * Driven off `expires_in` rather than a constant, so a server-side retune of the TTL is
     * picked up without a client change.
     */
    private fun scheduleProactiveRefresh(expiresIn: Long, projectId: String) {
        if (expiresIn <= 0) return
        proactiveJob?.cancel()
        proactiveJob = scope.launch {
            delay((expiresIn * PROACTIVE_FRACTION * 1000).toLong())
            if (_enabled.value) refresh(projectId)
        }
    }

    @Volatile
    private var proactiveJob: kotlinx.coroutines.Job? = null

    private fun expiryFrom(expiresIn: Long): Long =
        System.currentTimeMillis() + expiresIn * 1000

    /** Forgets everything. Called on logout, and when the server ends the session. */
    suspend fun clear() {
        proactiveJob?.cancel()
        deviceToken = null
        projectTokens.clear()
        refreshInFlight = null
        secureStore.clear()
    }

    /** Drops one project's token — used when a call comes back scoped wrong. */
    fun invalidateProject(projectId: String) {
        projectTokens.remove(projectId)
    }

    private companion object {
        const val TAG = "TokenSession"

        /** "~80% of life" from the spec — 48 minutes of an hour, 12 of the dev 15. */
        const val PROACTIVE_FRACTION = 0.8
    }
}
