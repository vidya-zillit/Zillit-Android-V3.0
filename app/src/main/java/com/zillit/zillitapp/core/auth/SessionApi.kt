package com.zillit.zillitapp.core.auth

import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiHeaders
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ModuleDataFactory
import com.zillit.zillitapp.core.network.ZillitCrypto
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** What a session call produced, in the four shapes the caller has to act on differently. */
sealed interface SessionResult<out T> {
    data class Success<T>(val data: T) : SessionResult<T>

    /** The session is over — refresh token unknown, expired, revoked or replayed. */
    data class Fatal(val message: String) : SessionResult<Nothing>

    /** Kill-switch: the server has token auth turned off. Fall back to moduledata. */
    data object Disabled : SessionResult<Nothing>

    /** Anything transient — no network, a 500. Worth retrying, not worth logging out for. */
    data class Failure(val message: String) : SessionResult<Nothing>
}

/**
 * The three session endpoints, called directly rather than through [ZillitApi].
 *
 * Deliberately its own client path: `ZillitApi` is where the Bearer header is attached and
 * where a 401 triggers a refresh, so minting tokens through it would mean a refresh that can
 * recurse into itself. These three calls each carry their own credential — moduledata, the
 * device token, or the refresh token in the body — and never the project token.
 */
@Singleton
class SessionApi @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
    private val moduleDataFactory: ModuleDataFactory,
    private val crypto: ZillitCrypto,
) {

    /** `POST /session/device` — exchanges the moduledata the app already sends for tokens. */
    suspend fun createDeviceSession(): SessionResult<DeviceSessionData> {
        val moduleData = crypto.encrypt(moduleDataFactory.build(ModuleData.DEFAULT))
        if (moduleData.isEmpty()) return SessionResult.Failure("moduledata unavailable")

        return call(
            url = ApiEndpoints.Session.DEVICE,
            body = null,
            headers = mapOf(ApiHeaders.MODULE_DATA to moduleData),
        ) { text -> json.decodeFromString(DeviceSessionResponse.serializer(), text).data }
    }

    /** `POST /session/project` — the token attached to every ordinary call. */
    suspend fun createProjectSession(
        deviceToken: String,
        projectId: String,
    ): SessionResult<ProjectSessionData> = call(
        url = ApiEndpoints.Session.PROJECT,
        body = json.encodeToString(
            ProjectSessionRequest.serializer(),
            ProjectSessionRequest(projectId),
        ),
        headers = mapOf(ApiHeaders.AUTHORIZATION to "Bearer $deviceToken"),
    ) { text -> json.decodeFromString(ProjectSessionResponse.serializer(), text).data }

    /**
     * `POST /session/device/refresh` — rotates the session.
     *
     * No auth header: the refresh token in the body *is* the credential. It is single-use,
     * so this must only ever be called from [TokenSession]'s single-flight path.
     */
    suspend fun refreshDevice(refreshToken: String): SessionResult<DeviceSessionData> = call(
        url = ApiEndpoints.Session.REFRESH,
        body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken)),
        headers = emptyMap(),
    ) { text -> json.decodeFromString(DeviceSessionResponse.serializer(), text).data }

    private suspend fun <T> call(
        url: String,
        body: String?,
        headers: Map<String, String>,
        parse: (String) -> T?,
    ): SessionResult<T> = runCatching {
        val response = client.request(url) {
            method = HttpMethod.Post
            headers.forEach { (name, value) -> header(name, value) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

        val text = response.bodyAsText()
        ZillitLog.d(TAG, "${url.substringAfterLast('/')} → ${response.status.value}")

        if (!response.status.isSuccess()) {
            val message = messageOf(text)
            return@runCatching when {
                // The kill-switch needs the server's explicit verdict. A bare 503 is a load
                // balancer having a moment — dev throws them routinely — and reading it as
                // "feature off" silently parked the whole session on moduledata.
                message == TokenErrors.AUTH_DISABLED -> SessionResult.Disabled

                message == TokenErrors.REFRESH_INVALID || message == TokenErrors.REFRESH_REUSE ->
                    SessionResult.Fatal(message)

                else -> SessionResult.Failure(message.ifEmpty { "HTTP ${response.status.value}" })
            }
        }

        parse(text)?.let { SessionResult.Success(it) }
            ?: SessionResult.Failure("empty session payload")
    }.getOrElse { SessionResult.Failure(it.message ?: "session request failed") }

    private fun messageOf(text: String): String = runCatching {
        json.parseToJsonElement(text).let { element ->
            (element as? kotlinx.serialization.json.JsonObject)
                ?.get("message")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                .orEmpty()
        }
    }.getOrDefault("")

    private companion object {
        const val TAG = "SessionApi"
    }
}
