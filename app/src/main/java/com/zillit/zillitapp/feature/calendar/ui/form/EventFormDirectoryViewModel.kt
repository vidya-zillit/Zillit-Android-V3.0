package com.zillit.zillitapp.feature.calendar.ui.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.directory.ExternalUser
import com.zillit.zillitapp.core.directory.ExternalUserRepository
import com.zillit.zillitapp.core.directory.ProjectDepartment
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.directory.ProjectUser
import com.zillit.zillitapp.core.directory.ProjectDesignation
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.preset.CountryCode
import com.zillit.zillitapp.core.preset.CountryCodeRepository
import com.zillit.zillitapp.core.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The people the form's two pickers can offer.
 *
 * Read from Realm so both pickers open with their lists already there rather than showing
 * a spinner in front of the control the user came for. Disabled people are dropped —
 * someone removed from the production cannot be invited to its events.
 */
@HiltViewModel
class EventFormDirectoryViewModel @Inject constructor(
    private val directory: ProjectDirectory,
    private val externalUsers: ExternalUserRepository,
    private val countryCodes: CountryCodeRepository,
    session: SessionStore,
) : ViewModel() {

    private val projectId: String? = session.activeProject.value?.projectId

    val currentUserId: String? = session.activeProject.value?.userId

    /** Whether this user may invite the whole project in one tap. */
    val isAdmin: Boolean = currentUserId?.let { directory.findUser(it)?.isAdmin } ?: false

    val projectUsers: StateFlow<List<ProjectUser>> = flowOrEmpty {
        directory.observeUsers(it).map { users -> users.filter { user -> user.enabled } }
    }

    val departments: StateFlow<List<ProjectDepartment>> = flowOrEmpty {
        directory.observeDepartments(it)
    }

    val external: StateFlow<List<ExternalUser>> = flowOrEmpty {
        externalUsers.observe(it)
    }

    private val _countries = MutableStateFlow(countryCodes.current)

    /** Dial codes for the guest form's phone field. */
    val countries: StateFlow<List<CountryCode>> = _countries

    init {
        // Pulled in the background so the guest picker is current without making the form
        // wait; whatever is already cached shows immediately either way.
        viewModelScope.launch { externalUsers.refresh() }
        viewModelScope.launch {
            (countryCodes.load() as? ApiResult.Success)?.let { _countries.value = it.data }
        }
    }

    /** The roles inside one department, for the guest form's designation picker. */
    fun designations(departmentId: String): List<ProjectDesignation> =
        projectId?.let { directory.designations(it, departmentId) }.orEmpty()

    /**
     * Saves a new external guest and returns them.
     *
     * Returns null on failure so the caller keeps the sheet open with what was typed,
     * rather than closing it and losing the entry.
     */
    suspend fun addExternalUser(user: ExternalUser): ExternalUser? =
        (externalUsers.add(user) as? ApiResult.Success)?.data

    private fun <T> flowOrEmpty(
        source: (String) -> kotlinx.coroutines.flow.Flow<List<T>>,
    ): StateFlow<List<T>> = projectId
        ?.let { source(it) }
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())
        ?: MutableStateFlow(emptyList())

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
