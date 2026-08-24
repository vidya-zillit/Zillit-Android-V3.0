package com.zillit.zillitapp.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.MainViewModel
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.preferences.ChatFontSize
import com.zillit.zillitapp.core.ui.theme.ThemeMode
import com.zillit.zillitapp.core.ui.components.ZillitChoiceDialog
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * App preferences — the reading settings, not account settings.
 *
 * Both entries here change how the whole app renders rather than what it shows, which is
 * why they live together and why they apply at the theme: a crew member who needs bigger
 * text needs it everywhere, not only inside a chat bubble.
 */
@Composable
fun AppPreferencesScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val fontSize by viewModel.chatFontSize.collectAsStateWithLifecycle()

    var pickingFontSize by remember { mutableStateOf(false) }
    var pickingTheme by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        PreferenceRow(
            icon = Icons.Outlined.FormatSize,
            title = stringResource(R.string.chat_font_size),
            value = stringResource(fontSize.labelRes()),
            onClick = { pickingFontSize = true },
        )

        PreferenceRow(
            icon = Icons.Outlined.DarkMode,
            title = stringResource(R.string.theme),
            value = stringResource(themeMode.labelRes()),
            onClick = { pickingTheme = true },
        )
    }

    if (pickingFontSize) {
        ZillitChoiceDialog(
            title = stringResource(R.string.chat_font_size),
            options = ChatFontSize.entries,
            selected = fontSize,
            label = { stringResource(it.labelRes()) },
            onSelected = {
                viewModel.setChatFontSize(it)
                pickingFontSize = false
            },
            onDismiss = { pickingFontSize = false },
        )
    }

    if (pickingTheme) {
        ZillitChoiceDialog(
            title = stringResource(R.string.theme),
            options = ThemeMode.entries,
            selected = themeMode,
            label = { stringResource(it.labelRes()) },
            onSelected = {
                viewModel.setThemeMode(it)
                pickingTheme = false
            },
            onDismiss = { pickingTheme = false },
        )
    }
}

@Composable
private fun PreferenceRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md)
            .clip(RoundedCornerShape(ZillitTheme.shapes.medium))
            .background(ZillitTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ZillitTheme.colors.textSecondary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = ZillitTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        // The current value sits on the row rather than only inside the dialog, so the
        // setting can be read without opening it.
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

private fun ChatFontSize.labelRes(): Int = when (this) {
    ChatFontSize.SMALL -> R.string.small
    ChatFontSize.MEDIUM -> R.string.medium
    ChatFontSize.LARGE -> R.string.large
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

/**
 * App Preferences as its own page.
 *
 * The screen itself was already built for the Settings tab, so this only gives it the frame
 * every other settings destination has — a title and a way back.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppPreferencesRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Scaffold(
        modifier = modifier,
        containerColor = ZillitTheme.colors.background,
        topBar = {
            com.zillit.zillitapp.core.ui.components.ZillitTopBar(
                title = androidx.compose.ui.res.stringResource(R.string.app_preferences),
                onBackClick = onBack,
                onHelpClick = null,
            )
        },
    ) { padding ->
        AppPreferencesScreen(modifier = Modifier.padding(padding))
    }
}
