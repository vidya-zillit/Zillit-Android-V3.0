package com.zillit.zillitapp.feature.settings.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * The settings destinations that exist but are not built yet.
 *
 * Deliberately empty. The landing page is the piece being got right first, and a real screen
 * behind each row would be a second thing to review at the same time — but a row that goes
 * nowhere cannot be checked at all, so each one opens a page carrying its own title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPlaceholder(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = ZillitTheme.colors.background,
        topBar = { ZillitTopBar(title = stringResource(titleRes), onBackClick = onBack, onHelpClick = null) },
    ) { padding ->
        EmptyState(
            icon = Icons.Outlined.Construction,
            title = stringResource(titleRes),
            description = stringResource(R.string.settings_coming_soon),
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/**
 * Invite Users.
 *
 * A placeholder for now rather than a jump to the existing share-code screen: that screen
 * takes the project code as a route argument and nothing in the session carries it, so
 * reaching it from here means loading the project first — work that belongs to this page
 * when it is built, not to the landing page.
 */
@Composable
fun InviteUsersScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    SettingsPlaceholder(R.string.invite_users, onBack, modifier)

@Composable
fun EditProfileScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    SettingsPlaceholder(R.string.edit_my_profile, onBack, modifier)

@Composable
fun PrivacyPreferencesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    SettingsPlaceholder(R.string.consent_settings_entry, onBack, modifier)

@Composable
fun RecoveryCodeScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    SettingsPlaceholder(R.string.recovery_email_txt, onBack, modifier)

@Composable
fun AccountSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    SettingsPlaceholder(R.string.account_settings, onBack, modifier)

@Composable
fun CateringSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    SettingsPlaceholder(R.string.catering_settings, onBack, modifier)

@Composable
fun AdminSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    SettingsPlaceholder(R.string.admin_settings, onBack, modifier)

@Composable
fun LeaveProjectScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    SettingsPlaceholder(R.string.leave_this_project, onBack, modifier)
