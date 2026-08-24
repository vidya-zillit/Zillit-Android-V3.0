package com.zillit.zillitapp.feature.project.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.preferences.AppPreferences
import com.zillit.zillitapp.core.bootstrap.ProjectBootstrapper
import com.zillit.zillitapp.feature.project.data.ProjectRepository
import com.zillit.zillitapp.feature.project.domain.Project
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProjectListUiState(
    val projects: List<Project> = emptyList(),
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    /** First load, nothing to show yet. */
    val isLoading: Boolean = true,
    /** A refresh over content that is already on screen. */
    val isRefreshing: Boolean = false,
    val error: ApiError? = null,
    val hasLoadedOnce: Boolean = false,
    /** Total before filtering — lets the UI tell "no projects" from "no search results". */
    val totalProjectCount: Int = 0,
) {
    /** The account genuinely has no projects. */
    val isEmpty: Boolean
        get() = !isLoading && hasLoadedOnce && totalProjectCount == 0

    /** Has projects, but none match the current search. */
    val isEmptySearchResult: Boolean
        get() = !isLoading && totalProjectCount > 0 && projects.isEmpty()

    /** A failure with nothing cached to fall back on. */
    val isBlockingError: Boolean get() = error != null && totalProjectCount == 0
}

/**
 * State holder for the project list.
 *
 * Rotation safety comes from the architecture, not `configChanges`: the ViewModel outlives
 * the Activity, so [uiState] survives a rotate without re-fetching or losing scroll.
 * `WhileSubscribed(5_000)` keeps the Realm Flow subscribed across the gap while the
 * Activity is recreated, so there is not even a flash of the loading state.
 *
 * The search query lives in [SavedStateHandle] rather than a plain field, so a half-typed
 * search also survives **process death** — being killed in the background while checking
 * another app is the common case, and losing the query there is just as annoying as
 * losing it on rotate.
 */
@HiltViewModel
class ProjectListViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val savedStateHandle: SavedStateHandle,
    private val bootstrapper: ProjectBootstrapper,
    private val preferences: AppPreferences,
) : ViewModel() {

    private val searchQuery: StateFlow<String> =
        savedStateHandle.getStateFlow(KEY_SEARCH_QUERY, "")

    private val isSearchActive: StateFlow<Boolean> =
        savedStateHandle.getStateFlow(KEY_SEARCH_ACTIVE, false)

    private val transientState = MutableStateFlow(TransientState())

    val uiState: StateFlow<ProjectListUiState> = combine(
        repository.observeProjects(),
        searchQuery,
        isSearchActive,
        transientState,
    ) { projects, query, searchActive, transient ->
        ProjectListUiState(
            projects = projects.filterBy(query),
            searchQuery = query,
            isSearchActive = searchActive,
            isLoading = transient.isLoading && !transient.hasLoadedOnce,
            isRefreshing = transient.isRefreshing,
            error = transient.error,
            hasLoadedOnce = transient.hasLoadedOnce,
            totalProjectCount = projects.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ProjectListUiState(),
    )

    init {
        load(isUserInitiated = false)
    }

    fun refresh() = load(isUserInitiated = true)

    fun onSearchQueryChange(query: String) {
        savedStateHandle[KEY_SEARCH_QUERY] = query
    }

    fun onSearchActiveChange(active: Boolean) {
        savedStateHandle[KEY_SEARCH_ACTIVE] = active
        // Closing search clears the filter; leaving a stale query applied behind a
        // collapsed search bar looks like projects have gone missing.
        if (!active) savedStateHandle[KEY_SEARCH_QUERY] = ""
    }

    /**
     * Toggles favourite. The repository writes locally first, so the star and the
     * re-sort happen immediately and roll back if the call fails.
     */
    fun onFavouriteToggle(project: Project) {
        viewModelScope.launch {
            when (val result = repository.setFavourite(project.id, project.userId, !project.isFavourite)) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> transientState.update { it.copy(error = result.error) }
            }
        }
    }

    /**
     * Opens a project.
     *
     * Establishing the session is the important part, not the navigation: until the
     * active project is set, every project-scoped request stamps an empty `project_id`
     * and the socket's [com.zillit.zillitapp.core.socket.EventGate] discards every
     * inbound event, so badges cannot update.
     *
     * @return true once the session is established and the caller may navigate.
     */
    fun onProjectSelected(project: Project): Boolean {
        // One call does the whole open: sets the session, brings up the socket, and
        // refreshes users / units / tools / profile into Realm. Keeping that sequence in
        // ProjectBootstrapper rather than here is what lets deep links, notification taps
        // and the "last project" restore path open a project the same way this screen does.
        bootstrapper.open(
            projectId = project.id,
            userId = project.userId,
            enterpriseClientId = project.enterpriseClientId,
        )

        // No badge sync call here on purpose. BadgeSyncCoordinator already watches
        // activeProject and reconciles when it changes, so triggering it here as well sent
        // the notification-sync request twice on every project open.
        viewModelScope.launch { preferences.setLastProjectId(project.id) }
        return true
    }

    private fun load(isUserInitiated: Boolean) {
        viewModelScope.launch {
            val hadCache = repository.hasCachedProjects()

            transientState.update {
                it.copy(
                    // A blocking spinner over existing content is worse than refreshing
                    // quietly underneath it.
                    isLoading = !hadCache,
                    isRefreshing = isUserInitiated || hadCache,
                    error = null,
                )
            }

            when (val result = repository.refresh()) {
                is ApiResult.Success -> transientState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = null,
                        hasLoadedOnce = true,
                    )
                }

                is ApiResult.Failure -> transientState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = result.error,
                        // A failed refresh over good cache still counts as loaded, so the
                        // UI shows the cached list plus an error, not an empty state.
                        hasLoadedOnce = it.hasLoadedOnce || hadCache,
                    )
                }
            }
        }
    }

    fun clearError() = transientState.update { it.copy(error = null) }

    /**
     * Matches name, project code and company.
     *
     * Type/sub-type are deliberately not searched: they are stored as label *keys*
     * (`entertainment_industry_label`), so matching them here would search identifiers
     * rather than the words the user can actually see on screen.
     */
    private fun List<Project>.filterBy(query: String): List<Project> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return this
        return filter { project ->
            project.name.contains(trimmed, ignoreCase = true) ||
                project.code?.contains(trimmed, ignoreCase = true) == true ||
                project.companyName?.contains(trimmed, ignoreCase = true) == true
        }
    }

    private data class TransientState(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val error: ApiError? = null,
        val hasLoadedOnce: Boolean = false,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val KEY_SEARCH_QUERY = "search_query"
        const val KEY_SEARCH_ACTIVE = "search_active"
    }
}
