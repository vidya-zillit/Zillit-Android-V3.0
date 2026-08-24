package com.zillit.zillitapp.core.network

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a response body into a model, with the two failure policies the app actually needs.
 *
 * Injected rather than static so every call site shares one configured [Json] — the
 * settings (`ignoreUnknownKeys`, `encodeDefaults = false`) are load-bearing against this
 * backend, and a second instance with defaults would break request signing.
 */
@Singleton
class ResponseParser @Inject constructor(
    // `@PublishedApi internal` because the inline overloads below reach it from other
    // modules' call sites; a plain `private` will not compile there.
    @PublishedApi internal val json: Json,
) {

    /** Parses, or null when the body does not match. For optional, best-effort reads. */
    inline fun <reified T> parseOrNull(rawJson: String): T? =
        runCatching { json.decodeFromString(serializer<T>(), rawJson) }.getOrNull()

    /**
     * Parses, or returns the decoding failure as an [ApiError.Parsing].
     *
     * Use this when the caller genuinely cannot continue without the model, so the
     * failure is surfaced rather than silently becoming null.
     */
    inline fun <reified T> parse(rawJson: String): ApiResult<T> = parse(rawJson, serializer<T>())

    fun <T> parse(rawJson: String, deserializer: DeserializationStrategy<T>): ApiResult<T> =
        runCatching { json.decodeFromString(deserializer, rawJson) }
            .fold(
                onSuccess = { ApiResult.Success(it) },
                onFailure = {
                    ApiResult.Failure(
                        ApiError.Parsing(it.message ?: "Unexpected response shape"),
                    )
                },
            )
}
