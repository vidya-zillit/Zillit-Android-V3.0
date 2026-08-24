package com.zillit.zillitapp.core.calendar.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zillit.zillitapp.core.preferences.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether to mirror events into the device's calendar, and which calendar to write to.
 *
 * Off by default. Writing into someone's personal calendar is a decision they make, not
 * one the app makes for them.
 *
 * The chosen calendar is remembered by id **and** by account, so a target that disappears
 * — an account removed, a calendar deleted — can be recognised as gone rather than
 * silently writing somewhere else.
 */
@Singleton
class CalendarSyncPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val store get() = context.dataStore

    val enabled: Flow<Boolean> = store.data.map { it[KEY_ENABLED] ?: false }

    val targetId: Flow<Long?> = store.data.map { it[KEY_TARGET_ID] }

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[KEY_ENABLED] = value }
    }

    /** The chosen calendar, or null when none has been chosen or it has gone away. */
    suspend fun target(): CalendarTarget? {
        val preferences = store.data.first()
        val id = preferences[KEY_TARGET_ID] ?: return null
        return CalendarTarget(
            id = id,
            accountName = preferences[KEY_TARGET_ACCOUNT].orEmpty(),
            accountType = preferences[KEY_TARGET_ACCOUNT_TYPE].orEmpty(),
            displayName = preferences[KEY_TARGET_NAME].orEmpty(),
        )
    }

    suspend fun setTarget(target: CalendarTarget?) {
        store.edit { preferences ->
            if (target == null) {
                preferences.remove(KEY_TARGET_ID)
                preferences.remove(KEY_TARGET_ACCOUNT)
                preferences.remove(KEY_TARGET_ACCOUNT_TYPE)
                preferences.remove(KEY_TARGET_NAME)
            } else {
                preferences[KEY_TARGET_ID] = target.id
                preferences[KEY_TARGET_ACCOUNT] = target.accountName
                preferences[KEY_TARGET_ACCOUNT_TYPE] = target.accountType
                preferences[KEY_TARGET_NAME] = target.displayName
            }
        }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("calendar_sync_enabled")
        val KEY_TARGET_ID = longPreferencesKey("calendar_sync_target_id")
        val KEY_TARGET_ACCOUNT = stringPreferencesKey("calendar_sync_target_account")
        val KEY_TARGET_ACCOUNT_TYPE = stringPreferencesKey("calendar_sync_target_account_type")
        val KEY_TARGET_NAME = stringPreferencesKey("calendar_sync_target_name")
    }
}
