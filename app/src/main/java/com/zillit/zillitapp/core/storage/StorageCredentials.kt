package com.zillit.zillitapp.core.storage

import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.network.ZillitCrypto
import com.zillit.zillitapp.core.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** Which backend a project's files live on. Mirrors v2's `storage_type`. */
enum class StorageBackend { S3, BOX }

/**
 * Where uploads go, and what signs them.
 *
 * Two sources feed this: `preset/suitable-region` decides the bucket and region for the
 * device's location, while `configuration` carries the account-level keys. They are kept
 * in one object because a caller only ever needs "can I upload right now?", which is both.
 */
@Serializable
data class StorageCredentials(
    val accessKey: String = "",
    val secretKey: String = "",
    val region: String = "",
    val uploadBucket: String = "",
    val downloadBucket: String = "",
    val boxToken: String = "",
    val boxFolderId: String = "",
) {
    /** Everything S3 needs. Checked before an upload rather than letting the SDK fail. */
    val isComplete: Boolean
        get() = accessKey.isNotBlank() && secretKey.isNotBlank() &&
            region.isNotBlank() && uploadBucket.isNotBlank()

    val isBoxComplete: Boolean
        get() = boxToken.isNotBlank() && boxFolderId.isNotBlank()

    /**
     * The bucket to read from, as a bare bucket name.
     *
     * The server sometimes returns `download_bucket` as a full CDN URL rather than a name,
     * and the S3 client needs the name. So a URL is reduced to its host's first label —
     * `https://zl-media.s3.amazonaws.com/` becomes `zl-media` — and anything unparseable
     * falls back to the upload bucket, which is always a plain name.
     */
    val effectiveDownloadBucket: String
        get() = downloadBucket
            .takeIf { it.isNotBlank() }
            ?.let { value ->
                if (value.startsWith("http", ignoreCase = true)) {
                    runCatching { URI(value).host?.substringBefore('.') }.getOrNull()
                } else {
                    value
                }
            }
            ?.takeIf { it.isNotBlank() }
            ?: uploadBucket
}

@Singleton
class StorageCredentialsStore @Inject constructor(
    private val api: ZillitApi,
    private val crypto: ZillitCrypto,
    private val preferences: AppPreferences,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _credentials = MutableStateFlow(StorageCredentials())
    val credentials: StateFlow<StorageCredentials> = _credentials.asStateFlow()

    private val _configuration = MutableStateFlow(AppConfiguration())

    /**
     * The full configuration payload — map, Places, translation and Box credentials as
     * well as S3. Exposed so the map and translation features read the same source rather
     * than each fetching `configuration` again.
     */
    val configuration: StateFlow<AppConfiguration> = _configuration.asStateFlow()

    val current: StorageCredentials get() = _credentials.value

    val currentConfiguration: AppConfiguration get() = _configuration.value

    /**
     * Loads the last known values from preferences.
     *
     * Called before the first frame so an upload started immediately after launch is not
     * blocked waiting on the network. A decode failure is ignored: stale-but-absent is
     * better than crashing on a payload whose shape changed between versions.
     */
    suspend fun restore() {
        preferences.getStorageCredentialsJson()?.let { stored ->
            runCatching { json.decodeFromString(StorageCredentials.serializer(), stored) }
                .getOrNull()
                ?.let { _credentials.value = it }
        }
        preferences.getAppConfigurationJson()?.let { stored ->
            runCatching { json.decodeFromString(AppConfiguration.serializer(), stored) }
                .getOrNull()
                ?.let { _configuration.value = it }
        }
    }

    /**
     * Re-reads both sources and persists the result.
     *
     * Region first, then configuration: the region call is the one an upload actually
     * blocks on, and running them in sequence keeps the log readable when either fails.
     */
    suspend fun refresh(): Boolean {
        val region = fetchRegion()
        val config = fetchConfig()
        persist()
        return region && config
    }

    /** Bucket and region for this device's location — v2's `preset/suitable-region`. */
    private suspend fun fetchRegion(): Boolean =
        when (val result = api.get<RegionResponse>(
            ApiEndpoints.Preset.SUITABLE_REGION,
            ModuleData.WITH_PROJECT_USER_ID,
        )) {
            is ApiResult.Failure -> {
                ZillitLog.w(TAG, "Region fetch failed: ${result.error.message}")
                false
            }

            is ApiResult.Success -> {
                val data = result.data.data
                _credentials.value = _credentials.value.copy(
                    region = data?.awsRegion ?: _credentials.value.region,
                    // Keeping the previous bucket on a null is deliberate: an empty bucket
                    // fails every upload, so a partial response must not clear a good one.
                    uploadBucket = data?.uploadBucket ?: _credentials.value.uploadBucket,
                    downloadBucket = data?.downloadBucket ?: _credentials.value.downloadBucket,
                )
                ZillitLog.d(TAG, "Region: ${data?.awsRegion} bucket=${data?.uploadBucket}")
                true
            }
        }

    /** Account keys. Every field arrives encrypted — see [decryptOrEmpty]. */
    private suspend fun fetchConfig(): Boolean =
        when (val result = api.get<ConfigurationResponse>(
            ApiEndpoints.Configuration.CONFIG,
            ModuleData.WITH_PROJECT_USER_ID,
        )) {
            is ApiResult.Failure -> {
                ZillitLog.w(TAG, "Configuration fetch failed: ${result.error.message}")
                false
            }

            is ApiResult.Success -> {
                val dto = result.data.data
                _configuration.value = AppConfiguration(
                    awsAccessKey = dto?.awsAccessKey.decryptOrEmpty(),
                    awsSecretKey = dto?.awsSecretKey.decryptOrEmpty(),
                    googleMapKey = dto?.googleMapKey.decryptOrEmpty(),
                    googlePlacesSecret = dto?.placesSecret.decryptOrEmpty(),
                    chatGptTranslationToken = dto?.chatGptTranslationToken.decryptOrEmpty(),
                    boxClientId = dto?.boxClientId.decryptOrEmpty(),
                    boxClientSecret = dto?.boxClientSecret.decryptOrEmpty(),
                    // Not encrypted — it is a plain public URL.
                    appDownloadUrl = dto?.appDownloadUrl.orEmpty(),
                    tokenAuthEnabled = dto?.tokenAuthEnabled == true,
                )
                _credentials.value = _credentials.value.copy(
                    accessKey = _configuration.value.awsAccessKey,
                    secretKey = _configuration.value.awsSecretKey,
                )
                true
            }
        }

    /**
     * Decrypts one field, or yields "" when it is absent or undecryptable.
     *
     * Empty rather than null so callers can treat "no key" and "bad key" the same way —
     * both mean the feature behind it is unavailable, and neither should throw at a call
     * site that is only trying to build a request.
     */
    private fun String?.decryptOrEmpty(): String =
        this?.takeIf { it.isNotBlank() }?.let { crypto.decrypt(it) }.orEmpty()

    /** Box token and folder, after the Box OAuth exchange. Nulls leave the existing value. */
    fun updateBox(token: String?, folderId: String?) {
        _credentials.value = _credentials.value.copy(
            boxToken = token ?: _credentials.value.boxToken,
            boxFolderId = folderId ?: _credentials.value.boxFolderId,
        )
    }

    private suspend fun persist() {
        preferences.setStorageCredentialsJson(
            json.encodeToString(StorageCredentials.serializer(), _credentials.value),
        )
        preferences.setAppConfigurationJson(
            json.encodeToString(AppConfiguration.serializer(), _configuration.value),
        )
    }

    private companion object {
        const val TAG = "StorageCredentials"
    }
}

@Serializable
private data class RegionResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("data") val data: RegionDto? = null,
)

@Serializable
private data class RegionDto(
    @SerialName("upload_bucket") val uploadBucket: String? = null,
    @SerialName("download_bucket") val downloadBucket: String? = null,
    @SerialName("aws_region") val awsRegion: String? = null,
    @SerialName("region_name") val regionName: String? = null,
    @SerialName("region_code") val regionCode: String? = null,
)
