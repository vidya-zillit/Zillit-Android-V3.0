package com.zillit.zillitapp.core.notification

import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import com.zillit.zillitapp.BuildConfig
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.preferences.AppPreferences
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Body of `PUT api/v2/device`. Field names match v2's `DeviceUpdateModel` exactly. */
@Serializable
data class DeviceUpdateRequest(
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("device_type") val deviceType: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("default_language") val defaultLanguage: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("fcm_token") val fcmToken: String? = null,
)

/**
 * Tells the backend where to push.
 *
 * **This is what makes realtime badges work at all.** Without it the app receives a token
 * from Firebase and never shares it, so the server has no address to send to and no push
 * ever arrives — the receiving pipeline can be perfectly correct and still see nothing.
 *
 * Mirrors v2: fetch the token from `FirebaseMessaging`, then `PUT api/v2/device` with
 * device details, using [ModuleData.DEFAULT] because this runs before any project is
 * selected.
 */
@Singleton
class DeviceRegistrar @Inject constructor(
    private val api: ZillitApi,
    private val preferences: AppPreferences,
) {

    /**
     * Fetches the current token and registers it. Called on every launch.
     *
     * Deliberately unconditional, matching v2. An earlier version skipped the call when
     * the stored token was unchanged, which looks like a sensible optimisation and is
     * wrong: a locally stored token proves only that *this app* has seen it, not that the
     * *server* still holds it. The server can lose the mapping — the row is cleared when
     * the device is unlinked from another session, and it did not exist at all for builds
     * that stored the token without registering. Every one of those cases ends with push
     * silently dead until a reinstall, which is far more expensive than one cheap PUT per
     * launch.
     */
    suspend fun registerIfNeeded(): Boolean {
        val token = currentToken()
        if (token.isNullOrBlank()) {
            ZillitLog.firebase("Token unavailable — push will not be delivered")
            return false
        }

        ZillitLog.firebase("Registering device, token=${token.abbreviate()}")
        return register(token)
    }

    /** Called from [ZillitMessagingService.onNewToken] when Firebase rotates the token. */
    suspend fun register(token: String): Boolean {
        val result = api.put<DeviceUpdateRequest, ActionEnvelope>(
            url = ApiEndpoints.Device.DETAILS,
            body = DeviceUpdateRequest(
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                deviceType = DEVICE_TYPE,
                osVersion = Build.VERSION.SDK_INT.toString(),
                defaultLanguage = Locale.getDefault().language,
                appVersion = BuildConfig.VERSION_NAME,
                fcmToken = token,
            ),
            module = ModuleData.DEFAULT,
        )

        return when (result) {
            is ApiResult.Success -> {
                // Stored only after the server accepts it, so a failed registration is
                // retried on the next launch instead of being treated as done.
                preferences.setFcmToken(token)
                ZillitLog.firebase("Device registered")
                true
            }

            is ApiResult.Failure -> {
                ZillitLog.firebase("Device registration failed: ${result.error.message}")
                false
            }
        }
    }

    /**
     * Firebase's token call is a `Task`; wrapped with a timeout so a device without
     * working Play Services cannot stall startup.
     */
    private suspend fun currentToken(): String? = withTimeoutOrNull(TOKEN_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    continuation.resume(task.result?.takeIf { task.isSuccessful })
                }
        }
    }

    private fun String.abbreviate(): String =
        if (length <= 12) this else "${take(6)}…${takeLast(4)}"

    private companion object {
        const val DEVICE_TYPE = "Android"
        const val TOKEN_TIMEOUT_MILLIS = 5_000L
    }
}

/** Action endpoints return `"data": {}`; `data` is deliberately not modelled. */
@Serializable
data class ActionEnvelope(
    @SerialName("status") val status: Int = 0,
    @SerialName("message") val message: String? = null,
)
