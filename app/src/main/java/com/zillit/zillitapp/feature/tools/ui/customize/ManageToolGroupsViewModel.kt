package com.zillit.zillitapp.feature.tools.ui.customize

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.labels.LabelRepository

import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.tools.data.ToolGroup
import com.zillit.zillitapp.feature.tools.data.ToolsRealtime
import com.zillit.zillitapp.feature.tools.data.ToolsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A tool, and the section it currently sits in. */
@Immutable
data class GroupedTool(
    val identifier: String,
    val title: String,
    val groupIdentifier: String,
    val groupName: String,
)

@Immutable
data class ManageToolGroupsUiState(
    val groups: List<ToolGroup> = emptyList(),
    val tools: List<GroupedTool> = emptyList(),
    val busy: Boolean = false,
)

/**
 * Creating, renaming and deleting the Tools tab's sections, and moving tools between them.
 *
 * Admin only, and project-wide — unlike the reorder sheet, which is one person's own view.
 * Every change here lands on everyone's Tools tab, which is why each one is a request rather
 * than something batched behind an Update button.
 */
@HiltViewModel
class ManageToolGroupsViewModel @Inject constructor(
    private val repository: ToolsRepository,
    private val directory: ProjectDirectory,
    private val realtime: ToolsRealtime,
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

    private val projectId: String get() = session.activeProject.value?.projectId.orEmpty()

    private val _busy = MutableStateFlow(false)
    private val _error = MutableStateFlow<ApiError?>(null)
    val error: StateFlow<ApiError?> = _error.asStateFlow()

    val state: StateFlow<ManageToolGroupsUiState> = combine(
        repository.groups,
        directory.observeTools(projectId),
        labels.dictionaries,
        _busy,
    ) { groups, tools, dictionary, busy ->
        ManageToolGroupsUiState(
            groups = groups,
            tools = tools
                .filter { it.isTool }
                .map { tool ->
                    val group = groups.firstOrNull { it.identifier == tool.groupIdentifier }
                    GroupedTool(
                        identifier = tool.identifier,
                        title = dictionary.resolveOr(tool.toolName, tool.identifier),
                        groupIdentifier = tool.groupIdentifier,
                        groupName = group?.name.orEmpty(),
                    )
                }
                .sortedBy { it.title.lowercase() },
            busy = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), ManageToolGroupsUiState())

    init {
        realtime.start()
        // Another admin editing groups in a second session has to land here too.
        realtime.groupsChanged.onEach { repository.refreshGroups() }.launchIn(viewModelScope)
        realtime.toolsChanged
            .onEach { directory.refreshTools(projectId, isPendingUser = false) }
            .launchIn(viewModelScope)

        viewModelScope.launch { repository.refreshGroups() }
    }

    fun createGroup(name: String) = run { repository.createGroup(name.trim()) }

    fun renameGroup(group: ToolGroup, name: String) = run {
        repository.renameGroup(group.groupId.orEmpty(), name.trim())
    }

    fun deleteGroup(group: ToolGroup) = run {
        repository.deleteGroup(group.groupId.orEmpty())
    }

    /** Blank [groupIdentifier] takes the tool out of every section. */
    fun move(tool: GroupedTool, groupIdentifier: String) = run {
        repository.moveToGroup(tool.identifier, groupIdentifier).also {
            // The membership lives on the tool, not on the group, so the tool list is what
            // has to be re-read — refreshing groups would show the same rows unchanged.
            if (it is ApiResult.Success) {
                directory.refreshTools(projectId, isPendingUser = false)
            }
        }
    }

    fun consumeError() {
        _error.value = null
    }

    private fun run(block: suspend () -> ApiResult<*>) {
        viewModelScope.launch {
            _busy.value = true
            val result = block()
            if (result is ApiResult.Failure) _error.value = result.error
            _busy.value = false
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
