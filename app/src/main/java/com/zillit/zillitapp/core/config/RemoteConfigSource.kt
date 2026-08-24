package com.zillit.zillitapp.core.config

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Firebase Remote Config, wrapped so the rest of the app never touches Firebase directly.
 *
 * Only one key is read today — [localeVersion] — but the wrapper matters: it turns a
 * callback-based `Task` into a suspend function, and keeps Firebase out of repositories
 * so they stay testable.
 */
@Singleton
class RemoteConfigSource @Inject constructor() {

    private val remoteConfig: FirebaseRemoteConfig by lazy {
        FirebaseRemoteConfig.getInstance().apply {
            setConfigSettingsAsync(
                FirebaseRemoteConfigSettings.Builder()
                    // Firebase's default throttle is 12 hours, which would make a label
                    // change invisible for half a day.
                    .setMinimumFetchIntervalInSeconds(MIN_FETCH_INTERVAL_SECONDS)
                    .build(),
            )
            setDefaultsAsync(mapOf(KEY_LOCALE_VERSION to DEFAULT_LOCALE_VERSION))
        }
    }

    /**
     * Fetches and activates. Returns false on failure — callers fall back to the values
     * already cached by Firebase, so a fetch failure is never fatal.
     */
    suspend fun refresh(): Boolean = withTimeoutOrNull(FETCH_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            remoteConfig.fetchAndActivate()
                .addOnCompleteListener { task ->
                    if (continuation.isActive) continuation.resume(task.isSuccessful)
                }
        }
    } ?: false

    /**
     * The server's label-dictionary version.
     *
     * This is the mechanism that stops the app re-downloading label dictionaries on every
     * launch: the backend team bumps `locale_version` in the Firebase console when the
     * translations change, and only then does the client re-fetch. Same key v2 uses
     * (`Constants.UPDATE_JSON = "locale_version"`), so both apps stay in step off one
     * console value.
     */
    val localeVersion: Long
        get() = remoteConfig.getLong(KEY_LOCALE_VERSION)

    private companion object {
        const val KEY_LOCALE_VERSION = "locale_version"

        /** 0 means "older than any real version", so a first run always fetches. */
        const val DEFAULT_LOCALE_VERSION = 0L

        const val MIN_FETCH_INTERVAL_SECONDS = 3600L

        /**
         * Hard ceiling on the fetch. Splash awaits this, and a Firebase `Task` that never
         * completes — no Play Services, a blocked network, a captive portal — would
         * otherwise leave the app on the splash screen indefinitely with no way out.
         */
        const val FETCH_TIMEOUT_MILLIS = 5_000L
    }
}
