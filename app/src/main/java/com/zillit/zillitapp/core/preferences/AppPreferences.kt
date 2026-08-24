package com.zillit.zillitapp.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.preferencesDataStore
import com.zillit.zillitapp.core.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's one preference store.
 *
 * Internal rather than private so other core modules — calendar sync, for one — keep their
 * own keys next to the code that uses them instead of piling every setting in this class.
 */
internal val Context.dataStore by preferencesDataStore(name = "zillit_prefs")

/**
 * Persisted app-level preferences.
 *
 * DataStore rather than SharedPreferences: reads are a Flow, so the theme applies the
 * moment it changes without an activity restart, and writes are on a background
 * dispatcher instead of the main thread.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Theme preference. Defaults to [ThemeMode.SYSTEM] — following the OS setting is
     * the expected behaviour, and it is what v2 defaulted to as well.
     */
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        ThemeMode.fromStorage(prefs[KEY_THEME_MODE] ?: ThemeMode.SYSTEM.ordinal)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode.ordinal }
    }

    /** False until the user has completed onboarding once. */
    /**
     * How large chat text is drawn.
     *
     * A reading preference, not a system one: crew read the app outdoors in daylight with
     * gloves on, and v2 exposes exactly these three steps under Settings → Chat Font Size.
     * Stored as the sp value so the mapping lives in one place.
     */
    val chatFontSize: Flow<ChatFontSize> = context.dataStore.data.map { prefs ->
        ChatFontSize.fromSp(prefs[KEY_CHAT_FONT_SIZE] ?: ChatFontSize.MEDIUM.sp)
    }

    suspend fun setChatFontSize(size: ChatFontSize) {
        context.dataStore.edit { it[KEY_CHAT_FONT_SIZE] = size.sp }
    }

    val hasOnboarded: Flow<Boolean> = context.dataStore.data.map { it[KEY_ONBOARDED] ?: false }

    /** Last project the user had open, so the app can return there on launch. */
    val lastProjectId: Flow<String?> = context.dataStore.data.map { it[KEY_LAST_PROJECT] }

    suspend fun setLastProjectId(projectId: String?) {
        context.dataStore.edit { prefs ->
            if (projectId == null) prefs.remove(KEY_LAST_PROJECT)
            else prefs[KEY_LAST_PROJECT] = projectId
        }
    }

    /** Server-issued id from the QR device-linking flow. */
    val scannerDeviceId: Flow<String?> = context.dataStore.data.map { it[KEY_SCANNER_DEVICE_ID] }

    suspend fun setScannerDeviceId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_SCANNER_DEVICE_ID)
            else prefs[KEY_SCANNER_DEVICE_ID] = id
        }
    }

    /**
     * Global notification mute. Silent pushes still update badges when muted — muting
     * suppresses the tray, not the state.
     */
    val isNotificationMuted: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_NOTIFICATIONS_MUTED] ?: false }

    /** Current FCM registration token, so it can be re-sent to the backend on change. */
    val fcmToken: Flow<String?> = context.dataStore.data.map { it[KEY_FCM_TOKEN] }

    /**
     * The signed-in user's own profile, as JSON.
     *
     * Stored whole rather than field-by-field: the shape is owned by
     * [com.zillit.zillitapp.core.session.CurrentUser], so adding a field there must not
     * mean adding a preference key here.
     */
    suspend fun getCurrentUserJson(): String? =
        context.dataStore.data.first()[KEY_CURRENT_USER]

    suspend fun setCurrentUserJson(value: String?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(KEY_CURRENT_USER) else prefs[KEY_CURRENT_USER] = value
        }
    }

    /** S3/Box credentials. Whole-object for the same reason as the profile. */
    suspend fun getStorageCredentialsJson(): String? =
        context.dataStore.data.first()[KEY_STORAGE_CREDENTIALS]

    suspend fun setStorageCredentialsJson(value: String?) {
        context.dataStore.edit { prefs ->
            if (value == null) {
                prefs.remove(KEY_STORAGE_CREDENTIALS)
            } else {
                prefs[KEY_STORAGE_CREDENTIALS] = value
            }
        }
    }

    suspend fun getAppConfigurationJson(): String? =
        context.dataStore.data.first()[KEY_APP_CONFIGURATION]

    suspend fun setAppConfigurationJson(value: String?) {
        context.dataStore.edit { prefs ->
            if (value == null) {
                prefs.remove(KEY_APP_CONFIGURATION)
            } else {
                prefs[KEY_APP_CONFIGURATION] = value
            }
        }
    }

    suspend fun setFcmToken(token: String) {
        context.dataStore.edit { it[KEY_FCM_TOKEN] = token }
    }

    private companion object {
        val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        val KEY_CHAT_FONT_SIZE = intPreferencesKey("chat_font_size")
        val KEY_ONBOARDED = booleanPreferencesKey("has_onboarded")
        val KEY_LAST_PROJECT = stringPreferencesKey("last_project_id")
        val KEY_SCANNER_DEVICE_ID = stringPreferencesKey("scanner_device_id")
        val KEY_NOTIFICATIONS_MUTED = booleanPreferencesKey("notifications_muted")
        val KEY_FCM_TOKEN = stringPreferencesKey("fcm_token")
        val KEY_CURRENT_USER = stringPreferencesKey("current_user")
        val KEY_STORAGE_CREDENTIALS = stringPreferencesKey("storage_credentials")
        val KEY_APP_CONFIGURATION = stringPreferencesKey("app_configuration")
    }
}

/**
 * The three chat text sizes, in sp.
 *
 * Values are v2's `Constants.SMALL/MEDIUM/LARGE_FONT_SIZE` so a user's choice means the
 * same thing after upgrading.
 */
enum class ChatFontSize(val sp: Int) {
    SMALL(16),
    MEDIUM(18),
    LARGE(22),
    ;

    /**
     * Multiplier applied to the theme's body styles.
     *
     * Scaling rather than overriding sizes keeps the type hierarchy intact — a caption
     * stays smaller than a message body at every setting.
     */
    val scale: Float get() = sp / MEDIUM.sp.toFloat()

    companion object {
        fun fromSp(sp: Int): ChatFontSize = entries.firstOrNull { it.sp == sp } ?: MEDIUM
    }
}
