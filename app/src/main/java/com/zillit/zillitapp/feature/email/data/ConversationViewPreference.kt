package com.zillit.zillitapp.feature.email.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.preferences.dataStore
import com.zillit.zillitapp.core.session.CurrentUserStore
import com.zillit.zillitapp.feature.email.domain.MailboxScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a folder is grouped into conversations — one value, shared by the whole module.
 *
 * It has to be a singleton. The switch lives in settings and the grouping happens in the
 * list, and those are separate view models: two `MutableStateFlow`s seeded from the same
 * cached profile meant the toggle changed the switch and nothing else, with the list staying
 * as it was until the profile was refetched.
 *
 * ### Per mailbox
 * The shared Accounts mailbox keeps **its own** value, set by whoever administers it, and
 * the personal one keeps the user's. So this is re-read on every mailbox switch rather than
 * being one flag for the module.
 *
 * ### Why it is also written locally
 * Only the personal value is cached on the device, so the list groups correctly at launch
 * before the profile request lands. The shared mailbox's value belongs to the project and is
 * read from the project record each time.
 */
@Singleton
class ConversationViewPreference @Inject constructor(
    @ApplicationContext private val context: Context,
    private val currentUser: CurrentUserStore,
    private val mailbox: EmailMailboxContext,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the value for whichever mailbox is now active. */
    fun refresh() {
        scope.launch {
            _enabled.value = if (mailbox.effectiveScope == MailboxScope.SHARED) {
                mailbox.accountsMailboxInfo.value?.conversationView ?: DEFAULT
            } else {
                // The stored value wins over the profile: it is the one the user last set,
                // and the profile can be a launch behind it.
                context.dataStore.data.first()[KEY] ?: currentUser.current?.conversationView ?: DEFAULT
            }
        }
    }

    /**
     * Records a change the server has already accepted.
     *
     * Called after the API call, not before: an optimistic local write that the server then
     * refused would leave the device disagreeing with every other one the user owns.
     */
    fun set(enabled: Boolean) {
        _enabled.value = enabled
        if (mailbox.effectiveScope == MailboxScope.SHARED) return

        scope.launch { context.dataStore.edit { it[KEY] = enabled } }
    }

    private companion object {
        /** v2's default when nothing is known — grouping on. */
        const val DEFAULT = true

        val KEY = booleanPreferencesKey("email_conversation_view")
    }
}
