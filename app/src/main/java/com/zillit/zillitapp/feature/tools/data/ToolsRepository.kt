package com.zillit.zillitapp.feature.tools.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.preferences.dataStore
import com.zillit.zillitapp.core.session.SessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A section, resolved. */
@kotlinx.serialization.Serializable
data class ToolGroup(
    val identifier: String,
    val name: String,
    val groupId: String?,
    val systemDefined: Boolean,
    val order: Int,
)

/**
 * The Tools tab's sections and their order.
 *
 * The tool **list** is not here — the project directory owns that, and fetches it at
 * bootstrap. This is the two things layered on top of it: which sections exist, and what
 * order this user wants them in.
 *
 * ### Cached, then refreshed
 * Both are read from the device first and published immediately, so the sections are drawn
 * with their real names on the first frame rather than appearing as raw identifiers and
 * rearranging a moment later. The network answer replaces them when it lands.
 */
@Singleton
class ToolsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ToolsApi,
    private val session: SessionStore,
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _groups = MutableStateFlow<List<ToolGroup>>(emptyList())
    val groups: StateFlow<List<ToolGroup>> = _groups.asStateFlow()

    /** Section identifiers, top first. Empty until something has been loaded. */
    private val _order = MutableStateFlow<List<String>>(emptyList())
    val order: StateFlow<List<String>> = _order.asStateFlow()

    init {
        scope.launch { readCache() }
    }

    suspend fun refreshGroups(): ApiResult<Unit> {
        val result = api.groups(Locale.getDefault().language)
        if (result is ApiResult.Success) {
            val groups = result.data.mapNotNull { it.toDomain() }.sortedBy { it.order }
            if (groups.isNotEmpty()) {
                _groups.value = groups
                writeCache(KEY_GROUPS, json.encodeToString(CachedGroups.serializer(), CachedGroups(groups)))
            }
        }
        return result.unit()
    }

    suspend fun refreshOrder(): ApiResult<Unit> {
        val result = api.order()
        if (result is ApiResult.Success && result.data.isNotEmpty()) {
            _order.value = result.data
            writeCache(KEY_ORDER, result.data.joinToString(","))
        }
        return result.unit()
    }

    /**
     * Saves this user's section order.
     *
     * Reconciled first: the backend requires the array to hold exactly the project's current
     * group identifiers, each once, and rejects anything else — so a stale id from a group
     * somebody deleted would fail the whole save. Applied locally before the request and
     * rolled back if it is refused.
     */
    suspend fun saveOrder(order: List<String>): ApiResult<Unit> {
        val previous = _order.value
        val reconciled = reconcile(order)

        _order.value = reconciled

        return when (val result = api.saveOrder(reconciled)) {
            is ApiResult.Success -> {
                if (result.data.isNotEmpty()) _order.value = result.data
                writeCache(KEY_ORDER, _order.value.joinToString(","))
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> {
                _order.value = previous
                result
            }
        }
    }

    /**
     * Keeps the user's sequence for groups that still exist, drops ids for groups that do
     * not, and appends any group the order has never heard of.
     */
    fun reconcile(order: List<String>): List<String> {
        val known = _groups.value.map { it.identifier }
        if (known.isEmpty()) return order.distinct()
        return (order.filter { it in known } + known.filterNot { it in order }).distinct()
    }

    suspend fun createGroup(name: String): ApiResult<Unit> =
        api.createGroup(name).thenRefreshGroups()

    suspend fun renameGroup(groupId: String, name: String): ApiResult<Unit> =
        api.renameGroup(groupId, name).thenRefreshGroups()

    suspend fun deleteGroup(groupId: String): ApiResult<Unit> =
        api.deleteGroup(groupId).thenRefreshGroups()

    suspend fun moveToGroup(identifier: String, groupIdentifier: String): ApiResult<Unit> =
        api.moveToGroup(identifier, groupIdentifier)

    suspend fun adminTools() = api.adminTools()

    suspend fun setEnabled(tools: List<EnableToolItem>): ApiResult<Unit> = api.setEnabled(tools)

    /** Forgets the cached sections. For sign-out, and when a project closes. */
    suspend fun clear() {
        _groups.value = emptyList()
        _order.value = emptyList()
        context.dataStore.edit {
            it.remove(KEY_GROUPS)
            it.remove(KEY_ORDER)
        }
    }

    private suspend fun <T> ApiResult<T>.thenRefreshGroups(): ApiResult<Unit> = when (this) {
        is ApiResult.Success -> refreshGroups()
        is ApiResult.Failure -> this
    }

    private fun <T> ApiResult<T>.unit(): ApiResult<Unit> = when (this) {
        is ApiResult.Success -> ApiResult.Success(Unit)
        is ApiResult.Failure -> this
    }

    private suspend fun readCache() {
        val prefs = runCatching { context.dataStore.data.first() }.getOrNull() ?: return

        prefs[KEY_GROUPS]?.let { stored ->
            runCatching { json.decodeFromString(CachedGroups.serializer(), stored).groups }
                .onSuccess { if (it.isNotEmpty()) _groups.value = it }
                .onFailure { ZillitLog.w(TAG, "cached tool groups unreadable: ${it.message}") }
        }

        prefs[KEY_ORDER]
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { _order.value = it }
    }

    private suspend fun writeCache(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        runCatching { context.dataStore.edit { it[key] = value } }
            .onFailure { ZillitLog.w(TAG, "could not cache tool sections: ${it.message}") }
    }

    private fun ToolGroupDto.toDomain(): ToolGroup? {
        val identifier = groupIdentifier?.takeIf { it.isNotBlank() } ?: return null
        return ToolGroup(
            identifier = identifier,
            // The server's own name wins; the identifier is the last resort, and showing it
            // is still better than showing a blank header.
            name = groupName?.takeIf { it.isNotBlank() } ?: identifier,
            groupId = toolGroupId,
            systemDefined = systemDefined ?: false,
            order = order ?: Int.MAX_VALUE,
        )
    }

    @kotlinx.serialization.Serializable
    private data class CachedGroups(val groups: List<ToolGroup>)

    private companion object {
        const val TAG = "ToolsRepository"
        val KEY_GROUPS = stringPreferencesKey("tool_groups")
        val KEY_ORDER = stringPreferencesKey("tool_group_order")
    }
}
