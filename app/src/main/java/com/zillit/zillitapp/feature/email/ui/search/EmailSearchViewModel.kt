package com.zillit.zillitapp.feature.email.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.email.data.EmailBadges
import com.zillit.zillitapp.feature.email.data.EmailRepository
import com.zillit.zillitapp.feature.email.data.SearchableField
import com.zillit.zillitapp.feature.email.data.toDomain
import com.zillit.zillitapp.feature.email.domain.EmailAddress
import com.zillit.zillitapp.feature.email.domain.EmailFolders
import com.zillit.zillitapp.feature.email.ui.list.EmailReadFilter
import com.zillit.zillitapp.feature.email.ui.list.toRowState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Local search.
 *
 * There is **no search endpoint** — this is a query over what has already synced. So a
 * folder the user has never opened cannot be searched, and Drafts never can, because they
 * are API-only and never cached. Both facts are why the folder chips exclude Drafts and why
 * the empty state promises no more than "Search your emails".
 */
@HiltViewModel
class EmailSearchViewModel @Inject constructor(
    private val repository: EmailRepository,
    private val badges: EmailBadges,
    private val session: SessionStore,
    private val directory: ProjectDirectory,
    private val labels: com.zillit.zillitapp.feature.email.ui.EmailRowLabels,
) : ViewModel() {

    private val _state = MutableStateFlow(
        EmailSearchUiState(selectedFolders = setOf(EmailFolders.INBOX)),
    )

    /** Unread uids across the inbox — the only folder search defaults to. */
    private var unreadUids: Set<String> = emptySet()
    val state: StateFlow<EmailSearchUiState> = _state.asStateFlow()

    /** The debounce timer between keystrokes. */
    private var debounceJob: Job? = null

    /**
     * The live subscription to the current matches.
     *
     * Separate from [debounceJob] on purpose: the debounce coroutine *starts* the
     * subscription, so a single job would have it cancel the coroutine it was running in
     * and the search would never run at all.
     */
    private var searchJob: Job? = null

    private val projectId: String get() = session.activeProject.value?.projectId.orEmpty()

    init {
        viewModelScope.launch {
            // Every folder, not just the inbox: searching a folder of your own reported
            // everything in it as read, and the Unread filter returned nothing there.
            badges.observeAllUnreadUids(projectId).collect { unreadUids = it }
        }

        viewModelScope.launch {
            val folders = repository.observeFolders().first()
            _state.value = _state.value.copy(
                // Drafts cannot be searched at all, so offering the chip would be a lie.
                availableFolders = folders
                    .map { it.toDomain() }
                    .filterNot { it.folderName.equals(EmailFolders.DRAFTS, ignoreCase = true) },
            )
        }
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)

        debounceJob?.cancel()
        if (query.isBlank()) {
            searchJob?.cancel()
            _state.value = _state.value.copy(results = emptyList(), hasSearched = false)
            return
        }

        debounceJob = viewModelScope.launch {
            // Long enough that typing a word is one query rather than five.
            delay(DEBOUNCE_MS)
            run()
        }
    }

    fun toggleFolder(folderName: String) {
        val current = _state.value.selectedFolders
        // Never all off: an empty folder set can only return nothing, which reads as a
        // broken search rather than as a filter the user set.
        val next = if (folderName in current) {
            (current - folderName).ifEmpty { current }
        } else {
            current + folderName
        }

        _state.value = _state.value.copy(selectedFolders = next)
        rerun()
    }

    fun toggleField(field: EmailSearchField) {
        val current = _state.value.fields
        val next = if (field in current) (current - field).ifEmpty { current } else current + field

        _state.value = _state.value.copy(fields = next)
        rerun()
    }

    fun setReadFilter(filter: EmailReadFilter) {
        _state.value = _state.value.copy(readFilter = filter)
        rerun()
    }

    fun setHasAttachments(enabled: Boolean) {
        _state.value = _state.value.copy(hasAttachments = enabled)
        rerun()
    }

    fun toggleFilters() {
        _state.value = _state.value.copy(showFilters = !_state.value.showFilters)
    }

    private fun rerun() {
        if (_state.value.query.isNotBlank()) run()
    }

    /**
     * Subscribes to the matches.
     *
     * A subscription rather than a one-off read: mail that syncs while the results are on
     * screen belongs in them. The previous search is cancelled first, so changing a filter
     * replaces the results instead of leaving two queries writing to the same state.
     */
    private fun run() {
        searchJob?.cancel()
        _state.value = _state.value.copy(searching = true)

        val current = _state.value

        searchJob = viewModelScope.launch {
            repository.search(
                query = current.query,
                folders = current.selectedFolders,
                fields = current.fields.map { it.toSearchable() }.toSet(),
            ).collect { matches ->
                val rows = matches
                    .map { entity ->
                        val unread = if (entity.folderName in EmailFolders.READ_STATE_FROM_FLAG) {
                            !entity.read
                        } else {
                            entity.uid.toString() in unreadUids
                        }
                        entity.toDomain(unread = unread)
                    }
                    .filter { email ->
                        val matchesRead = when (current.readFilter) {
                            EmailReadFilter.ALL -> true
                            EmailReadFilter.READ -> !email.isUnread
                            EmailReadFilter.UNREAD -> email.isUnread
                        }
                        matchesRead && (!current.hasAttachments || email.hasAttachments)
                    }
                    .map { it.toRowState(labels.noSubject, labels.draft, resolveAvatar = ::avatarFor) }

                _state.value = _state.value.copy(
                    results = rows,
                    searching = false,
                    hasSearched = true,
                )
            }
        }
    }

    private fun avatarFor(address: EmailAddress): Pair<String?, String?> {
        val user = directory.findByEmail(address.address) ?: return null to null
        return user.profilePictureUrl to user.profileThumbnailKey
    }

    private fun EmailSearchField.toSearchable(): SearchableField = when (this) {
        EmailSearchField.SUBJECT -> SearchableField.SUBJECT
        EmailSearchField.FROM -> SearchableField.FROM
        EmailSearchField.TO -> SearchableField.TO
        EmailSearchField.CC -> SearchableField.CC
        EmailSearchField.BCC -> SearchableField.BCC
    }

    private companion object {
        const val DEBOUNCE_MS = 400L

    }
}
