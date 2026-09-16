package com.zillit.zillitapp.feature.createproject.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.bootstrap.MailboxProvisioner
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.feature.createproject.data.CreateProjectRepository
import com.zillit.zillitapp.feature.createproject.data.CreateProjectRequest
import com.zillit.zillitapp.core.preset.CountryCode
import com.zillit.zillitapp.feature.createproject.data.Language
import com.zillit.zillitapp.feature.createproject.data.ProjectType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateProjectUiState(
    /** True while the OTP dialog is open over the form. */
    val isVerifyingEmail: Boolean = false,
    /** Set once the code is accepted; Submit stays disabled until then. */
    val isEmailVerified: Boolean = false,
    /** The address the code was sent to, so editing the email invalidates the check. */
    val verifiedEmail: String? = null,
    /**
     * Server-issued token from `device-otp/verify` — NOT the OTP the user typed.
     * This is what `confirm_code` carries on create.
     */
    val confirmCode: String? = null,

    val projectName: String = "",
    val selectedType: ProjectType? = null,
    val selectedSubTypeKey: String? = null,
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val otp: String = "",
    val selectedLanguage: Language? = null,
    val selectedCountry: CountryCode? = null,
    val phone: String = "",
    val termsAccepted: Boolean = false,
    /** Seconds until Resend re-enables; 0 means it is available. */
    val resendCooldownSeconds: Int = 0,

    val availableTypes: List<ProjectType> = emptyList(),
    val availableLanguages: List<Language> = emptyList(),
    val availableCountries: List<CountryCode> = emptyList(),
    val isLoadingTypes: Boolean = true,
    val isSubmitting: Boolean = false,
    val error: ApiError? = null,
    /** Set once the project is created; the screen navigates away on it. */
    val isCreated: Boolean = false,
    /** Populated on success so the share screen can present the code. */
    val createdProjectName: String? = null,
    val createdProjectCode: String? = null,
) {
    /** Sub-types belong to the chosen type, so the picker is empty until one is picked. */
    val availableSubTypeKeys: List<String> get() = selectedType?.subTypeKeys.orEmpty()

    /**
     * Mirrors v2's required set: both names, type, language, project name and email.
     * Terms must be accepted — v2 gates Submit on the checkbox, and shipping without it
     * would let a user create a project having agreed to nothing.
     */
    /** Verify is offered as soon as the address looks plausible. */
    /**
     * Language is required here, not just at Submit: the OTP endpoint validates it and
     * rejects the request with `project_language_validation` otherwise.
     */
    val canVerifyEmail: Boolean
        get() = email.isValidEmail() && selectedLanguage != null && !isSubmitting && !isEmailVerified

    /**
     * Submit requires a *verified* email, not merely a well-formed one — v2 will not let
     * the form through until the inline Verify has succeeded.
     */
    /**
     * Field rules ported from v2's `StartProjectActivity.validate()`.
     *
     * Enforced client-side because the server reports them one at a time as opaque label
     * keys — a user fixing four fields would otherwise make four round trips.
     */
    val firstNameError: FieldError?
        get() = when {
            firstName.isBlank() -> FieldError.Required
            firstName.trim().length !in NAME_MIN..NAME_MAX -> FieldError.NameLength
            else -> null
        }

    val lastNameError: FieldError?
        get() = when {
            lastName.isBlank() -> FieldError.Required
            lastName.trim().length !in NAME_MIN..NAME_MAX -> FieldError.NameLength
            else -> null
        }

    val projectNameError: FieldError?
        get() = when {
            projectName.isBlank() -> FieldError.Required
            projectName.trim().length !in NAME_MIN..PROJECT_NAME_MAX -> FieldError.ProjectNameLength
            else -> null
        }

    /**
     * Phone and country are all-or-nothing, matching v2: a number without a dial code and
     * a dial code without a number are both errors rather than being quietly dropped.
     */
    val phoneError: FieldError?
        get() = when {
            phone.isBlank() && selectedCountry == null -> null
            phone.isNotBlank() && selectedCountry == null -> FieldError.CountryRequired
            selectedCountry != null && phone.isBlank() -> FieldError.PhoneRequired
            phone.length !in PHONE_MIN..PHONE_MAX -> FieldError.PhoneLength
            else -> null
        }

    val canSubmitDetails: Boolean
        get() = firstNameError == null &&
            lastNameError == null &&
            projectNameError == null &&
            phoneError == null &&
            selectedType != null &&
            selectedLanguage != null &&
            isEmailVerified &&
            termsAccepted &&
            !isSubmitting

    /** The backend requires exactly six digits, so Verify stays disabled below that. */
    val canConfirmOtp: Boolean get() = otp.trim().length == OTP_LENGTH && !isSubmitting

    val canResend: Boolean get() = resendCooldownSeconds == 0 && !isSubmitting
}

/**
 * Drives the create-project flow.
 *
 * Order matters and is enforced here rather than by the UI: the OTP is requested only
 * after the details validate, and the project is created only after the OTP verifies —
 * the backend re-checks `confirm_code` at create time, so the verified code has to be
 * carried forward rather than discarded once the verify call returns.
 *
 * Field values live in [SavedStateHandle], so a half-filled form survives rotation *and*
 * process death. Losing a form to a backgrounded app is the common case, and this one
 * ends with an email round-trip the user would have to repeat.
 */
@HiltViewModel
class CreateProjectViewModel @Inject constructor(
    private val repository: CreateProjectRepository,
    private val mailboxProvisioner: MailboxProvisioner,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private var resendJob: Job? = null

    private val _uiState = MutableStateFlow(CreateProjectUiState())
    val uiState: StateFlow<CreateProjectUiState> = _uiState.asStateFlow()

    init {
        restoreForm()
        loadProjectTypes()
    }

    /**
     * Loads the three picker sources together.
     *
     * Concurrently, not in sequence: they are independent, and chaining them would make
     * the form wait for the slowest. A failure in any one surfaces but does not block the
     * others — a missing country list should not stop a user picking a project type.
     */
    /** Retries the preset lists after a failure. */
    fun retryPresets() = loadProjectTypes()

    private fun loadProjectTypes() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTypes = true, error = null) }

            val types = async { repository.getProjectTypes() }
            val languages = async { repository.getLanguages() }
            val countries = async { repository.getCountryCodes() }

            val typeResult = types.await()
            val languageResult = languages.await()
            val countryResult = countries.await()

            _uiState.update { state ->
                state.copy(
                    isLoadingTypes = false,
                    availableTypes = (typeResult as? ApiResult.Success)?.data.orEmpty(),
                    availableLanguages = (languageResult as? ApiResult.Success)?.data.orEmpty(),
                    availableCountries = (countryResult as? ApiResult.Success)?.data.orEmpty(),
                    error = (typeResult as? ApiResult.Failure)?.error,
                )
            }
        }
    }

    fun onProjectNameChange(value: String) = updateField(KEY_NAME, value) {
        copy(projectName = value)
    }

    fun onFirstNameChange(value: String) = updateField(KEY_FIRST_NAME, value) {
        copy(firstName = value)
    }

    fun onLastNameChange(value: String) = updateField(KEY_LAST_NAME, value) {
        copy(lastName = value)
    }

    /**
     * Editing the address after verifying invalidates the check.
     *
     * Otherwise a user could verify one address, change it, and submit an unverified one —
     * the create call would carry a `confirm_code` issued for a different email.
     */
    fun onEmailChange(value: String) = updateField(KEY_EMAIL, value) {
        val stillVerified = isEmailVerified && value.trim() == verifiedEmail
        copy(
            email = value,
            isEmailVerified = stillVerified,
            verifiedEmail = if (stillVerified) verifiedEmail else null,
            // The token is bound to the verified address, so it dies with it.
            confirmCode = if (stillVerified) confirmCode else null,
        )
    }

    fun onOtpChange(value: String) {
        // Digits only, capped — the field is a code, not free text.
        val digits = value.filter(Char::isDigit).take(OTP_LENGTH)
        _uiState.update { it.copy(otp = digits) }
    }

    fun onTypeSelected(type: ProjectType) {
        savedStateHandle[KEY_TYPE_ID] = type.id
        // Clearing the sub-type is essential: sub-types belong to a type, so keeping the
        // old one would submit a pairing the backend does not recognise.
        savedStateHandle[KEY_SUB_TYPE] = null
        _uiState.update { it.copy(selectedType = type, selectedSubTypeKey = null) }
    }

    fun onLanguageSelected(language: Language) {
        savedStateHandle[KEY_LANGUAGE] = language.code
        _uiState.update { it.copy(selectedLanguage = language) }
    }

    fun onCountrySelected(country: CountryCode) {
        savedStateHandle[KEY_COUNTRY] = country.dialCode
        _uiState.update { it.copy(selectedCountry = country) }
    }

    fun onPhoneChange(value: String) {
        val digits = value.filter(Char::isDigit).take(PHONE_MAX_LENGTH)
        savedStateHandle[KEY_PHONE] = digits
        _uiState.update { it.copy(phone = digits) }
    }

    fun onTermsChange(accepted: Boolean) {
        _uiState.update { it.copy(termsAccepted = accepted) }
    }

    fun onSubTypeSelected(subTypeKey: String) {
        savedStateHandle[KEY_SUB_TYPE] = subTypeKey
        _uiState.update { it.copy(selectedSubTypeKey = subTypeKey) }
    }

    /** Inline Verify: sends the code and opens the dialog. */
    fun requestEmailVerification() {
        val state = _uiState.value
        if (!state.canVerifyEmail) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }

            when (val result = repository.sendOtp(state.email, state.selectedLanguage?.code.orEmpty())) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(isSubmitting = false, isVerifyingEmail = true, otp = "")
                    }
                    startResendCooldown()
                }

                is ApiResult.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, error = result.error)
                }
            }
        }
    }

    fun dismissVerification() {
        // The cooldown deliberately keeps running: reopening the sheet must not hand out
        // a fresh minute-free resend.
        _uiState.update { it.copy(isVerifyingEmail = false, otp = "", error = null) }
    }

    /**
     * Verifies the OTP and keeps the token the server returns.
     *
     * `device-otp/verify` responds with `data.confirm_code`, a server-issued token that
     * is what create actually validates. Sending the user's OTP to create instead fails
     * — `project_otp_invalid` once verify has consumed it, or `project_otp_expired` once
     * its short lifetime elapses. Both were symptoms of skipping this exchange.
     */
    fun confirmOtp() {
        val state = _uiState.value
        if (!state.canConfirmOtp) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }

            when (
                val result = repository.verifyOtp(
                    state.email,
                    state.otp,
                    state.selectedLanguage?.code.orEmpty(),
                )
            ) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        isVerifyingEmail = false,
                        isEmailVerified = true,
                        verifiedEmail = it.email.trim(),
                        confirmCode = result.data,
                    )
                }

                is ApiResult.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, error = result.error)
                }
            }
        }
    }

    fun resendOtp() {
        if (!_uiState.value.canResend) return
        val state = _uiState.value

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            val result = repository.sendOtp(state.email, state.selectedLanguage?.code.orEmpty())
            _uiState.update {
                it.copy(isSubmitting = false, error = (result as? ApiResult.Failure)?.error)
            }
            // Only restart the cooldown when a code was actually sent — a failed resend
            // should not lock the button for another minute.
            if (result is ApiResult.Success) startResendCooldown()
        }
    }

    /**
     * Counts down in the ViewModel, not the composable, so the timer survives rotation
     * and does not restart every time the sheet recomposes.
     */
    private fun startResendCooldown() {
        resendJob?.cancel()
        resendJob = viewModelScope.launch {
            for (remaining in RESEND_COOLDOWN_SECONDS downTo 1) {
                _uiState.update { it.copy(resendCooldownSeconds = remaining) }
                delay(1_000)
            }
            _uiState.update { it.copy(resendCooldownSeconds = 0) }
        }
    }

    /** Submit: creates the project using the already-verified code. */
    fun submit() {
        val state = _uiState.value
        if (!state.canSubmitDetails) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }

            val request = CreateProjectRequest(
                projectName = state.projectName.trim(),
                projectTypeId = state.selectedType?.id,
                projectType = state.selectedType?.nameKey,
                projectSubType = state.selectedSubTypeKey,
                email = state.email.trim(),
                firstName = state.firstName.trim(),
                lastName = state.lastName.trim(),
                // The backend re-checks the code at create time, so the verified OTP is
                // carried forward rather than discarded once verification succeeded.
                // The token `device-otp/verify` returned, NOT the OTP the user typed. The
                // server issues a fresh confirm_code on verification and only that is
                // accepted here; replaying the OTP fails with project_otp_invalid.
                confirmCode = state.confirmCode,
                projectLanguage = state.selectedLanguage?.code,
                projectLanguageDescription = state.selectedLanguage?.name,
                numberOfUsers = CreateProjectRequest.DEFAULT_USER_COUNT,
                projectRegion = CreateProjectRequest.DEFAULT_REGION,
                countryCode = state.selectedCountry?.dialCode,
                phone = state.phone.takeIf { it.isNotBlank() },
            )

            when (val created = repository.createProject(request)) {
                is ApiResult.Success -> {
                    // The membership exists but the mailbox does not — the backend only
                    // provisions it when asked, which v2 does right here. Not awaited: the
                    // share-code screen must not wait on it, and the bootstrap re-checks
                    // the profile when the project is opened.
                    val projectId = created.data.projectId.orEmpty()
                    val userId = created.data.userId.orEmpty()
                    if (projectId.isNotBlank() && userId.isNotBlank()) {
                        viewModelScope.launch { mailboxProvisioner.ensure(projectId, userId) }
                    }
                    clearForm()
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            isCreated = true,
                            createdProjectName = created.data.projectName ?: state.projectName.trim(),
                            createdProjectCode = created.data.projectCode.orEmpty(),
                        )
                    }
                }

                is ApiResult.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, error = created.error)
                }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private inline fun updateField(
        key: String,
        value: String,
        transform: CreateProjectUiState.() -> CreateProjectUiState,
    ) {
        savedStateHandle[key] = value
        _uiState.update { it.transform() }
    }

    private fun restoreForm() {
        _uiState.update {
            it.copy(
                projectName = savedStateHandle[KEY_NAME] ?: "",
                firstName = savedStateHandle[KEY_FIRST_NAME] ?: "",
                lastName = savedStateHandle[KEY_LAST_NAME] ?: "",
                email = savedStateHandle[KEY_EMAIL] ?: "",
                selectedSubTypeKey = savedStateHandle[KEY_SUB_TYPE],
                phone = savedStateHandle[KEY_PHONE] ?: "",
            )
        }
    }

    private fun clearForm() {
        listOf(
            KEY_NAME, KEY_FIRST_NAME, KEY_LAST_NAME, KEY_EMAIL, KEY_TYPE_ID, KEY_SUB_TYPE,
            KEY_LANGUAGE, KEY_COUNTRY, KEY_PHONE,
        )
            .forEach { savedStateHandle.remove<String>(it) }
    }

    private companion object {
        const val KEY_NAME = "cp_name"
        const val KEY_FIRST_NAME = "cp_first_name"
        const val KEY_LAST_NAME = "cp_last_name"
        const val KEY_EMAIL = "cp_email"
        const val KEY_TYPE_ID = "cp_type_id"
        const val KEY_SUB_TYPE = "cp_sub_type"
        const val KEY_LANGUAGE = "cp_language"
        const val KEY_COUNTRY = "cp_country"
        const val KEY_PHONE = "cp_phone"
    }
}

private const val PHONE_MAX_LENGTH = 15
/** Client-side field failures, mapped to copy at render time. */
enum class FieldError {
    Required,
    NameLength,
    ProjectNameLength,
    PhoneRequired,
    PhoneLength,
    CountryRequired,
}

private const val NAME_MIN = 3
private const val NAME_MAX = 15
private const val PROJECT_NAME_MAX = 25
private const val PHONE_MIN = 5
private const val PHONE_MAX = 20

private const val OTP_LENGTH = 6
private const val RESEND_COOLDOWN_SECONDS = 60


/**
 * Deliberately permissive.
 *
 * Client-side email validation that tries to be clever rejects valid addresses; the
 * server is the authority. This only catches the obvious typo before costing the user an
 * email round-trip.
 */
private fun String.isValidEmail(): Boolean {
    val trimmed = trim()
    return trimmed.length >= 5 && trimmed.contains('@') && trimmed.substringAfterLast('@').contains('.')
}
