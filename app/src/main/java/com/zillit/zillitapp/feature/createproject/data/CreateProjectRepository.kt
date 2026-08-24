package com.zillit.zillitapp.feature.createproject.data

import com.zillit.zillitapp.core.common.capitalizeFirst
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.preset.CountryCode
import com.zillit.zillitapp.core.preset.CountryCodeRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ProjectTypeResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("data") val data: List<ProjectTypeDto> = emptyList(),
)

@Serializable
data class ProjectTypeDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("project_type") val projectType: String? = null,
    @SerialName("sub_types") val subTypes: List<String> = emptyList(),
)

/**
 * `POST device-otp` and `device-otp/verify` — v2's `ValidateEmailRequest`.
 *
 * **`language` is required.** Omitting it fails with `project_language_validation`, which
 * reads like an unrelated error: the OTP endpoint validates the project language because
 * the code email is sent in that language.
 */
@Serializable
data class ValidateEmailRequest(
    @SerialName("email") val email: String,
    @SerialName("language") val language: String? = null,
    @SerialName("otp") val otp: String? = null,
)

/**
 * `POST project`. Field names match v2's `CreateProjectRequest` exactly.
 *
 * `confirm_code` is the OTP the user just verified — the backend re-checks it at create
 * time, so the verify step alone is not sufficient and the code must be carried forward.
 */
@Serializable
data class CreateProjectRequest(
    @SerialName("project_name") val projectName: String,
    @SerialName("project_type_id") val projectTypeId: String? = null,
    @SerialName("project_type") val projectType: String? = null,
    @SerialName("project_sub_type") val projectSubType: String? = null,
    @SerialName("email") val email: String,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("confirm_code") val confirmCode: String? = null,
    // Both of these are REQUIRED by the backend and both must always serialize.
    //
    // Declared non-null with no default on purpose: `encodeDefaults = false` omits any
    // property equal to its declared default, so `= DEFAULT_REGION` silently dropped
    // project_region from the JSON — the identical trap that hid project_language.
    // The backend then rejected the call with a Mongoose validation error.
    @SerialName("number_of_users") val numberOfUsers: Int,
    @SerialName("project_region") val projectRegion: Int,
    // Default deliberately null, NOT "en". `encodeDefaults = false` omits any property
    // equal to its declared default, so a default of "en" meant selecting English
    // dropped `project_language` from the JSON entirely and the server rejected it with
    // project_language_validation.
    @SerialName("project_language") val projectLanguage: String? = null,
    @SerialName("project_language_description") val projectLanguageDescription: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("phone") val phone: String? = null,
) {
    companion object {
        /** What the web client sends on this endpoint. */
        const val DEFAULT_REGION = 0
        const val DEFAULT_USER_COUNT = 0
    }
}

/**
 * Response to `device-otp/verify`.
 *
 * The important part: `data.confirm_code` is a **server-issued token**, not the OTP the
 * user typed. Create must send *this*, not the original code — the OTP is consumed and
 * time-limited, so replaying it fails with `project_otp_invalid` or `project_otp_expired`.
 */
@Serializable
data class VerifyOtpResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: ConfirmCodeData? = null,
)

@Serializable
data class ConfirmCodeData(
    @SerialName("confirm_code") val confirmCode: String? = null,
)

/** `POST project` response — carries the created project, including its share code. */
@Serializable
data class CreateProjectResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: CreatedProject? = null,
)

@Serializable
data class CreatedProject(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("project_code") val projectCode: String? = null,
)

@Serializable
data class ActionResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("message") val message: String? = null,
)

@Serializable
data class LanguageResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("data") val data: List<LanguageDto> = emptyList(),
)

@Serializable
data class LanguageDto(
    @SerialName("name") val name: String? = null,
    @SerialName("language_code") val languageCode: String? = null,
)

/** A selectable language. `code` fills `project_language`, `name` the description. */
data class Language(val code: String, val name: String)

/** Domain model for a selectable project type. */
data class ProjectType(
    val id: String,
    /** Label KEY — resolve through the dictionary before display. */
    val nameKey: String,
    /** Sub-type label keys. */
    val subTypeKeys: List<String>,
)

/**
 * The create-project flow's API surface.
 *
 * Every call uses [ModuleData.DEFAULT]: the whole flow runs *before* a project exists, so
 * there is no project or user to put in the header. Using a project-scoped variant here
 * stamps an empty `project_id` and the server rejects it — the same mistake that broke
 * the favourite toggle.
 */
@Singleton
class CreateProjectRepository @Inject constructor(
    private val api: ZillitApi,
    private val countryCodes: CountryCodeRepository,
) {

    suspend fun getProjectTypes(): ApiResult<List<ProjectType>> =
        when (
            val result = api.get<ProjectTypeResponse>(
                url = ApiEndpoints.Preset.PROJECT_TYPES,
                module = ModuleData.DEFAULT,
            )
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(
                result.data.data.mapNotNull { dto ->
                    val id = dto.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ProjectType(
                        id = id,
                        nameKey = dto.projectType.orEmpty(),
                        subTypeKeys = dto.subTypes,
                    )
                },
            )
        }

    suspend fun getLanguages(): ApiResult<List<Language>> =
        when (
            val result = api.get<LanguageResponse>(
                url = ApiEndpoints.Preset.LANGUAGES,
                module = ModuleData.DEFAULT,
            )
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(
                result.data.data.mapNotNull { dto ->
                    val code = dto.languageCode?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    // Capitalised at the source, so the picker and the selected value agree —
                    // fixing only one leaves them inconsistent.
                    Language(code = code, name = dto.name.orEmpty().ifBlank { code }.capitalizeFirst())
                },
            )
        }

    /** The shared country list — held in core so every phone field reads the same one. */
    suspend fun getCountryCodes(): ApiResult<List<CountryCode>> = countryCodes.load()

    suspend fun sendOtp(email: String, languageCode: String): ApiResult<Unit> =
        when (
            val result = api.post<ValidateEmailRequest, ActionResponse>(
                url = ApiEndpoints.Device.OTP,
                body = ValidateEmailRequest(email = email.trim(), language = languageCode),
                module = ModuleData.DEFAULT,
            )
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(Unit)
        }

    /** @return the server-issued `confirm_code` to carry into the create request. */
    suspend fun verifyOtp(email: String, otp: String, languageCode: String): ApiResult<String> =
        when (
            val result = api.post<ValidateEmailRequest, VerifyOtpResponse>(
                url = ApiEndpoints.Device.VERIFY_OTP,
                body = ValidateEmailRequest(
                    email = email.trim(),
                    language = languageCode,
                    otp = otp.trim(),
                ),
                module = ModuleData.DEFAULT,
            )
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(result.data.data?.confirmCode.orEmpty())
        }

    suspend fun createProject(request: CreateProjectRequest): ApiResult<CreatedProject> =
        when (
            val result = api.post<CreateProjectRequest, CreateProjectResponse>(
                url = ApiEndpoints.Project.CREATE,
                body = request,
                module = ModuleData.DEFAULT,
            )
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(result.data.data ?: CreatedProject())
        }
}
