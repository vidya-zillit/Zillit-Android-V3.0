package com.zillit.zillitapp.feature.linkeddevice.data

import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.feature.linkeddevice.domain.LinkedDevice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LinkedDeviceListResponse(
    @SerialName("data") val data: List<LinkedDeviceDto> = emptyList(),
    @SerialName("message") val message: String? = null,
    @SerialName("status") val status: Int? = null,
)

@Serializable
data class LinkedDeviceDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("device_info") val deviceInfo: DeviceInfoDto? = null,
    @SerialName("last_activity") val lastActivity: Long? = null,
    @SerialName("parent_device_id") val parentDeviceId: String? = null,
    @SerialName("primary_device_id") val primaryDeviceId: String? = null,
)

@Serializable
data class DeviceInfoDto(
    @SerialName("deviceName") val deviceName: String? = null,
    @SerialName("deviceType") val deviceType: String? = null,
    @SerialName("osVersion") val osVersion: String? = null,
)

@Serializable
data class UnlinkDeviceRequest(
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class ScanQrRequest(
    @SerialName("code") val code: String,
    @SerialName("file_name") val fileName: String = QR_FILE_NAME,
)

@Serializable
data class SimpleResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
)

/** v2 sends this constant with every scan; the backend expects the field present. */
private const val QR_FILE_NAME = "filename.png"

/**
 * Linked devices — the web/tablet sessions attached to this account.
 *
 * Not cached in Realm, unlike projects: the whole point of the screen is to show which
 * sessions are live *right now*, and a stale list would invite the user to log out a
 * device that is already gone. Every open re-fetches.
 */
@Singleton
class LinkedDeviceRepository @Inject constructor(
    private val api: ZillitApi,
) {

    suspend fun getLinkedDevices(): ApiResult<List<LinkedDevice>> =
        when (
            val result = api.get<LinkedDeviceListResponse>(
                url = ApiEndpoints.Device.LINKED,
                module = ModuleData.WITH_PROJECT_USER_ID,
            )
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(result.data.data.map { it.toDomain() })
        }

    /** Logs the given device out. The caller refreshes the list on success. */
    suspend fun unlink(deviceId: String): ApiResult<Unit> =
        when (
            val result = api.post<UnlinkDeviceRequest, SimpleResponse>(
                url = ApiEndpoints.Device.UNLINK,
                body = UnlinkDeviceRequest(deviceId),
                module = ModuleData.WITH_PROJECT_USER_ID,
            )
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(Unit)
        }

    /** Links a new device from a scanned QR payload. */
    suspend fun linkByQrCode(code: String): ApiResult<Unit> =
        when (
            val result = api.post<ScanQrRequest, SimpleResponse>(
                url = ApiEndpoints.Device.SCAN_QR,
                body = ScanQrRequest(code = code),
                module = ModuleData.WITH_PROJECT_USER_ID,
            )
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(Unit)
        }
}

private fun LinkedDeviceDto.toDomain(): LinkedDevice = LinkedDevice(
    id = id.orEmpty(),
    deviceName = deviceInfo?.deviceName.orEmpty(),
    deviceType = deviceInfo?.deviceType.orEmpty(),
    osVersion = deviceInfo?.osVersion,
    // v2 treats 0 and 1 as "no real timestamp" and falls back to now, so the row never
    // shows an epoch date.
    lastActivity = lastActivity?.takeIf { it > 1L } ?: System.currentTimeMillis(),
    isPrimary = !primaryDeviceId.isNullOrBlank() && primaryDeviceId == id,
)
