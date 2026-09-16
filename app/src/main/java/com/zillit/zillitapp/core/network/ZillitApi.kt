package com.zillit.zillitapp.core.network

import com.zillit.zillitapp.core.auth.TokenErrors
import com.zillit.zillitapp.core.auth.TokenScope
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
    private val tokenSession: dagger.Lazy<com.zillit.zillitapp.core.auth.TokenSession>,
    private val errorLog: dagger.Lazy<com.zillit.zillitapp.core.errorlog.ErrorLogReporter>,
    private val session: com.zillit.zillitapp.core.session.SessionStore,
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

    /**
     * DELETE carrying a body.
     *
     * Unusual, and the mail service's shape: deleting folders, messages and drafts all
     * identify the target in the body rather than the path. Ktor allows it; the signing
     * pipeline treats it like any other body, so the `bodyhash` still covers what is sent.
     */
    suspend inline fun <reified B, reified T> deleteWithBody(
        url: String,
        body: B,
        module: ModuleData = ModuleData.WITH_PROJECT_USER_ID,
        query: Map<String, String> = emptyMap(),
    ): ApiResult<T> = execute(
        HttpMethod.Delete, url, module, json.encodeToString(serializer<B>(), body), query,
        serializer(),
    )

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
        projectOverride: com.zillit.zillitapp.core.session.SessionStore.ActiveProject? = null,
    ): ApiResult<T> = execute(
        HttpMethod.Patch, url, module, json.encodeToString(serializer<B>(), body), query,
        serializer(), projectOverride,
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
        // Guards the single retry the spec allows. A second 401 is a real one.
        var retried = false

        val result: ApiResult<T> = try {
            // The token this call rides on, when it rides on one. Resolved once so the
            // 401 path can name exactly which token was rejected.
            val scope = module.tokenScope(projectOverride)
            var bearer = scope?.let { tokenSession.get().bearerTokenFor(it) }
            var headers = buildHeaders(module, bodyJson, projectOverride, bearer)
            if (headers == null) {
                ApiResult.Failure(
                    ApiError.Configuration(
                        "Encryption key material is missing or too short for this build flavour",
                    ),
                )
            } else {
                suspend fun send(with: Map<String, String>): HttpResponse = client.request(url) {
                    this.method = method
                    with.forEach { (name, value) -> header(name, value) }
                    query.forEach { (key, value) -> parameter(key, value) }
                    if (bodyJson != null) {
                        contentType(ContentType.Application.Json)
                        setBody(bodyJson)
                    }
                }

                var response = send(headers)
                var text = response.bodyAsText()

                // The token paths, in the order the spec describes them.
                //
                // 401 on a bearer → recover once, retry once. Deliberately *not* a logout:
                // v2's old rule was 401 = sign out, and keeping that would sign people out
                // every time an hour passed. Recovery is the session's single-flight path,
                // so a burst of 401s rotates exactly once.
                if (response.status.value == 401 && bearer != null && scope != null && !retried) {
                    retried = true
                    val tokens = tokenSession.get()
                    val message = extractBackendMessage(text)

                    // A scope error means the wrong *kind* of token, not a dead one — the
                    // fix is a project token, not a refresh.
                    val recovered = if (message == TokenErrors.INVALID_SCOPE && scope is TokenScope.Project) {
                        tokens.invalidateProject(scope.projectId)
                        tokens.bearerTokenFor(scope)
                    } else {
                        tokens.recoverFromUnauthorized(scope, bearer)
                    }

                    if (recovered != null && recovered != bearer) {
                        bearer = recovered
                        headers = buildHeaders(module, bodyJson, projectOverride, bearer) ?: headers
                        response = send(headers)
                        text = response.bodyAsText()
                    }
                }

                // Kill-switch: the feature is off server-side. Turn it off locally and
                // repeat the call the old way — moduledata still works throughout the
                // migration, so this must not surface as an error.
                if (bearer != null && extractBackendMessage(text) == TokenErrors.AUTH_DISABLED) {
                    tokenSession.get().killSwitch()
                    headers = buildHeaders(module, bodyJson, projectOverride, bearer = null) ?: headers
                    response = send(headers)
                    text = response.bodyAsText()
                }

                statusCode = response.status.value
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

        // Failures go to the server-side error log — the same funnel as the local one, so
        // no failing path can slip past it. Successes are deliberately not sent (spec
        // rule 8: log the failure, not the noise).
        (result as? ApiResult.Failure)?.let { failure ->
            errorLog.get().reportApiFailure(
                url = url,
                method = method.value,
                status = statusCode.takeIf { it > 0 },
                errorMessage = failure.error.message.ifBlank { failure.error::class.java.simpleName },
                request = bodyJson,
                response = responseText,
            )
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

    /**
     * Which token a variant rides on, or null when it must stay on moduledata.
     *
     * Mirrors v2's `TokenAuth.scopeFor`. The identity variants carry nothing the token does
     * not; `BUCKET_DATA` rides too, since in this app it fronts ordinary CRUD routes (the
     * S3 transfer itself goes through the SDK, not through here). A project variant with no
     * project open falls back to the **device** token rather than to moduledata: several
     * device-level routes use a project variant only by default, and the backend classes
     * them as device-token calls — moduledata there is what `libs_moduledata_not_accepted`
     * looks like. The scanner, Box and pre-registration variants feed the only routes that
     * read non-identity header fields, and stay legacy.
     */
    private fun ModuleData.tokenScope(
        projectOverride: com.zillit.zillitapp.core.session.SessionStore.ActiveProject?,
    ): TokenScope? {
        fun projectOrDevice(projectId: String?): TokenScope =
            projectId?.takeIf { it.isNotBlank() }?.let { TokenScope.Project(it) } ?: TokenScope.Device

        return when (this) {
            ModuleData.DEFAULT -> TokenScope.Device

            ModuleData.WITH_PROJECT_ID,
            ModuleData.WITH_PROJECT_USER_ID,
            ModuleData.WITH_PROJECT_USER_BUCKET_DATA,
            ModuleData.NOTIFICATION_ACKNOWLEDGE,
                -> projectOrDevice(projectOverride?.projectId ?: session.activeProject.value?.projectId)

            ModuleData.CALLING_WITH_PROJECT_USER_ID ->
                projectOrDevice(session.callingIdentity.value?.projectId)

            ModuleData.SCANNER_DEVICE_ID,
            ModuleData.WITH_PROJECT_USER_BOX_DATA,
            ModuleData.EMPTY_DEVICE_ID,
            ModuleData.CHAT_GPT_TOKEN,
            ModuleData.MAP_ROUTE_REQUEST,
                -> null
        }
    }

    private suspend fun buildHeaders(
        module: ModuleData,
        bodyJson: String?,
        projectOverride: com.zillit.zillitapp.core.session.SessionStore.ActiveProject? = null,
        /** The bearer this call rides on; null sends moduledata instead. */
        bearer: String? = null,
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

        // Token mode: the bearer replaces moduledata entirely — no encrypted blob, and so
        // no body hash, which existed only to sign that blob. Everything else about the
        // request is unchanged, which is the whole point of the migration. Sending
        // moduledata *alongside* a token does not work — the token path never decrypts
        // it — so it is one or the other.
        bearer?.let { token ->
            lastModuleDataPlain = null
            return buildMap {
                put(ApiHeaders.AUTHORIZATION, "Bearer $token")
                put(ApiHeaders.DEVICE_INFO, deviceInfo.asHeader())
                if (module.sendsTimezone) {
                    put(ApiHeaders.TIMEZONE, Calendar.getInstance().timeZone.id)
                }
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
                    // A device the backend has never seen — a fresh install before its
                    // first create or join. Not a dead session: there is nothing to sign
                    // back into, and the caller decides what an unknown device means.
                    401 -> if (message == TokenErrors.INVALID_DEVICE_ID) {
                        ApiError.InvalidDevice(message)
                    } else {
                        ApiError.Unauthorized(message)
                    }
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
