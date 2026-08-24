package com.zillit.zillitapp.core.common

/**
 * Public web pages the app links out to.
 *
 * Carried over from v2's `Constants` verbatim — these are the legal pages users accept at
 * sign-up, so the URLs must match what the client approved.
 */
object ZillitUrls {
    private const val CORPORATE = "https://corporate.zillit.com/"

    const val TERMS_OF_SERVICE =
        CORPORATE + "terms-conditions-for-zillit-application-and-web-platform"

    const val PRIVACY_POLICY =
        CORPORATE + "privacy-policy-for-zillit-application-and-web-platform"
}
