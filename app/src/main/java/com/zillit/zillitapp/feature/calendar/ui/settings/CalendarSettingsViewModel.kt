package com.zillit.zillitapp.feature.calendar.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.calendar.sync.CalendarProvider
import com.zillit.zillitapp.core.calendar.sync.CalendarSync
import com.zillit.zillitapp.core.calendar.sync.CalendarSyncPreferences
import com.zillit.zillitapp.core.calendar.sync.CalendarTarget
import com.zillit.zillitapp.core.calendar.sync.SyncDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the settings screen shows about the mirror's health. */
sealed interface SyncHealth {
    data object Off : SyncHealth

    /** Sync is on but Android has not granted access. Actionable. */
    data object NeedsPermission : SyncHealth

    /** Nothing on the device can be written to. */
    data object NoCalendar : SyncHealth

    data object Healthy : SyncHealth
    data class Working(val pending: Int) : SyncHealth
    data class Failing(val failed: Int) : SyncHealth
}

data class CalendarSettingsState(
    val isEnabled: Boolean = false,
    val health: SyncHealth = SyncHealth.Off,
    val target: CalendarTarget? = null,
    val availableCalendars: List<CalendarTarget> = emptyList(),
)

/**
 * Backs the calendar settings screen.
 *
 * The health state is derived rather than stored, so it cannot claim "up to date" while
 * jobs are sitting in the queue or the permission has been revoked from Android settings
 * since the switch was turned on.
 */
@HiltViewModel
class CalendarSettingsViewModel @Inject constructor(
    private val sync: CalendarSync,
    private val preferences: CalendarSyncPreferences,
    private val provider: CalendarProvider,
    private val diagnostics: SyncDiagnostics,
) : ViewModel() {

    /** The diagnostics report, once gathered. Null while the sheet is closed. */
    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    fun gatherDiagnostics() {
        viewModelScope.launch { _report.value = diagnostics.gather() }
    }

    fun dismissDiagnostics() {
        _report.value = null
    }

    private val _target = MutableStateFlow<CalendarTarget?>(null)
    private val _calendars = MutableStateFlow<List<CalendarTarget>>(emptyList())

    /**
     * Why the last attempt to turn sync on did not work.
     *
     * A flow, not a field: the health line is derived inside a `combine`, and a plain
     * field would change without anything emitting — leaving the switch to slide back with
     * no explanation, which is exactly the failure this is here to describe.
     */
    private val _blockedBy = MutableStateFlow<SyncHealth?>(null)

    private val base = combine(
        preferences.enabled,
        sync.pendingCount,
        sync.failedCount,
        _target,
        _calendars,
    ) { enabled, pending, failed, target, calendars ->
        CalendarSettingsState(
            isEnabled = enabled,
            health = health(enabled, pending, failed),
            target = target,
            availableCalendars = calendars,
        )
    }

    val state: StateFlow<CalendarSettingsState> = combine(base, _blockedBy) { state, blocked ->
        // A blocked attempt outranks "off": the user just asked for this and deserves the
        // reason it did not happen.
        if (blocked != null && !state.isEnabled) state.copy(health = blocked) else state
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), CalendarSettingsState())

    init {
        refreshTarget()
    }

    /**
     * Re-read whenever the screen is shown.
     *
     * The permission can be granted or revoked in Android's own settings while this screen
     * is in the background, so the state on return has to be re-derived rather than
     * remembered.
     */
    fun onResumed() = refreshTarget()

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                _blockedBy.value = null

                if (!provider.hasPermission()) {
                    _blockedBy.value = SyncHealth.NeedsPermission
                    return@launch
                }

                if (!sync.enable()) {
                    // The only remaining reason enable can refuse: nowhere to write.
                    _blockedBy.value = SyncHealth.NoCalendar
                }
            } else {
                _blockedBy.value = null
                sync.disable()
            }
            refreshTarget()
        }
    }

    fun selectCalendar(target: CalendarTarget) {
        viewModelScope.launch {
            preferences.setTarget(target)
            _target.value = target
            // The mirror now belongs somewhere else, so what was written before is
            // rebuilt in the new calendar rather than stranded in the old one.
            sync.resetAndResync()
        }
    }

    fun syncNow() {
        viewModelScope.launch { sync.retryFailed() }
    }

    fun resetAndResync() {
        viewModelScope.launch { sync.resetAndResync() }
    }

    private fun refreshTarget() {
        viewModelScope.launch {
            _calendars.value = provider.writableCalendars()
            _target.value = preferences.target() ?: provider.defaultTarget()
        }
    }

    private fun health(enabled: Boolean, pending: Int, failed: Int): SyncHealth = when {
        !enabled -> SyncHealth.Off
        !provider.hasPermission() -> SyncHealth.NeedsPermission
        provider.writableCalendars().isEmpty() -> SyncHealth.NoCalendar
        // Failures come first: work still queued behind a failure is not reassuring news.
        failed > 0 -> SyncHealth.Failing(failed)
        pending > 0 -> SyncHealth.Working(pending)
        else -> SyncHealth.Healthy
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
