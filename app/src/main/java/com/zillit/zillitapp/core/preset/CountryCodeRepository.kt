package com.zillit.zillitapp.core.preset

import com.zillit.zillitapp.core.common.capitalizeFirst
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** A country dial code. `dialCode` is what fills a `country_code` field. */
data class CountryCode(val code: String, val dialCode: String, val name: String)

@Serializable
internal data class IsdCodeResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("data") val data: List<IsdCodeDto> = emptyList(),
)

@Serializable
internal data class IsdCodeDto(
    @SerialName("name") val name: String? = null,
    @SerialName("dial_code") val dialCode: String? = null,
    @SerialName("code") val code: String? = null,
)

/**
 * The country list, fetched once.
 *
 * Anywhere a phone number is entered needs it — creating a project, adding an external
 * guest, editing a profile — so it lives in `core` and is held after the first call rather
 * than re-fetched per screen. The list does not change while the app is running.
 */
@Singleton
class CountryCodeRepository @Inject constructor(
    private val api: ZillitApi,
) {

    @Volatile
    private var cached: List<CountryCode>? = null

    /** The cached list, if it has already been loaded. */
    val current: List<CountryCode> get() = cached.orEmpty()

    suspend fun load(): ApiResult<List<CountryCode>> {
        cached?.let { return ApiResult.Success(it) }

        return when (
            val result = api.get<IsdCodeResponse>(
                url = ApiEndpoints.Preset.ISD_CODES,
                module = ModuleData.DEFAULT,
            )
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val codes = result.data.data.mapNotNull { dto ->
                    val dial = dto.dialCode?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    CountryCode(
                        code = dto.code.orEmpty().ifBlank { dial },
                        dialCode = dial,
                        // Capitalised at the source, so a picker and the value it sets agree —
                        // fixing only one leaves them inconsistent.
                        name = dto.name.orEmpty().capitalizeFirst(),
                    )
                }
                cached = codes
                ApiResult.Success(codes)
            }
        }
    }
}
