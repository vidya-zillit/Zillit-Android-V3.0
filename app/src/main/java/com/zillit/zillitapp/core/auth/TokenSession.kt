package com.zillit.zillitapp.core.auth

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.eventFlow
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The credential a request carries in token mode.
 *
 * The device token for device-level routes, or a project token bound to one project. A
 * project-scoped call made while no project is open resolves to [Device] rather than to
 * moduledata: several genuinely device-level routes use a project variant only because
 * that was the default the code was written with (`preset/project-types` on the create
 * screen, say), and the backend classes them as device-token calls.
 */
sealed interface TokenScope {
    data object Device : TokenScope
    data class Project(val projectId: String) : TokenScope
}

/**
 * The bearer-token session: what the app presents instead of `moduledata`.
 *
 * Three credentials, with deliberately different homes:
 *
 *  - **refresh token** — 90 days, the session itself. Sealed in the Keystore
 *    ([SecureTokenStore]) because it is the only one worth stealing.
 *  - **device access token** — memory only. Mints project tokens, and is what the socket
 *    presents at its handshake.
 *  - **project access token** — memory only, one per project. Attached to every ordinary
 *    API call.
 *
 * ### How the mode is decided — by `POST /session/device`, not by a flag
 * An earlier version waited for `token_auth_enabled` from `GET /configuration`. That flag
 * was dropped server-side (Sep 2026): `/configuration` is project-scoped while the token
 * flow starts at device level, before any project is chosen — so the app stayed on
 * moduledata forever, and the routes that have since tightened (mail, for one) answered
 * every call with an error that read like a data problem. v2 already follows the new rule
 * and this now matches it:
 *
 *  - `200 session_device_created` → token mode. The device token serves device-level calls
 *    and a project token is minted when a project opens.
 *  - `401 libs_invalid_device_id` → no device record yet, a fresh install before its first
 *    create or join. Stay on moduledata, and probe again once a project opens — that is
 *    the call which registers the device.
 *  - `session_token_auth_disabled` → the server kill-switch. Moduledata for the session.
 *
 * Token mode is therefore **on by default** and only turns off for those two proven cases.
 * A token-eligible call never *skips* the token: [bearerTokenFor] suspends while the
 * session is established and only falls back to moduledata once acquisition has genuinely
 * failed. The ordering is the point — an eager fallback shows up as a 401, not as a
 * graceful degrade.
 *
 * ### The rule that matters
 * Refreshing is **single-flight**. A rotated refresh token is single-use, and the server
 * treats a replay as theft and kills the session — so two concurrent 401s must never both
 * rotate. Everything that touches the refresh token goes through [refreshLock].
 */
@Singleton
class TokenSession @Inject constructor(
    private val api: SessionApi,
    private val secureStore: SecureTokenStore,
    private val session: SessionStore,
    @com.zillit.zillitapp.core.di.ApplicationScope private val scope: CoroutineScope,
) {

    /** Set by the server's explicit kill-switch verdict, and by nothing else. */
    @Volatile
    private var killSwitched = false

    /**
     * Set when the probe answers `libs_invalid_device_id`: this install has no device
     * record yet, so everything stays on moduledata until create/join has run.
     */
    @Volatile
    private var noDeviceYet = false

    /**
     * Quiet period after a transient establish failure. Token mode stays on, so without
     * this every request would fire its own `/session/device` while the server is down.
     */
    @Volatile
    private var establishBackoffUntil = 0L

    @Volatile
    private var deviceToken: AccessToken? = null

    private val projectTokens = ConcurrentHashMap<String, AccessToken>()

    /** Everything that touches the refresh token runs under this. */
    private val refreshLock = Mutex()

    /** Per-project, so concurrent requests to one project do not all mint. */
    private val mintLocks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var proactiveJob: Job? = null

    private val lifecycleWatched = AtomicBoolean(false)

    /**
     * Whether requests should carry a bearer token — everything except the two proven
     * exceptions. Deliberately does **not** require an established session: the app-start
     * probe is asynchronous, and the first burst of device-level calls must wait on it
     * inside [bearerTokenFor] rather than go out on moduledata because it has not finished.
     */
    val tokenMode: Boolean
        get() = !killSwitched && !noDeviceYet

    // ── Entry points ─────────────────────────────────────────────────────────

    /**
     * The mode probe — `POST /session/device`, which replaced the configuration flag.
     *
     * Call once at app start. Cheap and idempotent: a no-op when a usable device token is
     * cached, a refresh when a refresh token is stored, and a moduledata exchange only when
     * there is neither. A warm-up rather than a gate — requests establish on demand anyway;
     * this just keeps the first burst from each waiting on it, and is where
     * `libs_invalid_device_id` is discovered.
     *
     * Also arms the foreground wake seam: a token that aged out while the app sat in the
     * background is renewed as the app comes forward rather than on the first tap.
     */
    fun probe() {
        if (killSwitched) return
        scope.launch { warmUp() }

        if (lifecycleWatched.compareAndSet(false, true)) {
            scope.launch {
                ProcessLifecycleOwner.get().lifecycle.eventFlow
                    .filter { it == Lifecycle.Event.ON_START }
                    .collect { onAppForegrounded() }
            }
        }
    }

    /**
     * Wake seam — the app came to the foreground.
     *
     * The point-of-use check already guarantees correctness; this moves the renewal off the
     * critical path and clears any establish backoff so a session that failed while offline
     * is retried now rather than after the quiet period.
     */
    fun onAppForegrounded() {
        if (killSwitched) return
        establishBackoffUntil = 0L
        scope.launch { warmUp() }
    }

    /**
     * A project was opened.
     *
     * A project can only be opened once create/join has run, which is exactly what
     * registers the device — so a probe that answered `libs_invalid_device_id` is worth
     * retrying here. Otherwise mints the project's token ahead of the bootstrap burst.
     */
    fun onActiveProjectChanged(projectId: String) {
        if (noDeviceYet) {
            noDeviceYet = false
            ZillitLog.d(TAG, "device registered — re-probing /session/device")
            probe()
            return
        }
        if (!tokenMode) return
        projectTokens[projectId]?.takeIf { !it.isExpired() }?.let { return }
        scope.launch { projectToken(projectId, stale = null) }
    }

    /**
     * The bearer for one request, or null when none could be obtained — offline, a dead
     * session service — in which case the caller sends moduledata, which the server still
     * accepts through the migration.
     *
     * Suspends while the session is established: a token-eligible call waits for its
     * token rather than downgrading because the probe has not finished yet.
     */
    suspend fun bearerTokenFor(scope: TokenScope): String? {
        if (!tokenMode) return null
        return when (scope) {
            TokenScope.Device -> ensureDeviceToken()
            is TokenScope.Project -> projectToken(scope.projectId, stale = null)
        }
    }

    /**
     * Reactive 401 recovery. Renews (or re-mints) and hands back a token for the caller to
     * retry with **once**. [failedToken] is the one the 401'd request carried: if another
     * coroutine already recovered while we waited, that newer token is returned without a
     * second rotation.
     */
    suspend fun recoverFromUnauthorized(scope: TokenScope, failedToken: String): String? {
        if (!tokenMode) return null
        return when (scope) {
            TokenScope.Device -> refreshLock.withLock {
                deviceToken?.takeIf { it.value != failedToken && !it.isExpired() }?.value
                    ?: renewDeviceSessionLocked()
            }

            is TokenScope.Project -> projectToken(scope.projectId, stale = failedToken)
        }
    }

    /**
     * The device token for the socket handshake — cached only, never blocking.
     *
     * Socket set-up is synchronous, so when token mode is on but nothing usable is cached
     * this kicks a background establish and returns null; that connect goes out on
     * moduledata (dual-accepted) and the next rebuild picks the token up.
     */
    fun currentDeviceToken(): String? {
        if (!tokenMode) return null
        deviceToken?.takeIf { !it.isExpired() }?.let { return it.value }
        scope.launch { ensureDeviceToken() }
        return null
    }

    /** Establishes the device session if there is not one — for the socket's retry loop. */
    suspend fun ensureDeviceSession(): Boolean = ensureDeviceToken() != null

    /** Forces a device-session renewal — the socket's answer to a rejected token. */
    suspend fun refresh(): Boolean = refreshLock.withLock { renewDeviceSessionLocked() } != null

    /** The server said the feature is off. Moduledata for the rest of the session. */
    fun killSwitch() {
        if (killSwitched) return
        killSwitched = true
        ZillitLog.w(TAG, "kill-switch: token auth disabled by server")
    }

    /** Drops one project's token — the answer to a call that came back scoped wrong. */
    fun invalidateProject(projectId: String) {
        projectTokens.remove(projectId)
    }

    /** Forgets everything. Called on logout; the device record itself survives. */
    suspend fun clear() {
        proactiveJob?.cancel()
        proactiveJob = null
        deviceToken = null
        projectTokens.clear()
        secureStore.clear()
        noDeviceYet = false
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private suspend fun warmUp() {
        ensureDeviceToken() ?: return
        session.activeProject.value?.projectId?.takeIf { it.isNotBlank() }
            ?.let { projectToken(it, stale = null) }
    }

    private suspend fun ensureDeviceToken(): String? {
        deviceToken?.takeIf { !it.isExpired() }?.let { return it.value }
        return refreshLock.withLock {
            deviceToken?.takeIf { !it.isExpired() }?.let { return it.value }
            renewDeviceSessionLocked()
        }
    }

    /**
     * MUST hold [refreshLock]. Rotates via the stored refresh token when there is one;
     * otherwise — or when the server declares that token dead — falls back to a fresh
     * moduledata exchange. Moduledata never expires during the migration, so a dead refresh
     * token silently re-establishes instead of logging anyone out.
     */
    private suspend fun renewDeviceSessionLocked(): String? {
        secureStore.readRefreshToken()?.takeIf { it.isNotBlank() }?.let { stored ->
            when (val result = api.refreshDevice(stored)) {
                is SessionResult.Success -> {
                    adopt(result.data)
                    ZillitLog.d(TAG, "device session refreshed (expires_in=${result.data.expiresIn}s)")
                    return deviceToken?.value
                }

                is SessionResult.Disabled -> {
                    killSwitch()
                    return null
                }

                is SessionResult.Fatal -> {
                    // Unknown, expired, revoked, or read as a replay: the family is over.
                    // Not a logout — the moduledata exchange below starts a new one.
                    ZillitLog.w(TAG, "refresh token dead (${result.message}) — re-establishing")
                    deviceToken = null
                    projectTokens.clear()
                    secureStore.clear()
                }

                is SessionResult.Failure -> {
                    // Transport trouble: keep the stored token and try again later.
                    ZillitLog.w(TAG, "refresh failed: ${result.message}")
                    return null
                }
            }
        }

        if (System.currentTimeMillis() < establishBackoffUntil) return null

        return when (val result = api.createDeviceSession()) {
            is SessionResult.Success -> {
                adopt(result.data)
                ZillitLog.d(TAG, "device session established via moduledata (expires_in=${result.data.expiresIn}s)")
                deviceToken?.value
            }

            is SessionResult.Disabled -> {
                killSwitch()
                null
            }

            is SessionResult.Failure -> {
                if (result.message == TokenErrors.INVALID_DEVICE_ID) {
                    // Only this exact verdict means "no device record yet". Latching on any
                    // 401 would turn a clock-skew blip into hours of moduledata traffic.
                    noDeviceYet = true
                    secureStore.clear()
                    ZillitLog.d(TAG, "no device yet — staying on moduledata until create/join")
                } else {
                    establishBackoffUntil = System.currentTimeMillis() + ESTABLISH_BACKOFF_MS
                    ZillitLog.w(TAG, "establish failed (${result.message}) — retrying in ${ESTABLISH_BACKOFF_MS / 1000}s")
                }
                null
            }

            is SessionResult.Fatal -> {
                ZillitLog.w(TAG, "establish rejected: ${result.message}")
                null
            }
        }
    }

    private suspend fun adopt(data: DeviceSessionData) {
        // Persisted before the session is used: the previous refresh token is already
        // consumed server-side, so the new one has to survive a crash from here on.
        secureStore.writeRefreshToken(data.refreshToken)
        deviceToken = AccessToken(data.accessToken, expiryFrom(data.expiresIn))
        // A live session is itself the answer the old flag used to give.
        noDeviceYet = false
        establishBackoffUntil = 0L
        ensureProactiveLoop()
    }

    /**
     * [stale] is a token known to be rejected: a cached token equal to it is skipped so
     * recovery mints fresh, while one minted by someone else in the meantime is reused.
     */
    private suspend fun projectToken(projectId: String, stale: String?): String? {
        projectTokens[projectId]?.takeIf { !it.isExpired() && it.value != stale }
            ?.let { return it.value }
        return mintLocks.getOrPut(projectId) { Mutex() }.withLock {
            projectTokens[projectId]?.takeIf { !it.isExpired() && it.value != stale }
                ?.let { return it.value }
            mintProjectTokenLocked(projectId)
        }
    }

    /** MUST hold the project's mint lock. Lock order is mint lock → [refreshLock], never reverse. */
    private suspend fun mintProjectTokenLocked(projectId: String): String? {
        var device = ensureDeviceToken() ?: return null
        var result = api.createProjectSession(device, projectId)

        if (result is SessionResult.Failure && result.message in NO_ACCESS_VERDICTS) {
            // Not an auth problem: the device has no usable membership in that project.
            // Surfaces as no-access on the resource route; never retried as an auth error.
            ZillitLog.w(TAG, "project mint denied for $projectId: ${result.message}")
            return null
        }

        if (result is SessionResult.Failure && result.message == TokenErrors.INVALID_TOKEN) {
            // The device token was rejected despite looking fresh — renew once, retry once.
            val failed = device
            device = refreshLock.withLock {
                deviceToken?.takeIf { it.value != failed && !it.isExpired() }?.value
                    ?: renewDeviceSessionLocked()
            } ?: return null
            result = api.createProjectSession(device, projectId)
        }

        return when (result) {
            is SessionResult.Success -> {
                projectTokens[projectId] = AccessToken(result.data.accessToken, expiryFrom(result.data.expiresIn))
                ZillitLog.d(TAG, "project token minted for $projectId, scope=${result.data.scope}")
                result.data.accessToken
            }

            is SessionResult.Disabled -> {
                killSwitch()
                null
            }

            else -> {
                ZillitLog.w(TAG, "project mint failed for $projectId: ${(result as? SessionResult.Failure)?.message ?: (result as? SessionResult.Fatal)?.message}")
                null
            }
        }
    }

    /**
     * Renews at ~80% of the device token's life and keeps the active project's token warm,
     * so UI bursts never pay a renewal round trip. Driven off `expires_in`, never a constant.
     */
    @Synchronized
    private fun ensureProactiveLoop() {
        if (proactiveJob?.isActive == true) return
        proactiveJob = scope.launch {
            while (isActive && tokenMode) {
                val current = deviceToken ?: break
                val wait = current.renewAt - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                if (!tokenMode) break
                refreshLock.withLock {
                    deviceToken?.takeIf { !it.isExpired() }?.value ?: renewDeviceSessionLocked()
                } ?: break
                session.activeProject.value?.projectId?.takeIf { it.isNotBlank() }?.let {
                    projectToken(it, stale = projectTokens[it]?.value)
                }
            }
        }
    }

    private fun expiryFrom(expiresIn: Long): Long =
        System.currentTimeMillis() + (expiresIn.takeIf { it > 0 } ?: DEFAULT_TTL_SECONDS) * 1000

    private companion object {
        const val TAG = "TokenSession"

        /** Only for a malformed response missing `expires_in`. */
        const val DEFAULT_TTL_SECONDS = 3600L

        const val ESTABLISH_BACKOFF_MS = 30_000L

        val NO_ACCESS_VERDICTS = setOf(TokenErrors.NO_MEMBERSHIP, TokenErrors.ACCESS_DENIED)
    }
}
