package com.zillit.zillitapp.core.auth

import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `POST /session/device` and `POST /session/device/refresh` both return this shape. */
@Serializable
data class DeviceSessionResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: DeviceSessionData? = null,
)

@Serializable
data class DeviceSessionData(
    @SerialName("device_access_token") val accessToken: String = "",
    @SerialName("device_refresh_token") val refreshToken: String = "",
    @SerialName("token_type") val tokenType: String = "Bearer",
    /** Seconds. Never hardcode the hour — the server may retune it. */
    @SerialName("expires_in") val expiresIn: Long = 0,
)

/** `POST /session/project`. */
@Serializable
data class ProjectSessionResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: ProjectSessionData? = null,
)

@Serializable
data class ProjectSessionData(
    @SerialName("project_access_token") val accessToken: String = "",
    /** "member" or "pending". For the UI only — the server does the gating. */
    @SerialName("scope") val scope: String? = null,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 0,
)

@Serializable
data class RefreshRequest(
    @SerialName("device_refresh_token") val refreshToken: String,
)

@Serializable
data class ProjectSessionRequest(
    @SerialName("project_id") val projectId: String,
)

/**
 * An access token and when it stops being one.
 *
 * [expiresAt] is taken from `expires_in` rather than from the JWT's own `exp`, because that
 * is the value the server tells us to schedule against — and the mail is explicit that the
 * lifetime may be retuned, so nothing here may assume an hour.
 */
data class AccessToken(
    val value: String,
    val expiresAt: Long,
) {
    fun isExpired(now: Long = System.currentTimeMillis(), skewMillis: Long = SKEW): Boolean =
        now >= expiresAt - skewMillis

    private companion object {
        /** Treated as expired slightly early, so a token cannot die in flight. */
        const val SKEW = 30_000L
    }
}

/**
 * The claims inside a project token.
 *
 * Decoded locally — the payload is base64, not a secret — only for logging and for sanity
 * checks like "is this token actually for the project I am asking about". Never trusted for
 * a decision the server should make.
 */
@Serializable
data class TokenClaims(
    @SerialName("typ") val type: String? = null,
    @SerialName("sub") val deviceId: String? = null,
    @SerialName("pid") val projectId: String? = null,
    @SerialName("uid") val userId: String? = null,
    @SerialName("iat") val issuedAt: Long? = null,
    @SerialName("exp") val expiresAt: Long? = null,
    @SerialName("jti") val tokenId: String? = null,
)

/** Reads a JWT's middle segment. Returns null for anything that is not a readable JWT. */
fun String.decodeTokenClaims(json: Json): TokenClaims? = runCatching {
    val payload = split('.').getOrNull(1) ?: return null
    val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    json.decodeFromString(TokenClaims.serializer(), decoded.decodeToString())
}.getOrNull()

/** The backend messages the token flow can fail with. */
object TokenErrors {
    /** Access token expired or invalid on a normal call → refresh and retry once. */
    const val INVALID_TOKEN = "libs_invalid_token"

    /** A device token was used on a project route → mint a project token. */
    const val INVALID_SCOPE = "libs_invalid_token_scope"

    /** Refresh token unknown, expired or revoked → the session is over. */
    const val REFRESH_INVALID = "session_refresh_invalid"

    /** A rotated refresh token was replayed; the server killed the session → log in again. */
    const val REFRESH_REUSE = "session_refresh_reuse_detected"

    /** Kill-switch: the feature is off server-side → fall back to moduledata. */
    const val AUTH_DISABLED = "session_token_auth_disabled"

    /** Handshake rejected on the socket for a bad/unknown device — the moduledata path. */
    const val INVALID_DEVICE_ID = "libs_invalid_device_id"
}
