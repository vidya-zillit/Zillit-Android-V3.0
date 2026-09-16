package com.zillit.zillitapp.feature.email.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.SettingsGroup
import com.zillit.zillitapp.core.ui.components.SettingsNavRow
import com.zillit.zillitapp.core.ui.components.SettingsRowDivider
import com.zillit.zillitapp.core.ui.components.SettingsSwitchRow
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Email settings — v2's `email_fragment_settings`.
 *
 * Same rows in the same order as v2, grouped rather than presented as seven identical
 * floating cards: the conversation toggle changes how *this* mailbox reads, the next three
 * manage things you compose with, and the last three are about mail arriving. Seven
 * undifferentiated cards is why v2's BCC Presets and Email Forwarding get confused for each
 * other.
 *
 * **Email Groups is admin-only**, which is v2's single gate on this screen. It is hidden
 * rather than disabled — a non-admin has no path to make it work, so an inert row would
 * only prompt a support question.
 */
@Composable
fun EmailSettingsScreen(
    conversationView: Boolean,
    isAdmin: Boolean,
    saving: Boolean,
    onBack: () -> Unit,
    onConversationViewChange: (Boolean) -> Unit,
    onSignatures: () -> Unit,
    onEmailGroups: () -> Unit,
    onBccPresets: () -> Unit,
    onEmailRules: () -> Unit,
    onEmailForwarding: () -> Unit,
    onExternalSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitTopBar(
                title = stringResource(R.string.email_settings),
                onBackClick = onBack,
                onHelpClick = null,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.email_conversation_view),
                    subtitle = stringResource(R.string.email_conversation_view_subtitle),
                    checked = conversationView,
                    onCheckedChange = onConversationViewChange,
                    // Disabled while the change is in flight, so a second tap cannot race
                    // the first and leave the switch disagreeing with the server.
                    enabled = !saving,
                )
            }

            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.email_signatures),
                    subtitle = stringResource(R.string.email_signatures_subtitle),
                    icon = Icons.Outlined.Draw,
                    onClick = onSignatures,
                )

                if (isAdmin) {
                    SettingsRowDivider()
                    SettingsNavRow(
                        title = stringResource(R.string.email_groups),
                        subtitle = stringResource(R.string.email_groups_subtitle),
                        icon = Icons.Outlined.Groups,
                        onClick = onEmailGroups,
                    )
                }

                SettingsRowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.email_bcc_presets),
                    subtitle = stringResource(R.string.email_bcc_presets_subtitle),
                    icon = Icons.Outlined.Mail,
                    onClick = onBccPresets,
                )
            }

            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.email_rules_title),
                    subtitle = stringResource(R.string.email_rules_card_subtitle),
                    icon = Icons.Outlined.FilterAlt,
                    onClick = onEmailRules,
                )

                SettingsRowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.email_forwarding),
                    subtitle = stringResource(R.string.email_forwarding_subtitle),
                    icon = Icons.AutoMirrored.Outlined.Forward,
                    onClick = onEmailForwarding,
                )

                SettingsRowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.email_credentials),
                    subtitle = stringResource(R.string.email_credentials_subtitle),
                    icon = Icons.Outlined.Laptop,
                    onClick = onExternalSetup,
                )
            }
        }
    }
}
