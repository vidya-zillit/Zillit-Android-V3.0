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
import com.zillit.zillitapp.feature.cnc.ui.CncGroupInfoRoute
import com.zillit.zillitapp.feature.cnc.ui.CncProfileRoute
import com.zillit.zillitapp.feature.cnc.ui.CncThreadRoute
import com.zillit.zillitapp.feature.cnc.ui.thread.CncThreadArgs
import com.zillit.zillitapp.feature.cnc.ui.CreateGroupRoute
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
import com.zillit.zillitapp.feature.email.ui.calendar.EmailCalendarScreen
import com.zillit.zillitapp.feature.email.ui.EmailSignaturesRoute
import com.zillit.zillitapp.feature.email.ui.EmailSignatureEditorRoute
import com.zillit.zillitapp.feature.email.ui.EmailSettingsRoute
import com.zillit.zillitapp.feature.email.ui.EmailSearchRoute
import com.zillit.zillitapp.feature.email.ui.EmailRulesRoute
import com.zillit.zillitapp.feature.email.ui.EmailRuleExecutionsRoute
import com.zillit.zillitapp.feature.email.ui.EmailRuleEditorRoute
import com.zillit.zillitapp.feature.email.ui.EmailGroupsRoute
import com.zillit.zillitapp.feature.email.ui.EmailGroupEditorRoute
import com.zillit.zillitapp.feature.email.ui.EmailFolderPickerRoute
import com.zillit.zillitapp.feature.tools.ui.CustomizeToolsRoute
import com.zillit.zillitapp.feature.tools.ui.ManageToolGroupsRoute
import com.zillit.zillitapp.feature.email.ui.EmailDetailRoute
import com.zillit.zillitapp.feature.email.ui.detail.EmailDetailArgs
import com.zillit.zillitapp.feature.email.ui.EmailContactsRoute
import com.zillit.zillitapp.feature.email.ui.EmailContactEditorRoute
import com.zillit.zillitapp.feature.email.ui.EmailComposeRoute
import com.zillit.zillitapp.feature.email.ui.compose.EmailComposeArgs
import java.time.ZoneId
import com.zillit.zillitapp.feature.calendar.ui.CalendarRoute
import com.zillit.zillitapp.feature.email.ui.EmailReadByRoute
import com.zillit.zillitapp.feature.email.ui.BccPresetsRoute
import com.zillit.zillitapp.feature.cnc.ui.CncLibraryRoute

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

    /**
     * Switching the project's tools on and off. Admin only.
     *
     * A destination of its own rather than a sheet on the Tools tab: it is a different list
     * from a different endpoint, and leaving it has to be able to take the user back to the
     * tab with the tiles already refreshed.
     */
    @Serializable
    data object CustomizeTools : Route

    /** The sections themselves, and which tool is in which. Admin only. */
    @Serializable
    data object ManageToolGroups : Route

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

    /**
     * One C&C conversation — direct or group.
     *
     * A destination rather than a screen swapped inside the C&C tab, so the project bar and
     * the tab bar are off it, its header is the only bar on the page, and back behaves the
     * same as it does everywhere else.
     */
    @Serializable
    data class CncThread(val conversationId: String, val isGroup: Boolean) : Route

    /**
     * Naming a group and picking who is in it — or, with [roomId] set, adding people to one
     * that already exists.
     *
     * One destination for both because it is the same list of people either way. The
     * argument is read off the back-stack entry by the view model, which is why it can be
     * optional without a second route.
     */
    @Serializable
    data class CncCreateGroup(val roomId: String = "") : Route

    /** One person's details. */
    @Serializable
    data class CncProfile(val userId: String) : Route

    /** One group's details and its members. */
    @Serializable
    data class CncGroupInfo(val roomId: String) : Route

    /**
     * Media, Docs and Links for one conversation, as a destination of its own.
     *
     * The thread shows the same gallery as an overlay. This exists because the details
     * screen offers Media and Files as well, and it is a sibling of the thread rather than
     * a child of it — popping back to the thread and asking it to open an overlay would
     * mean one screen reaching into another's state.
     *
     * Its arguments are named exactly as [CncThread]'s are on purpose: the thread's view
     * model reads `conversationId` and `isGroup` off the back-stack entry, so this
     * destination gets the whole feed, already mapped, with no second derivation of it.
     */
    @Serializable
    data class CncLibrary(
        val conversationId: String,
        val isGroup: Boolean,
        /** `true` opens on Docs, for the Files shortcut. */
        val documentsFirst: Boolean = false,
    ) : Route
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
                pickedEmailFolder = entry.folderPickerResult(),
                onEmailFolderHandled = { entry.clearFolderPickerResult() },
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
                // Mail. The list lives in the Email tab; everything it can open is a
                // destination here, so a message survives the process being killed behind
                // it and back behaves the same as anywhere else in the app.
                onOpenEmail = { emailId, folderName, threadId ->
                    navController.navigate(EmailDetail(emailId, folderName, threadId))
                },
                onComposeEmail = { draftId ->
                    navController.navigate(
                        if (draftId == null) {
                            EmailCompose()
                        } else {
                            EmailCompose(mode = EmailCompose.MODE_EDIT_DRAFT, draftId = draftId)
                        },
                    )
                },
                onSearchEmail = { navController.navigate(EmailSearch) },
                onPickEmailFolder = { source ->
                    navController.navigate(EmailFolderPicker(source))
                },
                onOpenEmailSettings = { navController.navigate(EmailSettings) },
                onCustomizeTools = { navController.navigate(Route.CustomizeTools) },
                // Chat & Calling. Everything the tab can open is a destination, so a
                // conversation is a page rather than a panel inside the dashboard.
                onOpenConversation = { id, isGroup ->
                    navController.navigate(Route.CncThread(id, isGroup))
                },
                onOpenCncProfile = { navController.navigate(Route.CncProfile(it)) },
                onCreateCncGroup = { navController.navigate(Route.CncCreateGroup()) },
                onOpenEmailContacts = { navController.navigate(EmailContacts) },
                onOpenEmailCalendar = { navController.navigate(EmailCalendar) },
                onEmailReadBy = { emailId, folderName ->
                    navController.navigate(EmailReadBy(emailId, folderName))
                },
                // The reading pane's reply and add-contact go to the same destinations the
                // phone's detail page uses, so the composer is one screen however it opened.
                onEmailReply = { mode, emailId, folderName ->
                    navController.navigate(
                        EmailCompose(mode = mode, sourceEmailId = emailId, sourceFolderName = folderName),
                    )
                },
                onEmailAddContact = { address, name ->
                    navController.navigate(EmailContactEditor(prefillEmail = address, prefillName = name))
                },
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

        // ── Email ────────────────────────────────────────────────────────────
        composable<EmailDetail> { entry ->
            EmailDetailRoute(
                args = EmailDetailArgs.from(entry.toRoute<EmailDetail>()),
                onBack = navController::popBackStack,
                pickedFolder = entry.folderPickerResult(),
                onFolderHandled = { entry.clearFolderPickerResult() },
                onReply = { mode, emailId, folderName ->
                    navController.navigate(
                        EmailCompose(
                            mode = mode,
                            sourceEmailId = emailId,
                            sourceFolderName = folderName,
                        ),
                    )
                },
                onPickFolder = { source -> navController.navigate(EmailFolderPicker(source)) },
                onReadBy = { emailId, folderName ->
                    navController.navigate(EmailReadBy(emailId, folderName))
                },
                onAddContact = { address, name ->
                    navController.navigate(
                        EmailContactEditor(prefillEmail = address, prefillName = name),
                    )
                },
            )
        }

        composable<EmailCompose> { entry ->
            EmailComposeRoute(
                args = EmailComposeArgs.from(entry.toRoute<EmailCompose>()),
                onClose = navController::popBackStack,
            )
        }

        composable<EmailSearch> {
            EmailSearchRoute(
                onBack = navController::popBackStack,
                onOpenDetail = { emailId, folderName ->
                    navController.navigate(EmailDetail(emailId, folderName))
                },
            )
        }

        composable<EmailFolderPicker> { entry ->
            val route = entry.toRoute<EmailFolderPicker>()
            EmailFolderPickerRoute(
                sourceFolder = route.sourceFolderName,
                // The answer goes to the entry that opened the picker, which is the one
                // holding the selection. Handing it back through a callback instead would
                // lose it if the process were killed while the picker was up.
                onPicked = { folder ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(EmailFolderPicker.RESULT_FOLDER, folder)
                    navController.popBackStack()
                },
                onBack = navController::popBackStack,
            )
        }

        // ── Chat & Calling ────────────────────────────────────────────────
        // The thread takes its arguments rather than reading the back-stack entry, because
        // the same screen is also the right-hand pane beside the conversation list on a
        // wide window, where there is no route to read.
        composable<Route.CncThread> { entry ->
            val route = entry.toRoute<Route.CncThread>()
            CncThreadRoute(
                args = CncThreadArgs(route.conversationId, route.isGroup),
                onBack = navController::popBackStack,
                onOpenDetails = { id, isGroup ->
                    navController.navigate(
                        if (isGroup) Route.CncGroupInfo(id) else Route.CncProfile(id),
                    )
                },
            )
        }

        composable<Route.CncCreateGroup> {
            CreateGroupRoute(onBack = navController::popBackStack)
        }

        composable<Route.CncLibrary> { entry ->
            val route = entry.toRoute<Route.CncLibrary>()
            CncLibraryRoute(
                documentsFirst = route.documentsFirst,
                onBack = navController::popBackStack,
            )
        }

        composable<Route.CncProfile> { entry ->
            val route = entry.toRoute<Route.CncProfile>()
            CncProfileRoute(
                userId = route.userId,
                onBack = navController::popBackStack,
                onOpenLibrary = { documentsFirst ->
                    navController.navigate(
                        Route.CncLibrary(
                            conversationId = route.userId,
                            isGroup = false,
                            documentsFirst = documentsFirst,
                        ),
                    )
                },
                // Messaging from a profile you reached *from* that conversation must go
                // back to it rather than stack a second copy on top of the first.
                onMessage = { id ->
                    navController.navigate(Route.CncThread(id, isGroup = false)) {
                        popUpTo(Route.CncThread(id, isGroup = false)) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable<Route.CncGroupInfo> { entry ->
            val route = entry.toRoute<Route.CncGroupInfo>()
            CncGroupInfoRoute(
                roomId = route.roomId,
                onBack = navController::popBackStack,
                onMessage = { id ->
                    navController.navigate(Route.CncThread(id, isGroup = true)) {
                        popUpTo(Route.CncThread(id, isGroup = true)) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onMember = { navController.navigate(Route.CncProfile(it)) },
                onAddMembers = { navController.navigate(Route.CncCreateGroup(route.roomId)) },
                onOpenLibrary = { documentsFirst ->
                    navController.navigate(
                        Route.CncLibrary(
                            conversationId = route.roomId,
                            isGroup = true,
                            documentsFirst = documentsFirst,
                        ),
                    )
                },
            )
        }

        composable<Route.CustomizeTools> {
            CustomizeToolsRoute(
                onBack = navController::popBackStack,
                onManageGroups = { navController.navigate(Route.ManageToolGroups) },
            )
        }

        composable<Route.ManageToolGroups> {
            ManageToolGroupsRoute(onBack = navController::popBackStack)
        }

        composable<EmailSettings> {
            EmailSettingsRoute(
                onBack = navController::popBackStack,
                onSignatures = { navController.navigate(EmailSignatures) },
                onGroups = { navController.navigate(EmailGroups) },
                onRules = { navController.navigate(EmailRules) },
                onBccPresets = { navController.navigate(EmailBccPresets) },
            )
        }

        composable<EmailBccPresets> {
            BccPresetsRoute(onBack = navController::popBackStack)
        }

        composable<EmailSignatures> {
            EmailSignaturesRoute(
                onBack = navController::popBackStack,
                onEdit = { navController.navigate(EmailSignatureEditor(it)) },
            )
        }

        composable<EmailSignatureEditor> { entry ->
            EmailSignatureEditorRoute(
                signatureId = entry.toRoute<EmailSignatureEditor>().signatureId,
                onBack = navController::popBackStack,
            )
        }

        composable<EmailGroups> {
            EmailGroupsRoute(
                onBack = navController::popBackStack,
                onEdit = { navController.navigate(EmailGroupEditor(it)) },
            )
        }

        composable<EmailGroupEditor> { entry ->
            EmailGroupEditorRoute(
                groupId = entry.toRoute<EmailGroupEditor>().groupId,
                onBack = navController::popBackStack,
            )
        }

        composable<EmailContacts> {
            EmailContactsRoute(
                onBack = navController::popBackStack,
                onEdit = { navController.navigate(EmailContactEditor(contactId = it)) },
                onCompose = { address ->
                    navController.navigate(EmailCompose(prefillTo = address))
                },
            )
        }

        composable<EmailContactEditor> { entry ->
            val route = entry.toRoute<EmailContactEditor>()
            EmailContactEditorRoute(
                contactId = route.contactId,
                prefillEmail = route.prefillEmail,
                prefillName = route.prefillName,
                onBack = navController::popBackStack,
            )
        }

        composable<EmailRules> {
            EmailRulesRoute(
                onBack = navController::popBackStack,
                onEdit = { navController.navigate(EmailRuleEditor(it)) },
                onHistory = { ruleId, ruleName ->
                    navController.navigate(EmailRuleExecutions(ruleId, ruleName))
                },
            )
        }

        composable<EmailRuleEditor> { entry ->
            EmailRuleEditorRoute(
                ruleId = entry.toRoute<EmailRuleEditor>().ruleId,
                onBack = navController::popBackStack,
            )
        }

        composable<EmailRuleExecutions> { entry ->
            val route = entry.toRoute<EmailRuleExecutions>()
            EmailRuleExecutionsRoute(
                ruleId = route.ruleId,
                ruleName = route.ruleName,
                onBack = navController::popBackStack,
            )
        }

        composable<EmailReadBy> {
            EmailReadByRoute(onBack = navController::popBackStack)
        }

        composable<EmailCalendar> {
            EmailCalendarScreen(onBack = navController::popBackStack) {
                // The project calendar, with no unit strip: reached from the email drawer,
                // where there is no unit context to switch between. v2 drops the same
                // shared calendar in here and adds nothing email-specific either.
                CalendarRoute(
                    units = emptyList(),
                    selectedUnitId = null,
                    onUnitSelected = { },
                    onCreateEvent = { date ->
                        navController.navigate(
                            Route.CalendarEventForm(
                                selectedDateMs = date.atStartOfDay(ZoneId.systemDefault())
                                    .toInstant().toEpochMilli(),
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** Key the scanner writes its payload under, read by whichever screen opened it. */
private const val SCANNED_CODE_KEY = "scanned_qr_code"
