package com.zillit.zillitapp.feature.email.data

import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.feature.email.domain.EmailContact
import com.zillit.zillitapp.feature.email.domain.EmailGroup
import com.zillit.zillitapp.feature.email.domain.EmailSignature
import com.zillit.zillitapp.feature.email.domain.ForwardingSetting
import com.zillit.zillitapp.feature.email.domain.MailCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contacts, signatures, groups, forwarding and credentials.
 *
 * **None of it is cached**, which is v2's behaviour and the right one here: these are small
 * lists that change rarely and are edited from several places, so a cache would mostly be a
 * way to show somebody a signature they deleted on their laptop.
 *
 * The one piece of state this class does hold is the revealed password, and it holds it as
 * briefly as possible — see [revealPassword].
 */
@Singleton
class EmailSettingsRepository @Inject constructor(
    private val api: EmailApi,
) {

    // ── Contacts ─────────────────────────────────────────────────────────────

    suspend fun contacts(): ApiResult<List<EmailContact>> = when (val result = api.contacts()) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> ApiResult.Success(result.data.map { it.toDomain() })
    }

    suspend fun saveContact(contact: EmailContact): ApiResult<Unit> =
        if (contact.id.isBlank()) {
            api.createContact(contact.toDto()).map { }
        } else {
            api.updateContact(contact.id, contact.toDto()).map { }
        }

    suspend fun deleteContact(id: String): ApiResult<Unit> = api.deleteContact(id)

    // ── Signatures ───────────────────────────────────────────────────────────

    /**
     * The user's signatures.
     *
     * At most one exists — a server limit, which the UI expresses by hiding the add button
     * rather than by refusing a second.
     */
    suspend fun signatures(): ApiResult<List<EmailSignature>> =
        when (val result = api.signatures()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(
                result.data.filter { it.deleted == 0L }.map { it.toDomain() },
            )
        }

    suspend fun saveSignature(id: String?, name: String, content: String): ApiResult<Unit> =
        if (id.isNullOrBlank()) {
            api.createSignature(name, content).map { }
        } else {
            api.updateSignature(id, name, content).map { }
        }

    suspend fun deleteSignature(id: String): ApiResult<Unit> = api.deleteSignature(id)

    // ── Groups ───────────────────────────────────────────────────────────────

    suspend fun groups(): ApiResult<List<EmailGroup>> = when (val result = api.groups()) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> ApiResult.Success(
            result.data.filter { it.deleted == 0L }.map { it.toDomain() },
        )
    }

    suspend fun saveGroup(id: String?, name: String, members: List<String>): ApiResult<Unit> =
        if (id.isNullOrBlank()) {
            api.createGroup(name, members).map { }
        } else {
            api.updateGroup(id, name, members).map { }
        }

    suspend fun deleteGroup(id: String): ApiResult<Unit> = api.deleteGroup(id)

    // ── Forwarding ───────────────────────────────────────────────────────────

    /**
     * The forwarding setting.
     *
     * A failure here is reported as "not configured" rather than as an error: having no
     * forwarding set up is the normal state, and the service answers that case with a
     * failure envelope rather than an empty success.
     */
    suspend fun forwarding(): ForwardingSetting =
        (api.forwarding() as? ApiResult.Success)?.data?.toDomain() ?: ForwardingSetting()

    suspend fun saveForwarding(address: String): ApiResult<Unit> =
        api.saveForwarding(address).map { }

    suspend fun deleteForwarding(): ApiResult<Unit> = api.deleteForwarding()

    // ── Credentials ──────────────────────────────────────────────────────────

    private val _revealedPassword = MutableStateFlow<String?>(null)

    /** The plaintext, while it is on screen. Null whenever it is not. */
    val revealedPassword: StateFlow<String?> = _revealedPassword.asStateFlow()

    private var lastRevealAt = 0L

    /**
     * Fetches the plaintext mailbox password.
     *
     * Every call is **audit-logged server-side**, so it happens on an explicit tap and never
     * on screen load. The local throttle stops a double-tap becoming two audit entries.
     */
    suspend fun revealPassword(): ApiResult<MailCredentials> {
        // A second tap while the first request is still out would be a second audited
        // reveal for one user action. The throttle alone does not stop it: a slow request
        // outlives its window.
        if (revealInFlight) {
            return ApiResult.Failure(
                com.zillit.zillitapp.core.network.ApiError.Http(
                    status = 429,
                    message = "reveal_rate_limited_locally",
                ),
            )
        }

        val now = System.currentTimeMillis()
        if (now - lastRevealAt < REVEAL_THROTTLE_MS) {
            return ApiResult.Failure(
                com.zillit.zillitapp.core.network.ApiError.Http(
                    status = 429,
                    message = "reveal_rate_limited_locally",
                ),
            )
        }
        lastRevealAt = now
        revealInFlight = true

        return try {
            when (val result = api.revealCredentials()) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> {
                    _revealedPassword.value = result.data.password
                    ApiResult.Success(result.data.toDomain())
                }
            }
        } finally {
            revealInFlight = false
        }
    }

    @Volatile
    private var revealInFlight = false

    /**
     * Forgets the plaintext.
     *
     * Called when the eye is tapped a second time and again when the sheet closes — hiding
     * **discards** rather than masking, so showing it again is another audited fetch rather
     * than a replay of a value this process has been holding onto.
     */
    fun hidePassword() {
        _revealedPassword.value = null
    }

    suspend fun updatePassword(password: String): ApiResult<Unit> = api.updatePassword(password)

    // ── BCC presets ──────────────────────────────────────────────────────────

    /**
     * Replaces the preset list.
     *
     * A complete replacement, not a delta — both endpoints take the whole list, so removing
     * one is a save without it.
     */
    suspend fun setBccPresets(addresses: List<String>): ApiResult<Unit> =
        api.setBccPresets(addresses)

    // ── Conversation view ────────────────────────────────────────────────────

    suspend fun setConversationView(enabled: Boolean): ApiResult<Unit> =
        api.setConversationView(enabled)

    private companion object {
        /** Enough to swallow a double-tap, short enough not to feel like a failure. */
        const val REVEAL_THROTTLE_MS = 1_200L
    }
}

/** Discards a successful result's payload, keeping the failure path intact. */
internal fun <T> ApiResult<T>.map(transform: (T) -> Unit): ApiResult<Unit> = when (this) {
    is ApiResult.Failure -> this
    is ApiResult.Success -> ApiResult.Success(transform(data))
}
