package com.zillit.zillitapp.core.network

import com.zillit.zillitapp.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the `bodyhash` header.
 *
 * Server contract, reproduced exactly from v2:
 *
 * ```
 * bodyhash = SHA-256( {"payload":<body|"">,"moduledata":"<encrypted hex>"} + IV_KEY )
 * ```
 *
 * Two things that are easy to get wrong and both matter:
 *  - **Key order is `payload` then `moduledata`.** The hash covers the serialized
 *    object, so swapping them produces a different digest and a rejected request.
 *  - **`payload` is the parsed JSON element, not a string.** A body of `{"a":1}`
 *    embeds as `{"a":1}`, not as `"{\"a\":1}"`. Bodies may legitimately be arrays —
 *    v2 shipped a bug here until 2026-07-20 where bare-array bodies threw before
 *    the request left the device.
 *
 * Improvement over v2: v2 re-serialized the request object to derive the hash,
 * separately from whatever the HTTP layer actually sent, so the two could drift.
 * Here the caller serializes once and passes the exact body string that goes on
 * the wire, so the digest always covers what was really sent.
 */
@Singleton
class RequestSigner @Inject constructor(
    private val json: Json,
) {

    /**
     * @param rawBody the exact JSON string being sent as the request body, or null
     *                for bodyless requests (GET/DELETE).
     * @param moduleDataHeader the already-encrypted `moduledata` header value.
     */
    fun bodyHash(rawBody: String?, moduleDataHeader: String): String {
        val input = signingString(rawBody, moduleDataHeader)
        if (input.isEmpty()) return ""

        val combined = (input + SALT).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(combined).toHex()
    }

    /** Exposed for tests — this is the string that gets salted and hashed. */
    internal fun signingString(rawBody: String?, moduleDataHeader: String): String {
        val payload: JsonElement = rawBody
            ?.takeIf { it.startsWith("{") || it.startsWith("[") }
            ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            ?: JsonPrimitive("")

        return buildJsonObject {
            put("payload", payload)
            put("moduledata", JsonPrimitive(moduleDataHeader))
        }.toString()
    }

    private companion object {
        /** v2 salts with `Constants.IV_ENCRYPTION_KEY`, which is just `BuildConfig.IV_KEY`. */
        val SALT: String get() = BuildConfig.IV_KEY
    }
}
