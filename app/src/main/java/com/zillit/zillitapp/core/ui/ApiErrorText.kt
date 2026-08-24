package com.zillit.zillitapp.core.ui

import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.network.ApiError

/**
 * Turns a network failure into something a user can read, in their own language.
 *
 * Each branch maps to a string resource rather than a literal. The only case that passes
 * server text straight through is [ApiError.Http], where the backend's own message is
 * more specific than anything generic we could write — and it arrives already localized.
 * When the backend sends nothing useful, that falls back to a translated generic message.
 */
fun ApiError?.toUiText(): UiText = when (this) {
    null -> UiText.of(R.string.error_generic)

    is ApiError.NoConnection -> UiText.of(R.string.error_no_connection)
    is ApiError.Timeout -> UiText.of(R.string.error_timeout)
    is ApiError.Unauthorized -> UiText.of(R.string.error_session_expired)
    is ApiError.Forbidden -> UiText.of(R.string.error_forbidden)
    is ApiError.InvalidDevice -> UiText.of(R.string.error_invalid_device)
    is ApiError.Configuration -> UiText.of(R.string.error_configuration)
    is ApiError.Parsing -> UiText.of(R.string.error_unexpected_response)

    is ApiError.Http -> when {
        // 5xx is infrastructure, not something the user did or can act on. These come
        // back as an nginx HTML page rather than the usual JSON envelope, so there is no
        // label key to resolve — showing the raw status ("Bad Gateway") or the HTML
        // leaks plumbing into the UI. Always a generic, actionable sentence instead.
        status >= HTTP_SERVER_ERROR -> UiText.of(R.string.error_server_unavailable)

        // Otherwise the backend returns a label KEY, not a sentence, resolved against the
        // server dictionary like any other label.
        message.isNotBlank() ->
            UiText.ServerMessage(com.zillit.zillitapp.core.labels.ServerText(message, elements))

        else -> UiText.of(R.string.error_generic)
    }

    is ApiError.Unknown -> UiText.of(R.string.error_generic)
}

/** Everything at or above this is a server/infrastructure failure, not a user error. */
private const val HTTP_SERVER_ERROR = 500
