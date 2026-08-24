package com.zillit.zillitapp.core.chat.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.draftDataStore by preferencesDataStore(name = "zillit_drafts")

/**
 * Half-written messages, one per thread.
 *
 * v2 caches the composer text against the unit id (`SharedPref.setUserCacheMessage`) and
 * restores it when the unit is reopened. Without it, tapping another unit to check
 * something throws away what you were typing — which on a set, mid-shot, is the difference
 * between the message being sent and not.
 *
 * Keyed by **project and scope**, not scope alone: two projects can hold units with the
 * same id, and a draft leaking across a project switch would be the same isolation bug the
 * upload queue was careful to avoid.
 */
@Singleton
class DraftStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun get(projectId: String, scopeId: String): String =
        context.draftDataStore.data.map { it[key(projectId, scopeId)].orEmpty() }.first()

    suspend fun set(projectId: String, scopeId: String, text: String) {
        context.draftDataStore.edit { prefs ->
            // Removed rather than stored blank, so the file does not accumulate an entry
            // for every unit ever opened.
            if (text.isBlank()) prefs.remove(key(projectId, scopeId))
            else prefs[key(projectId, scopeId)] = text
        }
    }

    suspend fun clear(projectId: String, scopeId: String) = set(projectId, scopeId, "")

    private fun key(projectId: String, scopeId: String) =
        stringPreferencesKey("draft:$projectId:$scopeId")
}
