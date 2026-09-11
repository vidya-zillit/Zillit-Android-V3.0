package com.zillit.zillitapp.core.network

import com.zillit.zillitapp.core.session.SessionStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects which identity envelope goes into the encrypted `moduledata` header.
 *
 * This is a **server contract** carried over from v2 unchanged — the backend
 * decrypts `moduledata` and validates the fields it expects for the endpoint.
 * Do not add, rename, or reorder members without a backend change.
 */
enum class ModuleData {
    /** device_id + time_stamp only. */
    DEFAULT,

    /** + project_id. */
    WITH_PROJECT_ID,

    /** + project_id, user_id. The common case for project-scoped endpoints. */
    WITH_PROJECT_USER_ID,

    /** + upload key/bucket/region. Used by media upload endpoints. */
    WITH_PROJECT_USER_BUCKET_DATA,

    /** Box storage: enterprise_client_id + folder_id, deliberately no project/user. */
    WITH_PROJECT_USER_BOX_DATA,

    /** device_id explicitly blank — pre-registration calls. */
    EMPTY_DEVICE_ID,

    /** + scanner_device_id, from the QR device-linking flow. */
    SCANNER_DEVICE_ID,

    /** Uses the calling identity, not the active project. */
    CALLING_WITH_PROJECT_USER_ID,

    /** No moduledata at all — sends `Authorization: Bearer <token>`. */
    CHAT_GPT_TOKEN,

    /** No moduledata at all — third-party routing API, only an Accept header. */
    MAP_ROUTE_REQUEST,

    /** Notification ack: encrypts {projectId,userId} and omits the Timezone header. */
    NOTIFICATION_ACKNOWLEDGE,
    ;

    /** True when this variant sends the encrypted `moduledata` header. */
    val sendsModuleData: Boolean
        get() = this != CHAT_GPT_TOKEN && this != MAP_ROUTE_REQUEST

    /** v2 omits `Timezone` on the notification-ack path. Preserved exactly. */
    /**
     * Whether a bearer token can stand in for this variant.
     *
     * The token carries exactly `device_id` / `project_id` / `user_id` — the spec's words:
     * "the token carries the same device_id / project_id / user_id that moduledata used
     * to". The variants listed here carry nothing else, so the swap is lossless. The rest
     * embed extra fields the token does not have — bucket credentials, the scanner id, Box
     * ids, the calling identity — and swapping those would silently drop data the server
     * reads, so they stay on moduledata until the backend owns those fields elsewhere.
     */
    val tokenEligible: Boolean
        get() = this == DEFAULT || this == WITH_PROJECT_ID || this == WITH_PROJECT_USER_ID

    val sendsTimezone: Boolean
        get() = sendsModuleData && this != NOTIFICATION_ACKNOWLEDGE
}

/**
 * Wire model for the `moduledata` payload.
 *
 * Field order mirrors v2's Gson model deliberately. Nulls are omitted because
 * `encodeDefaults` is left at its kotlinx default of `false` and every field
 * defaults to null — which reproduces Gson's null-skipping behaviour.
 */
@Serializable
data class RequestHeader(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("time_stamp") val timeStamp: Long? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("key") val key: String? = null,
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("region") val region: String? = null,
    @SerialName("scanner_device_id") val scannerDeviceId: String? = null,
    @SerialName("enterprise_client_id") val enterpriseClientId: String? = null,
    @SerialName("folder_id") val folderId: String? = null,
    @SerialName("network") val network: String? = null,
    @SerialName("osversion") val osVersion: String? = null,
    @SerialName("deviceType") val deviceType: String? = null,
    @SerialName("deviceName") val deviceName: String? = null,
)

/** Payload for [ModuleData.NOTIFICATION_ACKNOWLEDGE] — camelCase, unlike the rest. */
@Serializable
data class NotificationRequestHeader(
    val projectId: String? = null,
    val userId: String? = null,
)

/**
 * Builds the plaintext JSON that [ZillitCrypto] then encrypts into `moduledata`.
 */
@Singleton
class ModuleDataFactory @Inject constructor(
    private val session: SessionStore,
    private val json: Json,
) {

    /**
     * @param projectOverride identity to stamp instead of the active project.
     *
     * Needed because some calls act on a project the user has not opened — the project
     * list toggles favourites before any project is active, so reading
     * `session.activeProject` would stamp an empty `project_id` and the server would
     * reject the request.
     */
    fun build(
        module: ModuleData,
        now: Long = System.currentTimeMillis(),
        projectOverride: SessionStore.ActiveProject? = null,
    ): String {
        val project = projectOverride ?: session.activeProject.value
        val upload = session.uploadTarget.value

        val header = when (module) {
            ModuleData.DEFAULT -> RequestHeader(
                deviceId = session.deviceId,
                timeStamp = now,
            )

            ModuleData.WITH_PROJECT_ID -> RequestHeader(
                projectId = project?.projectId.orEmpty(),
                deviceId = session.deviceId,
                timeStamp = now,
            )

            ModuleData.WITH_PROJECT_USER_BUCKET_DATA -> RequestHeader(
                projectId = project?.projectId.orEmpty(),
                deviceId = session.deviceId,
                timeStamp = now,
                userId = project?.userId.orEmpty(),
                key = upload?.key,
                bucket = upload?.bucket,
                region = upload?.region,
            )

            ModuleData.WITH_PROJECT_USER_BOX_DATA -> RequestHeader(
                deviceId = session.deviceId,
                timeStamp = now,
                enterpriseClientId = project?.enterpriseClientId,
                folderId = upload?.boxFolderId,
            )

            ModuleData.EMPTY_DEVICE_ID -> RequestHeader(
                deviceId = "",
                timeStamp = now,
            )

            ModuleData.SCANNER_DEVICE_ID -> RequestHeader(
                deviceId = session.deviceId,
                timeStamp = now,
                scannerDeviceId = session.scannerDeviceId.value,
            )

            ModuleData.CALLING_WITH_PROJECT_USER_ID -> {
                val calling = session.callingIdentity.value
                RequestHeader(
                    projectId = calling?.projectId.orEmpty(),
                    deviceId = session.deviceId,
                    timeStamp = now,
                    userId = calling?.userId.orEmpty(),
                )
            }

            // v2's `else` branch resolves to the project+user shape for everything
            // that still asks for a moduledata payload.
            ModuleData.WITH_PROJECT_USER_ID,
            ModuleData.CHAT_GPT_TOKEN,
            ModuleData.MAP_ROUTE_REQUEST,
            ModuleData.NOTIFICATION_ACKNOWLEDGE,
                -> RequestHeader(
                projectId = project?.projectId.orEmpty(),
                deviceId = session.deviceId,
                timeStamp = now,
                userId = project?.userId.orEmpty(),
            )
        }

        return json.encodeToString(RequestHeader.serializer(), header)
    }

    fun buildNotificationAck(payload: NotificationRequestHeader): String =
        json.encodeToString(NotificationRequestHeader.serializer(), payload)
}
