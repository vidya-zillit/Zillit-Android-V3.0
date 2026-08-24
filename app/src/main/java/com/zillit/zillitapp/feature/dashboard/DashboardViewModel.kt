package com.zillit.zillitapp.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.badge.BadgeKey
import com.zillit.zillitapp.core.badge.BadgeManager
import com.zillit.zillitapp.core.badge.BadgeSection
import com.zillit.zillitapp.core.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DashboardBadges(
    val home: Int = 0,
    val email: Int = 0,
    val tools: Int = 0,
    val cnc: Int = 0,
    /** Shown on the Zillit logo — everything unread in the active project. */
    val global: Int = 0,
)

/**
 * State for the dashboard shell.
 *
 * Badge counts come straight from [BadgeManager] as Flows, so a notification arriving
 * while the user is on any tab moves the relevant number without a refresh — the same
 * mechanism the project list uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val badgeManager: BadgeManager,
    private val session: SessionStore,
) : ViewModel() {

    val projectName: StateFlow<String> = session.activeProject
        .map { it?.projectId.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), "")

    /**
     * Tab totals, each a prefix sum over the badge path.
     *
     * These were five named counters in v2's 238-field model; here they are five queries
     * over the same rows, so a tab and the units inside it cannot disagree.
     */
    val badges: StateFlow<DashboardBadges> = session.activeProject
        .flatMapLatest { project ->
            val projectId = project?.projectId.orEmpty()
            combine(
                // Whole section, calendar invitations included: v2's bottom-nav Home badge
                // is `homeTotal + calendarPending + calendarExpired`, and those are the same
                // rows. Only the *unit* strip splits them out, because a unit's badge means
                // "unread in this chat" and an invitation is not that.
                badgeManager.observeUnder(BadgeKey.section(projectId, BadgeSection.HOME)),
                badgeManager.observeUnder(BadgeKey.section(projectId, BadgeSection.EMAIL)),
                badgeManager.observeUnder(BadgeKey.section(projectId, BadgeSection.TOOLS)),
                badgeManager.observeUnder(BadgeKey.section(projectId, BadgeSection.CNC)),
                // The logo counts the whole project, so it deliberately pins no section —
                // summing the tabs would miss anything the backend files elsewhere.
                badgeManager.observeUnder(BadgeKey.project(projectId)),
            ) { home, email, tools, cnc, project ->
                DashboardBadges(
                    home = home,
                    email = email,
                    tools = tools,
                    cnc = cnc,
                    global = project,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), DashboardBadges())

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
