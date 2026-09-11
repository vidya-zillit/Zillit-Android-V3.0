package com.zillit.zillitapp.core.errorlog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * The schema-v1 error-log envelope, exactly as the reporting spec defines it.
 *
 * The spec exists because three platforms invented twenty spellings for the same ten
 * concepts and made the shared collection unqueryable. The rules that bind everything
 * here: lower_snake_case keys, structured objects (never JSON-in-a-string), integer epoch
 * millis, a fresh UUID v4 per event, no moduledata in the body, and platform extras only
 * ever inside `context`.
 */

/** `category` — the closed set the spec allows. */
enum class LogCategory(val wire: String) {
    API("api"),
    SOCKET("socket"),
    SCREEN("screen"),
    LIFECYCLE("lifecycle"),
    CALL("call"),
    CRASH("crash"),
}

@Serializable
data class LogError(
    @SerialName("message") val message: String,
    @SerialName("code") val code: String? = null,
)

@Serializable
data class LogApi(
    @SerialName("url") val url: String,
    @SerialName("method") val method: String,
    /** Integer, or null when the request never reached the server. Never a string. */
    @SerialName("status") val status: Int? = null,
    @SerialName("request") val request: String? = null,
    @SerialName("response") val response: String? = null,
)

@Serializable
data class LogEventData(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("platform") val platform: String = "android",
    @SerialName("app_version") val appVersion: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("install_id") val installId: String,
    /** "wifi" | "cellular" | "none" | "unknown" — lowercase, the spec's exact set. */
    @SerialName("network") val network: String,
    /** Integer epoch millis, UTC. Never fractional, never ISO. */
    @SerialName("event_time") val eventTime: Long,
    @SerialName("category") val category: String,
    @SerialName("name") val name: String,
    /** Omitted entirely on success — never an empty string. */
    @SerialName("error") val error: LogError? = null,
    /** Required when category == "api". */
    @SerialName("api") val api: LogApi? = null,
    /** Free-form platform extras. The only place a new field may appear. */
    @SerialName("context") val context: JsonObject? = null,
)

/** One entry of the batch body's `data` array. */
@Serializable
data class LogEnvelope(
    /** Fresh UUID v4 per event. The server upserts on it: a reused id destroys the older event. */
    @SerialName("unique_id") val uniqueId: String,
    @SerialName("data") val data: LogEventData,
)

@Serializable
data class LogBatchRequest(
    @SerialName("data") val data: List<LogEnvelope>,
)
