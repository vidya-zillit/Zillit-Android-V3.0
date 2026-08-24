package com.zillit.zillitapp.core.network

/**
 * Outcome of a single API call.
 *
 * v2 signalled results through a callback interface with an int request code, which
 * meant every caller wrote a `when (reqCode)` block and errors were stringly-typed.
 * Here the call either returns data or a typed [ApiError], and the compiler enforces
 * that both are handled.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

sealed class ApiError(open val message: String) {

    /** No usable connection — the request never left the device. */
    data class NoConnection(override val message: String = "No internet connection") :
        ApiError(message)

    /** Connect/read timeout. */
    data class Timeout(override val message: String = "Request timed out") : ApiError(message)

    /** 401 — session no longer valid. */
    data class Unauthorized(override val message: String) : ApiError(message)

    /** 403 — authenticated but not permitted. */
    data class Forbidden(override val message: String) : ApiError(message)

    /** Device rejected by the backend (unknown/unlinked device id). */
    data class InvalidDevice(override val message: String) : ApiError(message)

    /**
     * Any other non-2xx.
     *
     * [message] is a label KEY, not a sentence — the backend never returns finished text.
     * [elements] are its `{search}` substitutions. Both are needed to render it; see
     * `ServerText`.
     */
    data class Http(
        val status: Int,
        override val message: String,
        val elements: List<com.zillit.zillitapp.core.labels.MessageElement> = emptyList(),
    ) : ApiError(message)

    /** Response body did not match the expected shape. */
    data class Parsing(override val message: String) : ApiError(message)

    /**
     * Encryption produced an empty `moduledata` header, which means the flavour's
     * key material is missing or too short. Fails fast rather than sending a
     * request the server will reject.
     */
    data class Configuration(override val message: String) : ApiError(message)

    data class Unknown(override val message: String) : ApiError(message)
}

inline fun <T> ApiResult<T>.onSuccess(block: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Success) block(data)
    return this
}

inline fun <T> ApiResult<T>.onFailure(block: (ApiError) -> Unit): ApiResult<T> {
    if (this is ApiResult.Failure) block(error)
    return this
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.data
