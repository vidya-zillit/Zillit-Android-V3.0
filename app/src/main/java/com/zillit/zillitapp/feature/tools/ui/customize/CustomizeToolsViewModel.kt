package com.zillit.zillitapp.feature.tools.ui.customize

import androidx.compose.runtime.Immutable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.labels.LabelRepository
import com.zillit.zillitapp.feature.tools.data.EnableToolItem
import com.zillit.zillitapp.feature.tools.data.ToolsRepository
import com.zillit.zillitapp.feature.tools.registry.ToolRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One switchable tool. */
@Immutable
data class CustomizableTool(
    val identifier: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val enabled: Boolean,
    /** Whether it was on when the screen opened. Turning one of these off has to ask. */
    val wasEnabled: Boolean,
)

@Immutable
data class CustomizeToolsUiState(
    val tools: List<CustomizableTool> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
)

/**
 * Turning the project's tools on and off. Admin only.
 *
 * The list here is **not** the Tools tab's list. It comes from the admin endpoint, it is
 * finer grained — Budget appears as Department, Full and Builder — and it necessarily
 * includes what is switched off, which by definition has no tile.
 *
 * ### Nothing is saved until Update
 * Ticks are local until the button is pressed, which is what makes turning several tools off
 * one decision rather than several. It is also why [CustomizableTool.wasEnabled] is carried:
 * the warning is owed for a tool that is actually being switched off, not for one the admin
 * ticked on and changed their mind about in the same visit.
 */
@HiltViewModel
class CustomizeToolsViewModel @Inject constructor(
    private val repository: ToolsRepository,
    private val registry: ToolRegistry,
    private val directory: ProjectDirectory,
    private val labels: LabelRepository,
    private val session: SessionStore,
    currentUser: com.zillit.zillitapp.core.session.CurrentUserStore,
) : ViewModel() {

    /**
     * Whether this user may be here at all.
     *
     * Checked on the screen as well as on the entry point. v2 gates only the entry point, so
     * its screen is fully usable by anyone who reaches it another way — and the save it
     * offers is a project-wide change.
     */
    val isAdmin: StateFlow<Boolean> = currentUser.profile
        .map { it?.isAdmin == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _state = MutableStateFlow(CustomizeToolsUiState())
    val state: StateFlow<CustomizeToolsUiState> = _state.asStateFlow()

    private val _error = MutableStateFlow<ApiError?>(null)
    val error: StateFlow<ApiError?> = _error.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)

            when (val result = repository.adminTools()) {
                is ApiResult.Success -> {
                    val dictionary = labels.dictionaries.value
                    _state.value = _state.value.copy(
                        tools = result.data.mapNotNull { dto ->
                            val identifier = dto.identifier?.takeIf { it.isNotBlank() }
                                ?: return@mapNotNull null
                            val enabled = dto.enabled ?: false

                            CustomizableTool(
                                identifier = identifier,
                                title = dictionary.resolveOr(
                                    // `unit_name` is what this endpoint sends; the others
                                    // are the same shape served elsewhere.
                                    dto.unitName ?: dto.toolName ?: dto.name.orEmpty(),
                                    identifier,
                                ),
                                icon = registry[identifier]?.icon ?: FALLBACK_ICON,
                                enabled = enabled,
                                wasEnabled = enabled,
                            )
                            // Alphabetical, as v2 sorts it: this list is long and flat, so
                            // there is no order to it but the name.
                        }.sortedBy { it.title.lowercase() },
                        loading = false,
                    )
                }

                is ApiResult.Failure -> {
                    _error.value = result.error
                    _state.value = _state.value.copy(loading = false)
                }
            }
        }
    }

    fun toggle(identifier: String) {
        _state.value = _state.value.copy(
            tools = _state.value.tools.map {
                if (it.identifier == identifier) it.copy(enabled = !it.enabled) else it
            },
        )
    }

    /** True when this tap is switching off a tool that was on when the screen opened. */
    fun needsWarning(identifier: String): Boolean =
        _state.value.tools.firstOrNull { it.identifier == identifier }
            ?.let { it.wasEnabled && it.enabled }
            ?: false

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)

            val payload = _state.value.tools.map { EnableToolItem(it.identifier, it.enabled) }

            when (val result = repository.setEnabled(payload)) {
                is ApiResult.Success -> {
                    // The tab's list is a different endpoint, so it does not learn about
                    // this on its own. Refreshed before leaving, so the tiles are right the
                    // moment the user is back on the tab.
                    session.activeProject.value?.projectId?.let {
                        directory.refreshTools(it, isPendingUser = false)
                    }
                    _saved.value = true
                    onDone()
                }

                is ApiResult.Failure -> _error.value = result.error
            }

            _state.value = _state.value.copy(saving = false)
        }
    }

    fun consumeError() {
        _error.value = null
        _saved.value = false
    }

    private companion object {
        val FALLBACK_ICON = Icons.Outlined.Build
    }
}
