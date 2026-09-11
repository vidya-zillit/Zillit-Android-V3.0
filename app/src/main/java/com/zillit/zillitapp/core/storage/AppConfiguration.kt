package com.zillit.zillitapp.core.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything `GET api/v2/configuration` hands the app.
 *
 * One endpoint carries **all** the third-party credentials — S3, Google Maps, Places, the
 * translation token and Box — so no key is compiled into the APK and any of them can be
 * rotated server-side. Every credential in here arrives **AES-encrypted** with the same
 * key/IV as the request headers, and is decrypted once on the way in.
 *
 * v2 fans these out into five separate `SharedPref` setters at the call site, so what the
 * config actually provides is only discoverable by reading the callback. Kept as one
 * object here, stored whole.
 */
@Serializable
data class AppConfiguration(
    val awsAccessKey: String = "",
    val awsSecretKey: String = "",
    val googleMapKey: String = "",
    val googlePlacesSecret: String = "",
    val chatGptTranslationToken: String = "",
    /** Box app credentials. The per-project *token* comes from a separate call. */
    val boxClientId: String = "",
    val boxClientSecret: String = "",
    /** Not a credential — where to send a user on a forced-update prompt. */
    val appDownloadUrl: String = "",
    /**
     * Whether this environment wants bearer tokens instead of the `moduledata` header.
     *
     * Off by default, and the server accepts both during the migration, so the token code
     * ships dormant and activates when the flag flips.
     */
    val tokenAuthEnabled: Boolean = false,
) {
    val hasAwsKeys: Boolean get() = awsAccessKey.isNotBlank() && awsSecretKey.isNotBlank()
    val hasBoxApp: Boolean get() = boxClientId.isNotBlank() && boxClientSecret.isNotBlank()
}

@Serializable
internal data class ConfigurationResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: ConfigurationDto? = null,
)

/** Every value here except `app_download_url` is AES-encrypted hex. */
@Serializable
internal data class ConfigurationDto(
    @SerialName("aws_access_key") val awsAccessKey: String? = null,
    @SerialName("aws_secret_key") val awsSecretKey: String? = null,
    @SerialName("google_map_key") val googleMapKey: String? = null,
    @SerialName("places_secret") val placesSecret: String? = null,
    @SerialName("chat_gpt_translation_token") val chatGptTranslationToken: String? = null,
    @SerialName("box_client_id") val boxClientId: String? = null,
    @SerialName("box_client_secret") val boxClientSecret: String? = null,
    @SerialName("app_download_url") val appDownloadUrl: String? = null,
    /** Not encrypted — a plain feature flag. */
    @SerialName("token_auth_enabled") val tokenAuthEnabled: Boolean? = null,
)
