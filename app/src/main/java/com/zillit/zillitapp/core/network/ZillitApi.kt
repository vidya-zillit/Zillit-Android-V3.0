package com.zillit.zillitapp.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import java.io.IOException
import java.net.UnknownHostException
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for every Zillit HTTP call.
 *
 * Every request is stamped with the four contract headers — `moduledata` (encrypted),
 * `deviceInfo`, `Timezone`, `bodyhash` — before it leaves. Callers pick a [ModuleData]
 * variant and nothing else; they cannot accidentally send an unsigned request.
 *
 * Differences from v2, both deliberate:
 *  - **One shared [HttpClient].** v2 built a fresh `HttpClient(OkHttp)` per call via
 *    `NetworkClient.getHttpClient(...)`, which rebuilds the engine, connection pool and
 *    every plugin on each request. Here headers are applied per-request instead, so the
 *    client (and its connection pool) is reused.
 *  - **The body is serialized once** and that exact string is both sent and hashed, so
 *    the `bodyhash` can never disagree with the payload.
 */
@Singleton
class ZillitApi @Inject constructor(
    @PublishedApi internal val client: HttpClient,
    @PublishedApi internal val json: Json,
    private val moduleDataFactory: ModuleDataFactory,
    private val crypto: ZillitCrypto,
    private val signer: RequestSigner,
    private val deviceInfo: DeviceInfoProvider,
    private val chatGptTokenProvider: ChatGptTokenProvider,
    private val apiLogger: com.zillit.zillitapp.core.logging.ApiLogger,
    private val networkMonitor: NetworkMonitor,
) {

    suspend inline fun <reified T> get(
        url: String,
        module: ModuleData = ModuleData.WITH_PROJECT_USER_ID,
        query: Map<String, String> = emptyMap(),
        /** Signs for a project other than the active one — see the forward picker. */
        projectOverride: com.zillit.zillitapp.core.session.SessionStore.ActiveProject? = null,
    ): ApiResult<T> = execute(
        HttpMethod.Get, url, module, null, query, serializer(), projectOverride,
    )

    suspend inline fun <reified T> delete(
        url: String,
        module: ModuleData = ModuleData.WITH_PROJECT_USER_ID,
        query: Map<String, String> = emptyMap(),
    ): ApiResult<T> = execute(HttpMethod.Delete, url, module, null, query, serializer())

    suspend inline fun <reified B, reified T> post(
        url: String,
        body: B,
        module: ModuleData = ModuleData.WITH_PROJECT_USER_ID,
        query: Map<String, String> = emptyMap(),
        projectOverride: com.zillit.zillitapp.core.session.SessionStore.ActiveProject? = null,
    ): ApiResult<T> = execute(
        HttpMethod.Post, url, module, json.encodeToString(serializer<B>(), body), query,
        serializer(), projectOverride,
    )

    suspend inline fun <reified B, reified T> put(
        url: String,
        body: B,
        module: ModuleData = ModuleData.WITH_PROJECT_USER_ID,
        query: Map<String, String> = emptyMap(),
    ): ApiResult<T> = execute(
        HttpMethod.Put, url, module, json.encodeToString(serializer<B>(), body), query,
        serializer(),
    )

    suspend inline fun <reified B, reified T> patch(
        url: String,
        body: B,
        module: ModuleData = ModuleData.WITH_PROJECT_USER_ID,
        query: Map<String, String> = emptyMap(),
    ): ApiResult<T> = execute(
        HttpMethod.Patch, url, module, json.encodeToString(serializer<B>(), body), query,
        serializer(),
    )

    /**
     * GET returning the raw response body.
     *
     * Use when the payload shape is uncertain or varies — a decoding mismatch then stays
     * a parsing concern for the caller (via [ResponseParser]) instead of masquerading as
     * a failed request.
     */
    suspend fun getRaw(
        url: String,
        module: ModuleData = ModuleData.WITH_PROJECT_USER_ID,
        query: Map<String, String> = emptyMap(),
    ): ApiResult<String> = executeRaw(HttpMethod.Get, url, module, null, query)

    /** POST returning the raw response body. */
    suspend inline fun <reified B> postRaw(
        url: String,
        body: B,
        module: ModuleData = ModuleData.WITH_PROJECT_USER_ID,
        query: Map<String, String> = emptyMap(),
        projectOverride: com.zillit.zillitapp.core.session.SessionStore.ActiveProject? = null,
    ): ApiResult<String> = executeRaw(
        HttpMethod.Post, url, module, json.encodeToString(serializer<B>(), body), query,
        projectOverride,
    )

    @PublishedApi
    internal suspend fun executeRaw(
        method: HttpMethod,
        url: String,
        module: ModuleData,
        bodyJson: String?,
        query: Map<String, String>,
        projectOverride: com.zillit.zillitapp.core.session.SessionStore.ActiveProject? = null,
    ): ApiResult<String> = execute(
        method, url, module, bodyJson, query, RawBodyDeserializer, projectOverride,
    )

    @PublishedApi
    internal suspend fun <T> execute(
        method: HttpMethod,
        url: String,
        module: ModuleData,
        bodyJson: String?,
        query: Map<String, String>,
        deserializer: DeserializationStrategy<T>,
        projectOverride: com.zillit.zillitapp.core.session.SessionStore.ActiveProject? = null,
    ): ApiResult<T> {
        val startedAt = System.currentTimeMillis()
        var statusCode = 0
        var responseText: String? = null

        val result: ApiResult<T> = try {
            val headers = buildHeaders(module, bodyJson, projectOverride)
            if (headers == null) {
                ApiResult.Failure(
                    ApiError.Configuration(
                        "Encryption key material is missing or too short for this build flavour",
                    ),
                )
            } else {
                val response = client.request(url) {
                    this.method = method
                    headers.forEach { (name, value) -> header(name, value) }
                    query.forEach { (key, value) -> parameter(key, value) }
                    if (bodyJson != null) {
                        contentType(ContentType.Application.Json)
                        setBody(bodyJson)
                    }
                }
                statusCode = response.status.value
                val text = response.bodyAsText()
                responseText = text
                handleResponse(response, text, deserializer)
            }
        } catch (e: CancellationException) {
            // Cancellation is not a failure and must not be logged as one.
            throw e
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Failure(ApiError.Timeout())
        } catch (e: ConnectTimeoutException) {
            ApiResult.Failure(ApiError.Timeout())
        } catch (e: SocketTimeoutException) {
            ApiResult.Failure(ApiError.Timeout())
        } catch (e: UnknownHostException) {
            ApiResult.Failure(ApiError.NoConnection())
        } catch (e: IOException) {
            ApiResult.Failure(ApiError.NoConnection(e.message ?: "Network unavailable"))
        } catch (e: Exception) {
            ApiResult.Failure(ApiError.Unknown(e.message ?: e::class.java.simpleName))
        }

        // Single logging point: every outcome funnels through here, so no path can be
        // silently unrecorded.
        apiLogger.log(
            method = method.value,
            url = url,
            moduleDataVariant = module.name,
            requestBody = bodyJson,
            responseBody = responseText,
            statusCode = statusCode,
            durationMs = System.currentTimeMillis() - startedAt,
            error = (result as? ApiResult.Failure)?.error,
            networkType = if (networkMonitor.isOnline.value) "online" else "offline",
            moduleDataPlain = lastModuleDataPlain,
        )

        return result
    }

    /**
     * Returns null when the encrypted header could not be produced, which means the
     * flavour is misconfigured. Sending anyway would just get a 4xx from the server.
     */
    /** Plaintext `moduledata` of the most recent request, for debug logging only. */
    @Volatile
    private var lastModuleDataPlain: String? = null

    private suspend fun buildHeaders(
        module: ModuleData,
        bodyJson: String?,
        projectOverride: com.zillit.zillitapp.core.session.SessionStore.ActiveProject? = null,
    ): Map<String, String>? {
        if (!module.sendsModuleData) {
            return when (module) {
                ModuleData.CHAT_GPT_TOKEN -> {
                    val token = chatGptTokenProvider.token() ?: return null
                    mapOf(ApiHeaders.AUTHORIZATION to "Bearer $token")
                }
                // Third-party routing service: no Zillit auth, only content negotiation.
                else -> mapOf(
                    "Accept" to
                        "application/json, application/geo+json, application/gpx+xml, img/png; charset=utf-8",
                )
            }
        }

        val plain = moduleDataFactory.build(module, projectOverride = projectOverride)
        lastModuleDataPlain = plain
        val encrypted = crypto.encrypt(plain)
        if (encrypted.isEmpty()) return null

        return buildMap {
            put(ApiHeaders.MODULE_DATA, encrypted)
            put(ApiHeaders.DEVICE_INFO, deviceInfo.asHeader())
            if (module.sendsTimezone) {
                put(ApiHeaders.TIMEZONE, Calendar.getInstance().timeZone.id)
            }
            put(ApiHeaders.BODY_HASH, signer.bodyHash(bodyJson, encrypted))
        }
    }

    private fun <T> handleResponse(
        response: HttpResponse,
        text: String,
        deserializer: DeserializationStrategy<T>,
    ): ApiResult<T> {
        if (!response.status.isSuccess()) {
            // Deliberately no fallback to `status.description`: that produced "Bad
            // Gateway" being looked up as a label key and rendered verbatim. An empty
            // message lets the UI layer choose the right generic copy.
            val message = extractBackendMessage(text).orEmpty()
            return ApiResult.Failure(
                when (response.status.value) {
                    401 -> ApiError.Unauthorized(message)
                    403 -> ApiError.Forbidden(message)
                    else -> ApiError.Http(
                        status = response.status.value,
                        message = message,
                        elements = extractMessageElements(text),
                    )
                },
            )
        }

        if (deserializer === RawBodyDeserializer) {
            @Suppress("UNCHECKED_CAST")
            return ApiResult.Success(text as T)
        }

        return runCatching { json.decodeFromString(deserializer, text) }
            .fold(
                onSuccess = { ApiResult.Success(it) },
                onFailure = {
                    ApiResult.Failure(
                        ApiError.Parsing(it.message ?: "Unexpected response shape"),
                    )
                },
            )
    }

    /**
     * Pulls `messageElements` out of an error body.
     *
     * Sent alongside `message` on most endpoints; without them a resolved template still
     * shows its `{placeholders}`.
     */
    private fun extractMessageElements(
        body: String,
    ): List<com.zillit.zillitapp.core.labels.MessageElement> = runCatching {
        val array = json.parseToJsonElement(body).jsonObject["messageElements"]
        json.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(
                com.zillit.zillitapp.core.labels.MessageElement.serializer(),
            ),
            array ?: return emptyList(),
        )
    }.getOrDefault(emptyList())

    /** Pulls the backend's own `message`/`error` field out of an error body. */
    private fun extractBackendMessage(body: String): String? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        (obj["message"] ?: obj["error"] ?: obj["msg"])?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * Supplies the bearer token for [ModuleData.CHAT_GPT_TOKEN].
 *
 * Not wired to a real store yet — no v3 screen uses the ChatGPT endpoint. Returning
 * null makes such a call fail loudly with [ApiError.Configuration] rather than
 * silently sending an unauthenticated request.
 */
interface ChatGptTokenProvider {
    suspend fun token(): String?
}

@Singleton
class UnconfiguredChatGptTokenProvider @Inject constructor() : ChatGptTokenProvider {
    override suspend fun token(): String? = null
}

/**
 * Passes the response body through unchanged.
 *
 * Lets the raw request path reuse [ZillitApi]'s single execute/logging pipeline instead
 * of a parallel copy that could drift from it.
 */
private object RawBodyDeserializer : DeserializationStrategy<String> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "RawBody",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING,
    )

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): String =
        error("RawBodyDeserializer is never invoked; handleResponse short-circuits it")
}
