package com.zillit.zillitapp.core.labels

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zillit.zillitapp.core.config.RemoteConfigSource
import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.labelDataStore by preferencesDataStore(name = "zillit_labels")

@Serializable
data class LabelResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: JsonObject? = null,
)

/** The three independent dictionaries the backend serves. */
enum class LabelDictionary(val endpoint: String) {
    /** Content labels — project types, departments, designations. */
    LABELS(ApiEndpoints.Preset.LABELS),

    /** Backend status/error messages, keyed by code. */
    MESSAGES(ApiEndpoints.Preset.MESSAGES),

    /** Field and section identifiers used by dynamic forms. */
    IDENTIFIERS(ApiEndpoints.Preset.IDENTIFIERS),
}

/**
 * The **server-side** half of localization, with a version gate.
 *
 * Zillit localizes two ways and a screen needs both:
 *  1. App-authored copy → `strings.xml`.
 *  2. **Server-authored content** → arrives as *keys*, not text. A project's type comes
 *     back literally as `entertainment_industry_label`, resolved against a dictionary
 *     fetched per language.
 *
 * ### Why the dictionaries are not fetched on every launch
 * They are large and change rarely. The backend team bumps `locale_version` in Firebase
 * Remote Config when translations change; [syncIfStale] compares that number with the
 * version stored alongside the cached dictionaries and only re-downloads when they
 * differ. Same key and same contract as v2, so both apps are driven by one console value.
 *
 * A first install has stored version `null` and Remote Config default `0`, and the
 * `stored == null` check makes that fetch — an empty dictionary is never mistaken for an
 * up-to-date one.
 */
@Singleton
class LabelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ZillitApi,
    private val json: Json,
    private val remoteConfig: RemoteConfigSource,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Content labels. Backed by DataStore, so it survives process death. */
    val labels: StateFlow<Map<String, String>> = dictionaryFlow(LabelDictionary.LABELS)

    /** Backend message codes. */
    val messages: StateFlow<Map<String, String>> = dictionaryFlow(LabelDictionary.MESSAGES)

    /** Form/field identifiers. */
    val identifiers: StateFlow<Map<String, String>> = dictionaryFlow(LabelDictionary.IDENTIFIERS)

    /**
     * Re-downloads the dictionaries only if the server says they changed, or if the
     * device language differs from the one they were cached for.
     *
     * The language check matters as much as the version: `locale_version` is global, so
     * without it a user switching language would keep the previous language's dictionary
     * until the backend happened to bump the counter.
     *
     * @return true if a fetch actually happened.
     */
    suspend fun syncIfStale(languageTag: String = deviceLanguage()): Boolean {
        remoteConfig.refresh()
        val remoteVersion = remoteConfig.localeVersion

        val prefs = context.labelDataStore.data.first()
        val storedVersion = prefs[KEY_VERSION]
        val storedLanguage = prefs[KEY_LANGUAGE]

        val stale = storedVersion == null ||
            storedVersion != remoteVersion ||
            storedLanguage != languageTag

        if (!stale) return false

        val fetched = fetchAll(languageTag)
        if (fetched) {
            context.labelDataStore.edit { editable ->
                editable[KEY_VERSION] = remoteVersion
                editable[KEY_LANGUAGE] = languageTag
            }
        }
        return fetched
    }

    /**
     * Fetches all three dictionaries concurrently.
     *
     * The version marker is only written when *every* dictionary succeeded — a partial
     * failure must stay stale, or the app would remember a version it never fully
     * downloaded and never retry.
     */
    private suspend fun fetchAll(languageTag: String): Boolean = coroutineScope {
        LabelDictionary.entries
            .map { dictionary -> async { fetch(dictionary, languageTag) } }
            .all { it.await() }
    }

    private suspend fun fetch(dictionary: LabelDictionary, languageTag: String): Boolean {
        val result = api.get<LabelResponse>(
            url = dictionary.endpoint,
            module = ModuleData.DEFAULT,
            query = mapOf("lang" to languageTag),
        )

        return when (result) {
            is ApiResult.Failure -> false
            is ApiResult.Success -> {
                val payload = result.data.data ?: return false
                context.labelDataStore.edit { it[keyFor(dictionary)] = payload.toString() }
                true
            }
        }
    }

    /** Forces a re-download regardless of version. For a manual language change. */
    suspend fun forceRefresh(languageTag: String = deviceLanguage()): Boolean {
        val fetched = fetchAll(languageTag)
        if (fetched) {
            context.labelDataStore.edit { it[KEY_LANGUAGE] = languageTag }
        }
        return fetched
    }

    private fun dictionaryFlow(dictionary: LabelDictionary): StateFlow<Map<String, String>> =
        context.labelDataStore.data
            .map { prefs -> prefs[keyFor(dictionary)]?.let(::parse).orEmpty() }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private fun parse(raw: String): Map<String, String> = runCatching {
        (json.parseToJsonElement(raw) as? JsonObject)
            ?.mapValues { (_, value) -> (value as? JsonPrimitive)?.content.orEmpty() }
            .orEmpty()
    }.getOrDefault(emptyMap())

    private fun deviceLanguage(): String = Locale.getDefault().language.ifBlank { "en" }

    private fun keyFor(dictionary: LabelDictionary) =
        stringPreferencesKey("dictionary_${dictionary.name.lowercase()}")

    private companion object {
        val KEY_VERSION = longPreferencesKey("locale_version")
        val KEY_LANGUAGE = stringPreferencesKey("labels_language")
    }
}
