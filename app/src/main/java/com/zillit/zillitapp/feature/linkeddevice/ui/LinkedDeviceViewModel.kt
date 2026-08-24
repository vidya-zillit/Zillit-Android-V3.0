package com.zillit.zillitapp.feature.linkeddevice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.feature.linkeddevice.data.LinkedDeviceRepository
import com.zillit.zillitapp.feature.linkeddevice.domain.LinkedDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LinkedDeviceUiState(
    val devices: List<LinkedDevice> = emptyList(),
    val isLoading: Boolean = true,
    val error: ApiError? = null,
    /** The device the confirm-logout dialog is asking about, if any. */
    val pendingLogout: LinkedDevice? = null,
    /** Set while an unlink or link call is in flight. */
    val isMutating: Boolean = false,
    /** One-shot: a device was just linked successfully. */
    val linkSucceeded: Boolean = false,
) {
    val isEmpty: Boolean get() = !isLoading && devices.isEmpty() && error == null
}

@HiltViewModel
class LinkedDeviceViewModel @Inject constructor(
    private val repository: LinkedDeviceRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LinkedDeviceUiState())
    val uiState: StateFlow<LinkedDeviceUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.devices.isEmpty(), error = null) }

            when (val result = repository.getLinkedDevices()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(devices = result.data, isLoading = false, error = null)
                }

                is ApiResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = result.error)
                }
            }
        }
    }

    /**
     * Tapping a row asks first rather than logging out immediately — v2 shows a confirm
     * dialog, and logging a colleague's live web session out by mis-tap is not recoverable
     * from the app.
     */
    fun onDeviceClick(device: LinkedDevice) {
        _uiState.update { it.copy(pendingLogout = device) }
    }

    fun dismissLogoutPrompt() {
        _uiState.update { it.copy(pendingLogout = null) }
    }

    fun confirmLogout() {
        val device = _uiState.value.pendingLogout ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingLogout = null, isMutating = true) }

            when (val result = repository.unlink(device.id)) {
                is ApiResult.Success -> {
                    // Drop it locally straight away so the row disappears, then refetch
                    // to pick up anything else that changed server-side.
                    _uiState.update { state ->
                        state.copy(
                            devices = state.devices.filterNot { it.id == device.id },
                            isMutating = false,
                        )
                    }
                    refresh()
                }

                is ApiResult.Failure -> _uiState.update {
                    it.copy(isMutating = false, error = result.error)
                }
            }
        }
    }

    /** Called with the payload from the scanner. */
    fun onQrCodeScanned(code: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true, error = null) }

            when (val result = repository.linkByQrCode(code)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isMutating = false, linkSucceeded = true) }
                    refresh()
                }

                is ApiResult.Failure -> _uiState.update {
                    it.copy(isMutating = false, error = result.error)
                }
            }
        }
    }

    fun consumeLinkSuccess() = _uiState.update { it.copy(linkSucceeded = false) }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
