package com.zillit.zillitapp.feature.shareproject.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.feature.shareproject.data.ShareProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShareProjectUiState(
    val recoveryEmail: String = "",
    val isRecoverySheetOpen: Boolean = false,
    val isProceedPromptOpen: Boolean = false,
    val isSubmitting: Boolean = false,
    val recoveryEmailSaved: Boolean = false,
    val error: ApiError? = null,
) {
    val canSaveRecoveryEmail: Boolean
        get() = recoveryEmail.isValidEmail() && !isSubmitting
}

@HiltViewModel
class ShareProjectViewModel @Inject constructor(
    private val repository: ShareProjectRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShareProjectUiState())
    val uiState: StateFlow<ShareProjectUiState> = _uiState.asStateFlow()

    fun openRecoverySheet() = _uiState.update { it.copy(isRecoverySheetOpen = true, error = null) }
    fun dismissRecoverySheet() = _uiState.update { it.copy(isRecoverySheetOpen = false, error = null) }

    fun onRecoveryEmailChange(value: String) = _uiState.update { it.copy(recoveryEmail = value) }

    /**
     * Both leaving the screen and the Proceed button raise the same prompt.
     *
     * v2 does this deliberately: the project code is shown once, and letting someone
     * swipe past it without acknowledgement means losing the only copy they had.
     */
    fun requestProceed() = _uiState.update { it.copy(isProceedPromptOpen = true) }
    fun dismissProceedPrompt() = _uiState.update { it.copy(isProceedPromptOpen = false) }

    fun saveRecoveryEmail() {
        val state = _uiState.value
        if (!state.canSaveRecoveryEmail) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }

            when (val result = repository.addRecoveryEmail(state.recoveryEmail)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        isRecoverySheetOpen = false,
                        recoveryEmailSaved = true,
                    )
                }

                is ApiResult.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, error = result.error)
                }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}

/** Permissive on purpose — the server is the authority; this catches the obvious typo. */
private fun String.isValidEmail(): Boolean {
    val trimmed = trim()
    return trimmed.length >= 5 &&
        trimmed.contains('@') &&
        trimmed.substringAfterLast('@').contains('.')
}
