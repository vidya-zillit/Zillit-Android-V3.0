package com.zillit.zillitapp.feature.email.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.session.CurrentUserStore
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.email.data.ConversationViewPreference
import com.zillit.zillitapp.feature.email.data.EmailMailboxContext
import com.zillit.zillitapp.feature.email.data.EmailRealtime
import com.zillit.zillitapp.feature.email.data.EmailSettingsRepository
import com.zillit.zillitapp.feature.email.domain.EmailGroup
import com.zillit.zillitapp.feature.email.domain.EmailSignature
import com.zillit.zillitapp.feature.email.domain.ForwardingSetting
import com.zillit.zillitapp.feature.email.domain.MailCredentials
import com.zillit.zillitapp.feature.email.domain.isPlausibleEmail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Email settings, and the three editors reachable from it.
 *
 * One view model rather than five because they share a mailbox, a refresh trigger and an
 * error channel, and because the screens are small — signatures are capped at one, groups
 * and contacts are short lists. Splitting them would mean five subscriptions to the same
 * socket events.
 */
@HiltViewModel
class EmailSettingsViewModel @Inject constructor(
    private val repository: EmailSettingsRepository,
    private val mailbox: EmailMailboxContext,
    private val realtime: EmailRealtime,
    private val currentUser: CurrentUserStore,
    private val directory: ProjectDirectory,
    private val session: SessionStore,
    private val conversationPreference: ConversationViewPreference,
) : ViewModel() {

    val conversationView: StateFlow<Boolean> = conversationPreference.enabled

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _error = MutableStateFlow<ApiError?>(null)
    val error: StateFlow<ApiError?> = _error.asStateFlow()

    /** Only an admin may manage groups; the row is absent otherwise, not disabled. */
    val isAdmin: Boolean get() = currentUser.isAdmin

    private val _signatures = MutableStateFlow<List<EmailSignature>>(emptyList())
    val signatures: StateFlow<List<EmailSignature>> = _signatures.asStateFlow()

    private val _groups = MutableStateFlow<List<EmailGroup>>(emptyList())
    val groups: StateFlow<List<EmailGroup>> = _groups.asStateFlow()

    private val _forwarding = MutableStateFlow(ForwardingSetting())
    val forwarding: StateFlow<ForwardingSetting> = _forwarding.asStateFlow()

    private val _forwardingError = MutableStateFlow<String?>(null)
    val forwardingError: StateFlow<String?> = _forwardingError.asStateFlow()

    private val _credentials = MutableStateFlow(MailCredentials())
    val credentials: StateFlow<MailCredentials> = _credentials.asStateFlow()

    private val _revealing = MutableStateFlow(false)
    val revealing: StateFlow<Boolean> = _revealing.asStateFlow()

    private val _revealError = MutableStateFlow<String?>(null)
    val revealError: StateFlow<String?> = _revealError.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val revealedPassword: StateFlow<String?> = repository.revealedPassword

    /**
     * Whether the password may be changed here.
     *
     * Off for the shared mailbox: it is not any one person's password, and v2 hides the
     * control there for the same reason.
     */
    val canChangePassword: Boolean get() = !mailbox.useAccountsParam

    init {
        // The three lists all change from the web as well, and each has its own event.
        realtime.signaturesChanged.onEach { loadSignatures() }.launchIn(viewModelScope)
        realtime.groupsChanged.onEach { loadGroups() }.launchIn(viewModelScope)
    }

    fun setConversationView(enabled: Boolean) {
        // Flipped locally first: the switch has to answer the tap, and a revert on failure
        // is clearer than a control that does nothing for a second. The preference is
        // shared, so the mail list regroups on the same flip.
        conversationPreference.set(enabled)

        viewModelScope.launch {
            _saving.value = true
            val result = repository.setConversationView(enabled)
            if (result is ApiResult.Failure) {
                conversationPreference.set(!enabled)
                _error.value = result.error
            }
            _saving.value = false
        }
    }

    // ── Signatures ───────────────────────────────────────────────────────────

    fun loadSignatures() {
        viewModelScope.launch {
            _loading.value = true
            when (val result = repository.signatures()) {
                is ApiResult.Success -> _signatures.value = result.data
                is ApiResult.Failure -> _error.value = result.error
            }
            _loading.value = false
        }
    }

    fun saveSignature(id: String?, name: String, content: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _saving.value = true
            when (val result = repository.saveSignature(id, name, content)) {
                is ApiResult.Success -> {
                    loadSignatures()
                    onDone()
                }

                is ApiResult.Failure -> _error.value = result.error
            }
            _saving.value = false
        }
    }

    fun deleteSignature(id: String) {
        viewModelScope.launch {
            val result = repository.deleteSignature(id)
            if (result is ApiResult.Failure) _error.value = result.error else loadSignatures()
        }
    }

    // ── Groups ───────────────────────────────────────────────────────────────

    fun loadGroups() {
        viewModelScope.launch {
            _loading.value = true
            when (val result = repository.groups()) {
                is ApiResult.Success -> _groups.value = result.data
                is ApiResult.Failure -> _error.value = result.error
            }
            _loading.value = false
        }
    }

    fun saveGroup(id: String?, name: String, members: List<String>, onDone: () -> Unit) {
        viewModelScope.launch {
            _saving.value = true
            when (val result = repository.saveGroup(id, name, members)) {
                is ApiResult.Success -> {
                    loadGroups()
                    onDone()
                }

                is ApiResult.Failure -> _error.value = result.error
            }
            _saving.value = false
        }
    }

    fun deleteGroup(id: String) {
        viewModelScope.launch {
            val result = repository.deleteGroup(id)
            if (result is ApiResult.Failure) _error.value = result.error else loadGroups()
        }
    }

    private val _availableMembers = MutableStateFlow<List<GroupMember>>(emptyList())

    /**
     * The members a group may contain.
     *
     * Project users only, which is the service's rule rather than a UI decision: a group's
     * members are resolved against the project, so a free-form address would be dropped —
     * and in v2 a member who has since left is silently dropped on the next save.
     */
    val availableMembers: StateFlow<List<GroupMember>> = _availableMembers.asStateFlow()

    fun loadAvailableMembers() {
        viewModelScope.launch {
            val projectId = session.activeProject.value?.projectId.orEmpty()
            _availableMembers.value = directory.observeUsers(projectId).first()
                .filter { user -> user.enabled && !user.email.isNullOrBlank() }
                .map { user ->
                    GroupMember(
                        userId = user.userId,
                        name = user.fullName,
                        email = user.email.orEmpty(),
                        designation = user.designationName.orEmpty(),
                        pictureKey = user.profilePictureUrl,
                        thumbnailKey = user.profileThumbnailKey,
                    )
                }
        }
    }

    // ── Forwarding ───────────────────────────────────────────────────────────

    fun loadForwarding() {
        viewModelScope.launch { _forwarding.value = repository.forwarding() }
    }

    fun saveForwarding(address: String, onDone: () -> Unit) {
        if (!address.trim().isPlausibleEmail()) {
            _forwardingError.value = ERROR_INVALID_EMAIL
            return
        }

        viewModelScope.launch {
            _saving.value = true
            _forwardingError.value = null

            when (val result = repository.saveForwarding(address.trim())) {
                is ApiResult.Success -> {
                    loadForwarding()
                    onDone()
                }

                is ApiResult.Failure -> _forwardingError.value = result.error.message
            }
            _saving.value = false
        }
    }

    fun removeForwarding(onDone: () -> Unit) {
        viewModelScope.launch {
            _saving.value = true
            when (val result = repository.deleteForwarding()) {
                is ApiResult.Success -> {
                    _forwarding.value = ForwardingSetting()
                    onDone()
                }

                is ApiResult.Failure -> _forwardingError.value = result.error.message
            }
            _saving.value = false
        }
    }

    // ── BCC presets ──────────────────────────────────────────────────────────

    private val _bccPresets = MutableStateFlow<List<String>>(emptyList())
    val bccPresets: StateFlow<List<String>> = _bccPresets.asStateFlow()

    private val _bccDraft = MutableStateFlow("")
    val bccDraft: StateFlow<String> = _bccDraft.asStateFlow()

    private val _bccError = MutableStateFlow<String?>(null)
    val bccError: StateFlow<String?> = _bccError.asStateFlow()

    /**
     * Loads the presets that belong to the **active** mailbox.
     *
     * Read from the cached profile rather than fetched: they arrive with it, and there is no
     * endpoint that returns them on their own.
     */
    fun loadBccPresets() {
        _bccPresets.value = if (mailbox.useAccountsParam) {
            mailbox.accountsBccPresets.value
        } else {
            currentUser.current?.bccPresets.orEmpty()
        }
    }

    fun setBccDraft(value: String) {
        _bccDraft.value = value
        _bccError.value = null
    }

    fun addBccPreset() {
        val address = _bccDraft.value.trim()

        when {
            address.isEmpty() -> {
                _bccError.value = ERROR_BCC_REQUIRED
                return
            }

            !address.isPlausibleEmail() -> {
                _bccError.value = ERROR_INVALID_EMAIL
                return
            }

            // Case-insensitive: an address is the same address whatever its capitals, and
            // adding it twice would blind-copy the recipient twice.
            _bccPresets.value.any { it.equals(address, ignoreCase = true) } -> {
                _bccError.value = ERROR_BCC_DUPLICATE
                return
            }
        }

        save(_bccPresets.value + address) { _bccDraft.value = "" }
    }

    fun removeBccPreset(address: String) {
        save(_bccPresets.value.filterNot { it.equals(address, ignoreCase = true) })
    }

    private fun save(next: List<String>, onDone: () -> Unit = {}) {
        val previous = _bccPresets.value
        // Optimistic, reverted on failure: the list is the point of the screen and it
        // should answer the tap.
        _bccPresets.value = next

        viewModelScope.launch {
            _saving.value = true
            when (val result = repository.setBccPresets(next)) {
                is ApiResult.Success -> onDone()
                is ApiResult.Failure -> {
                    _bccPresets.value = previous
                    _error.value = result.error
                }
            }
            _saving.value = false
        }
    }

    // ── Credentials ──────────────────────────────────────────────────────────

    /**
     * What an external client needs, from the profile — no request, nothing sensitive.
     *
     * All six non-secret fields, for whichever mailbox is active. Filling in only the
     * address and username left the sheet showing empty hosts and a port of "0" until the
     * user tapped the eye, which made a working mailbox look misconfigured.
     */
    fun loadCredentials() {
        val info = if (mailbox.useAccountsParam) {
            mailbox.accountsMailboxInfo.value
        } else {
            currentUser.current?.mailbox
        }

        _credentials.value = MailCredentials(
            emailAddress = mailbox.activeAddress.orEmpty(),
            username = info?.smtpUserName.orEmpty().ifBlank { mailbox.activeAddress.orEmpty() },
            smtpHost = info?.smtpHost.orEmpty(),
            smtpPort = info?.smtpPort ?: 0,
            imapHost = info?.imapHost.orEmpty(),
            imapPort = info?.imapPort ?: 0,
        )
    }

    /**
     * Shows or hides the password.
     *
     * Hiding **discards** the plaintext rather than masking it, so showing it again is
     * another audited fetch instead of a replay of a value this process has been holding.
     */
    fun togglePassword() {
        if (repository.revealedPassword.value != null) {
            repository.hidePassword()
            return
        }

        viewModelScope.launch {
            _revealing.value = true
            _revealError.value = null

            when (val result = repository.revealPassword()) {
                // A 200 carrying no password is indistinguishable from "this mailbox has
                // none". Showing it as a successful reveal tells the user their password is
                // on screen when the row is blank, so it is treated as the failure it is.
                is ApiResult.Success ->
                    if (repository.revealedPassword.value.isNullOrBlank()) {
                        repository.hidePassword()
                        _revealError.value = ERROR_PASSWORD_UNAVAILABLE
                    } else {
                        // Only the password came back; the rest was already loaded from the
                        // profile and re-assigning would blank the hosts on a partial reply.
                        _credentials.value = _credentials.value.copy(
                            emailAddress = result.data.emailAddress.ifBlank {
                                _credentials.value.emailAddress
                            },
                        )
                    }

                is ApiResult.Failure -> _revealError.value = result.error.message
            }
            _revealing.value = false
        }
    }

    /**
     * The plaintext, for the clipboard — null when it is not on screen.
     *
     * Reading it does not fetch: copying a password the user has not chosen to reveal would
     * put it on the clipboard without anything on screen saying so.
     */
    fun passwordForClipboard(): String? = repository.revealedPassword.value

    fun updatePassword(password: String, onDone: () -> Unit) {
        viewModelScope.launch {
            when (val result = repository.updatePassword(password)) {
                is ApiResult.Success -> onDone()
                is ApiResult.Failure -> _error.value = result.error
            }
        }
    }

    /** Called when the credentials sheet closes, and again when the screen stops. */
    fun forgetPassword() {
        repository.hidePassword()
    }

    fun consumeError() {
        _error.value = null
        _forwardingError.value = null
        _revealError.value = null
    }

    override fun onCleared() {
        forgetPassword()
        super.onCleared()
    }

    private companion object {
        const val ERROR_INVALID_EMAIL = "valid_email"

        /** A reveal that came back empty — the mailbox has no password to show. */
        const val ERROR_PASSWORD_UNAVAILABLE = "password_reveal_unavailable"
        const val ERROR_BCC_REQUIRED = "email_bcc_required"
        const val ERROR_BCC_DUPLICATE = "email_bcc_duplicate"
    }
}
