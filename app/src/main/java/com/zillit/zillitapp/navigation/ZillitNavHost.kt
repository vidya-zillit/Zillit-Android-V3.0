package com.zillit.zillitapp.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.help.ui.HelpScreen
import com.zillit.zillitapp.feature.notification.ui.NotificationScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zillit.zillitapp.core.scanner.QrScannerScreen
import androidx.navigation.toRoute
import com.zillit.zillitapp.feature.createproject.ui.CreateProjectRoute
import com.zillit.zillitapp.feature.calendar.ui.form.EventFormEntry
import com.zillit.zillitapp.feature.calendar.ui.settings.CalendarSettingsRoute
import com.zillit.zillitapp.feature.calendar.ui.lists.EventListRoute
import com.zillit.zillitapp.feature.dashboard.DashboardRoute
import com.zillit.zillitapp.feature.shareproject.ui.ShareProjectRoute
import com.zillit.zillitapp.feature.linkeddevice.ui.LinkedDeviceRoute
import com.zillit.zillitapp.feature.linkeddevice.ui.LinkedDeviceViewModel
import com.zillit.zillitapp.feature.project.ui.ProjectListRoute
import com.zillit.zillitapp.feature.settings.ui.AccountSettingsScreen
import com.zillit.zillitapp.feature.settings.ui.AppPreferencesRoute
import com.zillit.zillitapp.feature.settings.ui.InviteUsersScreen
import com.zillit.zillitapp.feature.settings.ui.SettingsDestination
import com.zillit.zillitapp.feature.settings.ui.AdminSettingsScreen
import com.zillit.zillitapp.feature.settings.ui.CateringSettingsScreen
import com.zillit.zillitapp.feature.settings.ui.EditProfileScreen
import com.zillit.zillitapp.feature.settings.ui.LeaveProjectScreen
import com.zillit.zillitapp.feature.settings.ui.PrivacyPreferencesScreen
import com.zillit.zillitapp.feature.settings.ui.RecoveryCodeScreen
import com.zillit.zillitapp.feature.splash.SplashRoute
import kotlinx.serialization.Serializable

/**
 * Type-safe routes. Navigation Compose resolves these from the `@Serializable` type, so a
 * typo is a compile error rather than a runtime "route not found".
 *
 * v2 navigated with ~400 `Intent(context, SomeActivity::class.java)` calls and only 7 nav
 * graphs, which is why back-stack behaviour differed screen to screen.
 */
sealed interface Route {
    @Serializable
    data object Splash : Route

    @Serializable
    data object ProjectList : Route

    @Serializable
    data object CreateProject : Route

    @Serializable
    data object JoinProject : Route

    /** Linked web/tablet sessions. Reached from the QR action and, later, Settings. */
    @Serializable
    data object LinkedDevices : Route

    @Serializable
    data object ScanQrCode : Route

    @Serializable
    data object Help : Route

    /** The project dashboard — the five-tab shell entered by opening a project. */
    @Serializable
    data class Dashboard(val projectName: String) : Route

    @Serializable
    data object Notifications : Route

    @Serializable
    data object Sos : Route

    /** Shown once after creating a project, carrying its share code. */
    @Serializable
    data class ShareProjectCode(val projectName: String, val projectCode: String) : Route

    /**
     * One of the calendar's four event lists.
     *
     * A single route carrying which list it is, because they share a screen — see
     * [com.zillit.zillitapp.feature.calendar.ui.lists.EventListKind].
     */
    @Serializable
    data class CalendarEvents(val kind: String) : Route

    /**
     * The event form. Empty [eventId] means create.
     *
     * [selectedDateMs] is the day the user was looking at when they tapped +, so a new
     * event opens on that date rather than always on today.
     */
    /** Device-calendar mirroring settings. */
    @Serializable
    data object CalendarSettings : Route

    @Serializable
    data class CalendarEventForm(
        val eventId: String = "",
        val occurrenceStartMs: Long = 0,
        val selectedDateMs: Long = 0,
    ) : Route

    // Settings destinations. Each is its own route rather than one parameterised screen,
    // because they will become genuinely different screens — sharing a route now would only
    // have to be unpicked later.
    /** App Preferences, opened from Settings rather than living inside the tab. */
    @Serializable
    data object AppPreferences : Route

    /** Invite Users — the project code, shown without the post-creation framing. */
    @Serializable
    data object InviteUsers : Route

    @Serializable
    data object EditProfile : Route

    @Serializable
    data object PrivacyPreferences : Route

    @Serializable
    data object RecoveryCode : Route

    @Serializable
    data object AccountSettings : Route

    @Serializable
    data object CateringSettings : Route

    @Serializable
    data object AdminSettings : Route

    @Serializable
    data object LeaveProject : Route
}

@Composable
fun ZillitNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Route.Splash,
    ) {
        composable<Route.Splash> {
            SplashRoute(
                onReady = {
                    navController.navigate(Route.ProjectList) {
                        // Splash must not be reachable via back.
                        popUpTo(Route.Splash) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable<Route.ProjectList> {
            ProjectListRoute(
                onProjectClick = { project ->
                    // The ViewModel has already set the active project by this point, so
                    // the dashboard's badge and API calls are correctly scoped.
                    navController.navigate(Route.Dashboard(project.name))
                },
                onCreateProject = { navController.navigate(Route.CreateProject) },
                onJoinProject = { navController.navigate(Route.JoinProject) },
                onScanQrCode = { navController.navigate(Route.LinkedDevices) },
                onHelpClick = { navController.navigate(Route.Help) },
            )
        }

        composable<Route.CreateProject> {
            CreateProjectRoute(
                onBack = navController::popBackStack,
                onCreated = { projectName, projectCode ->
                    // Straight to the share screen, matching v2 — the code is presented
                    // once and the user must acknowledge it before reaching the list.
                    // Create is popped so back cannot re-enter a completed flow.
                    navController.navigate(Route.ShareProjectCode(projectName, projectCode)) {
                        popUpTo(Route.CreateProject) { inclusive = true }
                    }
                },
                onHelpClick = { navController.navigate(Route.Help) },
            )
        }

        composable<Route.JoinProject> {
            PlaceholderScreen(
                titleRes = R.string.project_action_join,
                onBack = navController::popBackStack,
            )
        }

        composable<Route.LinkedDevices> { entry ->
            val viewModel: LinkedDeviceViewModel = hiltViewModel()

            // The scanner hands its result back through this entry's SavedStateHandle
            // rather than through a shared singleton, so the code cannot leak to an
            // unrelated screen and survives process death with the back stack.
            val scannedCode by entry.savedStateHandle
                .getStateFlow<String?>(SCANNED_CODE_KEY, null)
                .collectAsStateWithLifecycle()

            LaunchedEffect(scannedCode) {
                scannedCode?.let { code ->
                    viewModel.onQrCodeScanned(code)
                    entry.savedStateHandle[SCANNED_CODE_KEY] = null
                }
            }

            LinkedDeviceRoute(
                onBack = navController::popBackStack,
                onLinkDevice = { navController.navigate(Route.ScanQrCode) },
                onHelpClick = { navController.navigate(Route.Help) },
                viewModel = viewModel,
            )
        }

        composable<Route.ScanQrCode> {
            QrScannerScreen(
                onCodeScanned = { code ->
                    // Hand the payload to whoever opened the scanner, then close. The
                    // scanner itself stays generic — it never knows what a code means.
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(SCANNED_CODE_KEY, code)
                    navController.popBackStack()
                },
                onBack = navController::popBackStack,
                onHelpClick = { navController.navigate(Route.Help) },
            )
        }

        composable<Route.ShareProjectCode> { entry ->
            val route = entry.toRoute<Route.ShareProjectCode>()
            ShareProjectRoute(
                projectName = route.projectName,
                projectCode = route.projectCode,
                onProceed = {
                    navController.navigate(Route.ProjectList) {
                        popUpTo(Route.ProjectList) { inclusive = true }
                    }
                },
                onHelpClick = { navController.navigate(Route.Help) },
            )
        }

        composable<Route.Dashboard> { entry ->
            val route = entry.toRoute<Route.Dashboard>()
            DashboardRoute(
                projectName = route.projectName,
                onChangeProject = {
                    // Back to the list to switch projects, clearing the dashboard so the
                    // next selection builds a fresh one rather than stacking.
                    navController.popBackStack(Route.ProjectList, inclusive = false)
                },
                onNotifications = { navController.navigate(Route.Notifications) },
                onSos = { navController.navigate(Route.Sos) },
                onHelp = { navController.navigate(Route.Help) },
                onOpenEventList = { kind ->
                    navController.navigate(Route.CalendarEvents(kind.name))
                },
                onCreateEvent = { dateMs ->
                    navController.navigate(Route.CalendarEventForm(selectedDateMs = dateMs))
                },
                onOpenCalendarSettings = { navController.navigate(Route.CalendarSettings) },
                onOpenSetting = { destination ->
                    // Three settings rows are not pages of their own: two reuse screens that
                    // already exist elsewhere in the app, and Update App leaves it entirely.
                    when (destination) {
                        SettingsDestination.APP_PREFERENCES -> navController.navigate(Route.AppPreferences)
                        SettingsDestination.LINKED_DEVICES -> navController.navigate(Route.LinkedDevices)
                        SettingsDestination.INVITE_USERS -> navController.navigate(Route.InviteUsers)
                        SettingsDestination.HELP -> navController.navigate(Route.Help)
                        SettingsDestination.EDIT_PROFILE -> navController.navigate(Route.EditProfile)
                        SettingsDestination.PRIVACY_PREFERENCES ->
                            navController.navigate(Route.PrivacyPreferences)
                        SettingsDestination.RECOVERY_CODE -> navController.navigate(Route.RecoveryCode)
                        SettingsDestination.ACCOUNT_SETTINGS -> navController.navigate(Route.AccountSettings)
                        SettingsDestination.CATERING_SETTINGS ->
                            navController.navigate(Route.CateringSettings)
                        SettingsDestination.ADMIN_SETTINGS -> navController.navigate(Route.AdminSettings)
                        SettingsDestination.LEAVE_PROJECT -> navController.navigate(Route.LeaveProject)
                        SettingsDestination.UPDATE_APP -> Unit
                    }
                },
            )
        }

        composable<Route.CalendarEvents> {
            EventListRoute(
                onBack = navController::popBackStack,
                onEdit = { event ->
                    navController.navigate(
                        Route.CalendarEventForm(
                            eventId = event.effectiveMasterEventId,
                            occurrenceStartMs = event.startDatetime,
                        ),
                    )
                },
            )
        }

        composable<Route.CalendarSettings> {
            CalendarSettingsRoute(onBack = navController::popBackStack)
        }

        // Settings destinations — all empty for now; the landing page is what is being
        // built. See SettingsPlaceholders.
        composable<Route.AppPreferences> {
            AppPreferencesRoute(onBack = navController::popBackStack)
        }
        composable<Route.InviteUsers> {
            InviteUsersScreen(onBack = navController::popBackStack)
        }
        composable<Route.EditProfile> { EditProfileScreen(onBack = navController::popBackStack) }
        composable<Route.PrivacyPreferences> {
            PrivacyPreferencesScreen(onBack = navController::popBackStack)
        }
        composable<Route.RecoveryCode> { RecoveryCodeScreen(onBack = navController::popBackStack) }
        composable<Route.AccountSettings> {
            AccountSettingsScreen(onBack = navController::popBackStack)
        }
        composable<Route.CateringSettings> {
            CateringSettingsScreen(onBack = navController::popBackStack)
        }
        composable<Route.AdminSettings> { AdminSettingsScreen(onBack = navController::popBackStack) }
        composable<Route.LeaveProject> { LeaveProjectScreen(onBack = navController::popBackStack) }

        composable<Route.CalendarEventForm> {
            EventFormEntry(
                onBack = navController::popBackStack,
                onSaved = navController::popBackStack,
            )
        }

        composable<Route.Notifications> {
            NotificationScreen(onBack = navController::popBackStack)
        }

        composable<Route.Sos> {
            PlaceholderScreen(
                titleRes = R.string.action_sos,
                onBack = navController::popBackStack,
            )
        }

        composable<Route.Help> {
            HelpScreen(onBack = navController::popBackStack)
        }
    }
}

/** Key the scanner writes its payload under, read by whichever screen opened it. */
private const val SCANNED_CODE_KEY = "scanned_qr_code"
