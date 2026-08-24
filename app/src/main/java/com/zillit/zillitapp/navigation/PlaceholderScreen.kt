package com.zillit.zillitapp.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Stand-in for a screen that has not been built yet.
 *
 * Used so the Create/Join buttons on the empty project list lead somewhere real and the
 * back stack behaves correctly, rather than being dead controls. Replace each usage as
 * the screen lands — this file should end up deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
) {
    val title = stringResource(titleRes)
    Scaffold(
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitTopBar(
                title = title,
                onBackClick = onBack,
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            EmptyState(
                icon = Icons.Outlined.Construction,
                title = stringResource(R.string.placeholder_title, title),
                description = stringResource(R.string.placeholder_description),
            )
        }
    }
}
