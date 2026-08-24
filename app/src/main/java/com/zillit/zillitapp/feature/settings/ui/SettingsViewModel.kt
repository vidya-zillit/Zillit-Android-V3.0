package com.zillit.zillitapp.feature.settings.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.BuildConfig
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.badge.BadgeKey
import com.zillit.zillitapp.core.badge.BadgeManager
import com.zillit.zillitapp.core.badge.BadgeSection
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.help.HelpLinks
import com.zillit.zillitapp.core.help.HelpTopic
import com.zillit.zillitapp.core.directory.ProjectUser
import com.zillit.zillitapp.core.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Where a settings row goes when it is tapped. */
enum class SettingsDestination {
    APP_PREFERENCES,
    LINKED_DEVICES,
    EDIT_PROFILE,
    INVITE_USERS,
    PRIVACY_PREFERENCES,
    RECOVERY_CODE,
    UPDATE_APP,
    HELP,
    ACCOUNT_SETTINGS,
    CATERING_SETTINGS,
    ADMIN_SETTINGS,
    LEAVE_PROJECT,
}

/**
 * One row on the settings page.
 *
 * @param infoRes the ⓘ blurb. Every row in v2 has one except Admin Settings, and it is the
 *   only explanation of what a setting does, so it is part of the row rather than a tooltip
 *   bolted on afterwards.
 * @param infoArg a value the blurb interpolates — the installed version, for Update App.
 * @param topic what the ⓘ explains. Decides which tutorial and which documentation page the
 *   dialog offers, per reader — see [com.zillit.zillitapp.core.help.HelpLinks].
 * @param badgeCount unread under this row — only Admin Settings has any.
 * @param showDot a soft marker with no number. v2 puts one on Update App when a newer
 *   version exists; nothing in v3 checks for one yet, so nothing sets it.
 */
data class SettingsRow(
    val destination: SettingsDestination,
    @StringRes val titleRes: Int,
    @StringRes val infoRes: Int?,
    val infoArg: String? = null,
    val topic: HelpTopic? = null,
    val badgeCount: Int = 0,
    val showDot: Boolean = false,
    val emphasis: RowEmphasis = RowEmphasis.NORMAL,
) {
    enum class RowEmphasis { NORMAL, ADMIN, DANGER }
}

data class SettingsUiState(
    val user: ProjectUser? = null,
    val rows: List<SettingsRow> = emptyList(),
    val isAdmin: Boolean = false,
)

/**
 * The settings page's contents.
 *
 * The list is **built from the user**, not fixed: which rows exist depends on whether they
 * are an admin, which department they are in, whether that department's tool is switched on,
 * and whether this phone already has a laptop linked. v2 rebuilds the same list from the
 * same inputs, so changing department — or being made an admin — changes this page while it
 * is open, with no reload.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val directory: ProjectDirectory,
    private val badgeManager: BadgeManager,
    private val helpLinks: HelpLinks,
    private val session: SessionStore,
) : ViewModel() {

    /**
     * The tutorial and documentation links for a row's ⓘ.
     *
     * Resolved on demand rather than baked into the row: both depend on who is reading, and
     * a row rebuilt for a badge change should not have to re-derive two URLs nobody opened.
     */
    fun linksFor(row: SettingsRow) = row.topic?.let(helpLinks::linksFor)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SettingsUiState> = session.activeProject
        .flatMapLatest { project ->
            if (project == null) return@flatMapLatest flowOf(SettingsUiState())

            combine(
                directory.observeUser(project.userId, project.projectId),
                directory.observeTools(project.projectId),
                directory.observeDepartments(project.projectId),
                // Admin Settings carries the approvals count — profile changes and new users
                // waiting on an admin. Same number the Settings tab shows, because it is the
                // same set of notifications.
                badgeManager.observeUnder(BadgeKey.section(project.projectId, BadgeSection.SETTINGS)),
            ) { user, tools, departments, settingsBadge ->
                val departmentIdentifier = departments
                    .firstOrNull { it.departmentId == user?.departmentId }
                    ?.identifier

                SettingsUiState(
                    user = user,
                    isAdmin = user?.isAdmin == true,
                    rows = buildRows(
                        isAdmin = user?.isAdmin == true,
                        departmentIdentifier = departmentIdentifier,
                        isToolEnabled = { identifier ->
                            tools.firstOrNull { it.identifier == identifier }?.enabled == true
                        },
                        settingsBadge = settingsBadge,
                    ),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), SettingsUiState())

    /**
     * @param isToolEnabled whether a tool is switched on for the project. A department's
     *   settings row is pointless when its tool is off — there would be nothing to configure.
     */
    private fun buildRows(
        isAdmin: Boolean,
        departmentIdentifier: String?,
        isToolEnabled: (String) -> Boolean,
        settingsBadge: Int,
    ): List<SettingsRow> {
        val rows = mutableListOf(
            SettingsRow(
                destination = SettingsDestination.APP_PREFERENCES,
                titleRes = R.string.app_preferences,
                infoRes = R.string.app_permission_info,
                topic = HelpTopic.APP_PREFERENCES,
            ),
            SettingsRow(
                destination = SettingsDestination.LINKED_DEVICES,
                titleRes = R.string.connect_to_zillit_from_your_laptop,
                infoRes = R.string.connect_to_zillit_info_admin_user,
                topic = HelpTopic.CONNECT_TO_ZILLIT,
            ),
            SettingsRow(
                destination = SettingsDestination.EDIT_PROFILE,
                titleRes = R.string.edit_my_profile,
                infoRes = R.string.edit_profile_info_admin_user,
                topic = HelpTopic.EDIT_MY_PROFILE,
            ),
            SettingsRow(
                destination = SettingsDestination.INVITE_USERS,
                titleRes = R.string.invite_users,
                infoRes = R.string.invite_info,
                topic = HelpTopic.INVITE_USERS,
            ),
            SettingsRow(
                destination = SettingsDestination.PRIVACY_PREFERENCES,
                titleRes = R.string.consent_settings_entry,
                infoRes = R.string.consent_settings_entry_info,
                topic = HelpTopic.PRIVACY_PREFERENCES,
            ),
            SettingsRow(
                destination = SettingsDestination.RECOVERY_CODE,
                titleRes = R.string.recovery_email_txt,
                infoRes = R.string.recovery_email_info,
                topic = HelpTopic.RECOVERY_CODE_EMAIL,
            ),
            SettingsRow(
                destination = SettingsDestination.UPDATE_APP,
                titleRes = R.string.update_app,
                infoRes = R.string.update_app_info_admin_user,
                topic = HelpTopic.UPDATE_APP,
                infoArg = APP_VERSION,
            ),
            SettingsRow(
                destination = SettingsDestination.HELP,
                titleRes = R.string.zillit_help,
                infoRes = R.string.tooltip_user_85,
                topic = HelpTopic.ZILLIT_HELP,
            ),
        )

        // The department's own settings. Accounts and Catering are the only two departments
        // that configure anything, and only when their tool is on.
        if (departmentIdentifier == DEPARTMENT_ACCOUNTS && isToolEnabled(TOOL_ACCOUNTS)) {
            rows += SettingsRow(
                destination = SettingsDestination.ACCOUNT_SETTINGS,
                titleRes = R.string.account_settings,
                infoRes = R.string.account_settings_info,
                topic = HelpTopic.ACCOUNT_SETTINGS,
            )
        }
        if (departmentIdentifier == DEPARTMENT_CATERING && isToolEnabled(TOOL_CATERING)) {
            rows += SettingsRow(
                destination = SettingsDestination.CATERING_SETTINGS,
                titleRes = R.string.catering_settings,
                infoRes = R.string.catering_settings_info,
                topic = HelpTopic.CATERING_SETTINGS,
            )
        }

        // Admin Settings and Leave are appended **after** the sort, so they stay at the
        // bottom whatever they are called — v2 does the same, and they are the two rows that
        // should not be hunted for in an alphabetical list.
        return rows.also { list ->
            if (isAdmin) {
                list += SettingsRow(
                    destination = SettingsDestination.ADMIN_SETTINGS,
                    titleRes = R.string.admin_settings,
                    infoRes = null,
                    topic = HelpTopic.ADMIN_SETTINGS,
                    badgeCount = settingsBadge,
                    emphasis = SettingsRow.RowEmphasis.ADMIN,
                )
            }
            list += SettingsRow(
                destination = SettingsDestination.LEAVE_PROJECT,
                titleRes = R.string.leave_this_project,
                // An admin is told the extra rule that applies to them: they cannot leave a
                // project they are the only admin of.
                infoRes = if (isAdmin) {
                    R.string.leave_project_info_admin
                } else {
                    R.string.leave_project_info_user
                },
                topic = HelpTopic.LEAVE_PROJECT,
                emphasis = SettingsRow.RowEmphasis.DANGER,
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L

        /** v2's `Constants.ACCOUNT_IDENTIFIER` / `CATERING_IDENTIFIER`. */
        const val DEPARTMENT_ACCOUNTS = "department_accounts"
        const val DEPARTMENT_CATERING = "department_catering"

        /** v2's `Constants.ACCOUNT_TOOL` / `CATERING_TOOL`. */
        const val TOOL_ACCOUNTS = "accounting_tool"
        const val TOOL_CATERING = "catering_tool"

        val APP_VERSION: String = BuildConfig.VERSION_NAME
    }
}
