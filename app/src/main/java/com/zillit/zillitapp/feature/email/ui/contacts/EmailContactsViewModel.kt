package com.zillit.zillitapp.feature.email.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.feature.email.data.EmailRealtime
import com.zillit.zillitapp.feature.email.data.EmailSettingsRepository
import com.zillit.zillitapp.feature.email.domain.EmailContact
import com.zillit.zillitapp.feature.email.domain.isPlausibleEmail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Saved contacts.
 *
 * Server-side only — the device address book is never read, so the module needs no contacts
 * permission and nothing here leaves the project.
 */
@HiltViewModel
class EmailContactsViewModel @Inject constructor(
    private val repository: EmailSettingsRepository,
    private val realtime: EmailRealtime,
    private val countryCodes: com.zillit.zillitapp.core.preset.CountryCodeRepository,
    private val postalCodes: com.zillit.zillitapp.core.preset.PostalCodeRepository,
) : ViewModel() {

    private val all = MutableStateFlow<List<EmailContact>>(emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<ApiError?>(null)
    val error: StateFlow<ApiError?> = _error.asStateFlow()

    /** Filtered locally — the list is small, and there is no search endpoint for it. */
    val contacts: StateFlow<List<EmailContact>> = combine(all, _query) { list, term ->
        if (term.isBlank()) {
            list
        } else {
            val needle = term.trim().lowercase()
            list.filter {
                it.displayName.lowercase().contains(needle) ||
                    it.emailAddress.lowercase().contains(needle) ||
                    it.companyName.lowercase().contains(needle)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    init {
        load()
        // Its own event, not the signature one — v2 exposes this flow from the wrong
        // backing field, so no contact event ever arrives there.
        realtime.contactsChanged.onEach { load() }.launchIn(viewModelScope)
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            when (val result = repository.contacts()) {
                is ApiResult.Success -> all.value = result.data
                is ApiResult.Failure -> _error.value = result.error
            }
            _loading.value = false
        }
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    /**
     * Saves a contact.
     *
     * The address is the one required field, and it is validated properly — v2 checks only
     * that it contains `@`, so a contact can be saved that can never be mailed.
     */
    fun save(contact: EmailContact, onValidationError: (String) -> Unit, onDone: () -> Unit) {
        if (!contact.emailAddress.trim().isPlausibleEmail()) {
            onValidationError(ERROR_INVALID_EMAIL)
            return
        }
        // A phone number without its country code cannot be dialled from another country,
        // which is the whole reason the field exists on a production.
        if (contact.phoneNumber.isNotBlank() && contact.countryCode.isBlank()) {
            onValidationError(ERROR_COUNTRY_REQUIRED)
            return
        }
        if (contact.countryCode.isNotBlank() && contact.phoneNumber.isBlank()) {
            onValidationError(ERROR_PHONE_REQUIRED)
            return
        }

        viewModelScope.launch {
            when (val result = repository.saveContact(contact)) {
                is ApiResult.Success -> {
                    load()
                    onDone()
                }

                is ApiResult.Failure -> _error.value = result.error
            }
        }
    }

    fun delete(contact: EmailContact) {
        viewModelScope.launch {
            val result = repository.deleteContact(contact.id)
            if (result is ApiResult.Failure) _error.value = result.error else load()
        }
    }

    // ── Country and postcode ─────────────────────────────────────────────────

    private val _countries = MutableStateFlow(countryCodes.current)
    val countries: StateFlow<List<com.zillit.zillitapp.core.preset.CountryCode>> =
        _countries.asStateFlow()

    /** Addresses a postcode resolved to; the user picks one. Empty closes the picker. */
    private val _postalAreas =
        MutableStateFlow<List<com.zillit.zillitapp.core.preset.PostalArea>>(emptyList())
    val postalAreas: StateFlow<List<com.zillit.zillitapp.core.preset.PostalArea>> =
        _postalAreas.asStateFlow()

    fun loadCountries() {
        viewModelScope.launch {
            // The cached list renders immediately; the fetch only refreshes it.
            (countryCodes.load() as? ApiResult.Success)?.let { _countries.value = it.data }
        }
    }

    /**
     * Resolves a postcode.
     *
     * Gated on a country having been chosen, because the same postcode exists in dozens of
     * countries and the endpoint takes one — v2 fires this off a `lateinit` field and throws
     * when the postcode field is blurred first.
     */
    fun lookUpPostalCode(countryCode: String, postalCode: String) {
        if (countryCode.isBlank() || postalCode.isBlank()) return

        viewModelScope.launch {
            val result = postalCodes.lookUp(countryCode, postalCode)
            // An empty or failed lookup is silent: the user can still type the address, and
            // an error about a convenience they did not ask for is noise.
            _postalAreas.value = (result as? ApiResult.Success)?.data.orEmpty()
        }
    }

    fun dismissPostalAreas() {
        _postalAreas.value = emptyList()
    }

    fun find(contactId: String): EmailContact? = all.value.firstOrNull { it.id == contactId }

    /** Whether an address is already saved — what the "Add to Contacts" sheet asks. */
    fun isSaved(address: String): Boolean =
        all.value.any { it.emailAddress.equals(address, ignoreCase = true) }

    fun consumeError() {
        _error.value = null
    }

    private companion object {
        const val STOP_MS = 5_000L
        const val ERROR_INVALID_EMAIL = "p_enter_valid_email"
        const val ERROR_COUNTRY_REQUIRED = "p_choose_country"
        const val ERROR_PHONE_REQUIRED = "p_enter_valid_phone_number"
    }
}
