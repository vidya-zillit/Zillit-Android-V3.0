package com.zillit.zillitapp.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Header names the backend expects. Server contract — do not rename. */
object ApiHeaders {
    const val MODULE_DATA = "moduledata"
    const val DEVICE_INFO = "deviceInfo"
    const val TIMEZONE = "Timezone"
    const val AUTHORIZATION = "Authorization"
    const val BODY_HASH = "bodyhash"
}

/**
 * The `deviceInfo` header — note this one is sent as **plaintext JSON**, unlike
 * `moduledata`. Carried over from v2's `AppHelper.getDeviceInfo()`.
 */
@Serializable
data class DeviceInfo(
    @SerialName("network") val network: String? = null,
    @SerialName("osversion") val osVersion: String? = null,
    @SerialName("deviceType") val deviceType: String? = null,
    @SerialName("deviceName") val deviceName: String? = null,
)

@Singleton
class DeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    fun asHeader(): String = json.encodeToString(
        DeviceInfo.serializer(),
        DeviceInfo(
            network = networkType(),
            osVersion = Build.VERSION.RELEASE,
            deviceType = "Android",
            deviceName = deviceName(),
        ),
    )

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }
    }

    private fun networkType(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "UNKNOWN"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "NO_NETWORK"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "UNKNOWN"
        }
    }
}
