package com.zillit.zillitapp.feature.email.data

import com.zillit.zillitapp.core.directory.MailboxInfo
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.session.CurrentUserStore
import com.zillit.zillitapp.feature.email.domain.MailboxScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which mailbox the email module is currently reading.
 *
 * Every request, every Realm row and every badge lookup is scoped by this, so it is a
 * singleton rather than screen state — the drawer, the list, the rules editor and the
 * credentials sheet must never disagree about which mailbox they are looking at.
 *
 * **Shared mode is conditional on two things**, and both can change mid-session: the
 * project has to have an Accounts mailbox provisioned, and this user has to still be in the
 * department that may open it. [effectiveScope] is the one property anything should read;
 * asking for shared while not entitled silently gives personal, because the alternative is
 * a 403 that the app's global handler turns into "you were thrown out of this project".
 */
@Singleton
class EmailMailboxContext @Inject constructor(
    private val currentUser: CurrentUserStore,
) {

    private val _selected = MutableStateFlow(MailboxScope.PERSONAL)

    /** What the user asked for. Not necessarily what they get — see [effectiveScope]. */
    val selected: StateFlow<MailboxScope> = _selected.asStateFlow()

    private val _accountsMailbox = MutableStateFlow<String?>(null)

    /** The shared mailbox's address, or null when the project has none. */
    val accountsMailbox: StateFlow<String?> = _accountsMailbox.asStateFlow()

    private val _accountsMailboxInfo = MutableStateFlow<MailboxInfo?>(null)

    /** The shared mailbox's server settings, for the credentials sheet. */
    val accountsMailboxInfo: StateFlow<MailboxInfo?> = _accountsMailboxInfo.asStateFlow()

    private val _accountsBccPresets = MutableStateFlow<List<String>>(emptyList())

    /** The shared mailbox's BCC presets. Empty when there is no shared mailbox. */
    val accountsBccPresets: StateFlow<List<String>> = _accountsBccPresets.asStateFlow()

    private val _entitled = MutableStateFlow(false)

    /** Whether this user may open the shared mailbox at all. */
    val entitled: StateFlow<Boolean> = _entitled.asStateFlow()

    /** The scope that actually applies. */
    val effectiveScope: MailboxScope
        get() = if (_selected.value == MailboxScope.SHARED && canUseShared) {
            MailboxScope.SHARED
        } else {
            MailboxScope.PERSONAL
        }

    private val canUseShared: Boolean
        get() = _entitled.value && !_accountsMailbox.value.isNullOrBlank()

    /** The address mail is currently being read as. */
    val activeAddress: String?
        get() = if (effectiveScope == MailboxScope.SHARED) {
            _accountsMailbox.value
        } else {
            personalAddress
        }

    val personalAddress: String?
        get() = currentUser.current?.mailboxAddress

    /** The display name to send as — "Sam Whitfield" or "Accounts". */
    val activeName: String?
        get() = if (effectiveScope == MailboxScope.SHARED) {
            ACCOUNTS_NAME
        } else {
            currentUser.current?.fullName
        }

    /** What goes in a `From:` header. */
    val fromHeader: String
        get() {
            val address = activeAddress.orEmpty()
            val name = activeName.orEmpty()
            return if (name.isBlank()) address else "$name <$address>"
        }

    /**
     * Whether requests should carry `use_project_account_mailbox=true`.
     *
     * The flag is **never** sent as `false` — absent means personal — so this is the only
     * thing a call site needs to ask.
     */
    val useAccountsParam: Boolean get() = effectiveScope == MailboxScope.SHARED

    fun select(scope: MailboxScope) {
        _selected.value = scope
    }

    /**
     * Updates what the project offers.
     *
     * Called when the project opens and whenever its details are refreshed. An entitlement
     * that has gone away drops the selection back to personal immediately rather than
     * waiting for the next request to fail.
     */
    fun update(
        accountsMailbox: String?,
        entitled: Boolean,
        accountsBccPresets: List<String> = emptyList(),
        accountsMailboxInfo: MailboxInfo? = null,
    ) {
        _accountsMailbox.value = accountsMailbox?.takeIf { it.isNotBlank() }
        _accountsMailboxInfo.value = accountsMailboxInfo
        _entitled.value = entitled
        _accountsBccPresets.value = accountsBccPresets

        if (_selected.value == MailboxScope.SHARED && !canUseShared) {
            _selected.value = MailboxScope.PERSONAL
        }
    }

    /**
     * Drops out of the shared mailbox after the server refused it.
     *
     * The entitlement can be revoked mid-session, and every subsequent request then fails.
     * Without this the user is stranded on an empty shared mailbox until the project is
     * reopened — and the app's global handler reads the 403 as "thrown out of the project".
     *
     * Returns true when it actually switched, so the caller can reload.
     */
    fun dropSharedOnRefusal(error: ApiError?): Boolean {
        if (_selected.value != MailboxScope.SHARED || !isAccountsRefusal(error)) return false

        _entitled.value = false
        _selected.value = MailboxScope.PERSONAL
        return true
    }

    /** Whether [error] is the backend saying this mailbox is not available to this user. */
    fun isAccountsRefusal(error: ApiError?): Boolean {
        val http = error as? ApiError.Http ?: return false
        return http.status == HTTP_FORBIDDEN ||
            http.message == ERR_ACCESS_DENIED ||
            http.message == ERR_NOT_CONFIGURED
    }

    fun reset() {
        _selected.value = MailboxScope.PERSONAL
        _accountsMailbox.value = null
        _entitled.value = false
        _accountsBccPresets.value = emptyList()
        _accountsMailboxInfo.value = null
    }

    private companion object {
        const val ERR_ACCESS_DENIED = "email_accounts_mailbox_access_denied"
        const val ERR_NOT_CONFIGURED = "email_accounts_mailbox_not_configured"
        const val HTTP_FORBIDDEN = 403

        /** v2 labels the shared mailbox this; it is not the user's name. */
        const val ACCOUNTS_NAME = "Accounts"
    }
}
