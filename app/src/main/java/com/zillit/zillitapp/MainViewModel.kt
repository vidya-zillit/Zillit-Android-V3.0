package com.zillit.zillitapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.labels.LabelRepository
import com.zillit.zillitapp.core.preferences.AppPreferences
import com.zillit.zillitapp.core.preferences.ChatFontSize
import com.zillit.zillitapp.core.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-level state that outlives any single screen — currently just the theme.
 *
 * Held in a ViewModel rather than read directly in the composable so the value survives
 * configuration changes; a rotate does not re-read DataStore and briefly flash the
 * default theme.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val labelRepository: LabelRepository,
    networkMonitor: com.zillit.zillitapp.core.network.NetworkMonitor,
) : ViewModel() {

    /** Server label dictionary, provided to the tree so labels resolve at render time. */
    /** Drives the app-wide connectivity banner. */
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    val labels: StateFlow<Map<String, String>> = labelRepository.labels
    val messages: StateFlow<Map<String, String>> = labelRepository.messages
    val identifiers: StateFlow<Map<String, String>> = labelRepository.identifiers

    val themeMode: StateFlow<ThemeMode> = preferences.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ThemeMode.SYSTEM,
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    /** Applied at the theme, so every screen picks it up without opting in. */
    val chatFontSize: StateFlow<ChatFontSize> = preferences.chatFontSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ChatFontSize.MEDIUM,
    )

    fun setChatFontSize(size: ChatFontSize) {
        viewModelScope.launch { preferences.setChatFontSize(size) }
    }

    /**
     * Re-syncs the server label dictionary when the device language changes.
     *
     * Unit names, designations and tool names are all label **keys** resolved through a
     * dictionary fetched per language and cached. Splash is the only other place that
     * checks, so without this a language changed mid-session leaves every one of those
     * names in the previous language until the app is next cold-started — which is
     * exactly what it looks like: a chat in one language and its unit tabs in another.
     *
     * `syncIfStale` compares the stored language itself, so calling it on every locale
     * change costs one cheap comparison when nothing moved.
     */
    fun onDeviceLocaleChanged() {
        viewModelScope.launch { labelRepository.syncIfStale() }
    }
}
