package com.zillit.zillitapp.feature.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.settings.ui.SettingsDestination
import com.zillit.zillitapp.feature.settings.ui.SettingsScreen
import com.zillit.zillitapp.core.ui.components.CountBadge
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.ZillitMainTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.calendar.ui.lists.EventListKind
import com.zillit.zillitapp.feature.home.ui.HomeRoute

/** The five dashboard destinations, in the order v2 shows them. */
enum class DashboardTab(val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Outlined.Home),
    EMAIL(R.string.tab_email, Icons.Outlined.Email),
    TOOLS(R.string.tab_tools, Icons.Outlined.Build),
    CNC(R.string.tab_cnc, Icons.Outlined.Chat),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings),
}

@Composable
fun DashboardRoute(
    projectName: String,
    onChangeProject: () -> Unit,
    onNotifications: () -> Unit,
    onSos: () -> Unit,
    onHelp: () -> Unit,
    onOpenEventList: (EventListKind) -> Unit,
    onCreateEvent: (Long) -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onOpenSetting: (SettingsDestination) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val badges by viewModel.badges.collectAsStateWithLifecycle()

    DashboardScreen(
        projectName = projectName,
        badges = badges,
        onChangeProject = onChangeProject,
        onNotifications = onNotifications,
        onSos = onSos,
        onHelp = onHelp,
        onOpenEventList = onOpenEventList,
        onCreateEvent = onCreateEvent,
        onOpenCalendarSettings = onOpenCalendarSettings,
        onOpenSetting = onOpenSetting,
    )
}

/**
 * The dashboard shell: top bar plus five tabs.
 *
 * Tab content is swapped inside a single screen rather than being five nav destinations,
 * because the top bar and bottom bar are constant across all of them — routing each tab
 * separately would mean re-declaring the chrome five times and keeping it in sync.
 *
 * Selection is `rememberSaveable`, so returning to the app lands on the tab the user left
 * rather than resetting to Home.
 */
@Composable
fun DashboardScreen(
    projectName: String,
    badges: DashboardBadges,
    onChangeProject: () -> Unit,
    onNotifications: () -> Unit,
    onSos: () -> Unit,
    onHelp: () -> Unit,
    onOpenEventList: (EventListKind) -> Unit,
    onCreateEvent: (Long) -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onOpenSetting: (SettingsDestination) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(DashboardTab.HOME) }

    Scaffold(
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitMainTopBar(
                projectName = projectName,
                // The logo carries the project-wide unread count and opens notifications.
                unreadCount = badges.global,
                onLogoClick = onNotifications,
                onProjectSwitchClick = onChangeProject,
                onSosClick = onSos,
                // Same place as on the project list, so it stays findable across screens.
                onHelpClick = onHelp,
            )
        },
        bottomBar = {
            NavigationBar(containerColor = ZillitTheme.colors.surface) {
                DashboardTab.entries.forEach { tab ->
                    val count = badges.countFor(tab)

                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            // BadgedBox only positions the pill; the pill itself is the
                            // shared one, so a tab badge and a unit badge are the same
                            // size and shape.
                            BadgedBox(badge = { CountBadge(count = count) }) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = stringResource(tab.labelRes),
                                )
                            }
                        },
                        label = { Text(stringResource(tab.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ZillitTheme.colors.brand,
                            selectedTextColor = ZillitTheme.colors.brand,
                            indicatorColor = ZillitTheme.colors.brandSoft,
                            unselectedIconColor = ZillitTheme.colors.textTertiary,
                            unselectedTextColor = ZillitTheme.colors.textTertiary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Each tab's real content lands here one at a time; the shell around it is
            // already final.
            when (selectedTab) {
                // Units are real — loaded into Realm when the project opened. The
                // message feed is still sample data until the chat API lands.
                DashboardTab.HOME -> HomeRoute(
                    onOpenEventList = onOpenEventList,
                    onCreateEvent = onCreateEvent,
                    onOpenCalendarSettings = onOpenCalendarSettings,
                )

                DashboardTab.SETTINGS -> SettingsScreen(
                    onOpen = onOpenSetting,
                    onEditProfile = { onOpenSetting(SettingsDestination.EDIT_PROFILE) },
                )
                else -> TabPlaceholder(tab = selectedTab)
            }
        }
    }
}

@Composable
private fun TabPlaceholder(tab: DashboardTab) {
    val label = stringResource(tab.labelRes)
    EmptyState(
        icon = tab.icon,
        title = stringResource(R.string.tab_placeholder, label),
        description = stringResource(R.string.tab_placeholder_description),
    )
}

private fun DashboardBadges.countFor(tab: DashboardTab): Int = when (tab) {
    DashboardTab.HOME -> home
    DashboardTab.EMAIL -> email
    DashboardTab.TOOLS -> tools
    DashboardTab.CNC -> cnc
    DashboardTab.SETTINGS -> 0
}

