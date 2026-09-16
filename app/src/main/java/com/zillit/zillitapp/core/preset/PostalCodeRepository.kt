package com.zillit.zillitapp.core.preset

import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ZillitApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An address the server derived from a postcode.
 *
 * A postcode is not unique to one place in most countries, so the lookup returns a list and
 * the user picks — which is why this is a model rather than a single string.
 */
data class PostalArea(
    val area: String,
    val city: String,
    val state: String,
    val postalCode: String,
) {
    /** "Camden Town, London, Greater London" — what the picker shows. */
    val display: String
        get() = listOf(area, city, state).filter { it.isNotBlank() }.joinToString(", ")
}

/**
 * Postcode → address.
 *
 * Used by any form that asks for a postcode and a city, so the user types the postcode once
 * instead of three fields. **Requires a country**: the same postcode exists in dozens of
 * them, so a lookup without one is meaningless — v2 fires it anyway, off a `lateinit`
 * country id, and crashes when the field is blurred before a country is chosen.
 */
@Singleton
class PostalCodeRepository @Inject constructor(
    private val api: ZillitApi,
) {

    suspend fun lookUp(countryCode: String, postalCode: String): ApiResult<List<PostalArea>> {
        if (countryCode.isBlank() || postalCode.isBlank()) {
            return ApiResult.Success(emptyList())
        }

        return when (val result = api.get<PostalCodeResponse>(
            ApiEndpoints.Preset.postalCode(countryCode.trim(), postalCode.trim()),
        )) {
            is ApiResult.Failure -> result

            is ApiResult.Success -> ApiResult.Success(
                result.data.data.orEmpty().map {
                    PostalArea(
                        area = it.area.orEmpty(),
                        city = it.city.orEmpty(),
                        state = it.state.orEmpty(),
                        postalCode = it.postalCode.orEmpty(),
                    )
                },
            )
        }
    }
}

@Serializable
private data class PostalCodeResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: List<PostalAreaDto>? = null,
)

@Serializable
private data class PostalAreaDto(
    @SerialName("area") val area: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("state") val state: String? = null,
    @SerialName("postalCode") val postalCode: String? = null,
    @SerialName("countryCode") val countryCode: String? = null,
)
