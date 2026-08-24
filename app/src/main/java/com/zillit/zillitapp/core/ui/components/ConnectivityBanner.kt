package com.zillit.zillitapp.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import kotlinx.coroutines.delay

/**
 * App-wide connectivity banner.
 *
 * Hosted **once at the root**, above the nav host, so every screen in every module gets it
 * without opting in. v2 put a `noInternet` view inside each layout and toggled it from
 * each Activity, which is why it appeared on some screens and not others.
 *
 * Two behaviours worth noting:
 *  - It reports only *validated* connectivity, so a Wi-Fi network with no working uplink
 *    counts as offline — matching what the user actually experiences.
 *  - Coming back online shows a brief confirmation rather than the bar just vanishing:
 *    a silent disappearance leaves people unsure whether to retry.
 */
@Composable
fun ConnectivityBanner(
    isOnline: Boolean,
    modifier: Modifier = Modifier,
) {
    var showRestored by remember { mutableStateOf(false) }
    var wasOffline by remember { mutableStateOf(false) }

    LaunchedEffect(isOnline) {
        if (!isOnline) {
            wasOffline = true
            showRestored = false
        } else if (wasOffline) {
            // Only after a real outage — not on first launch, where the app has simply
            // always been online and a "Back online" flash would be noise.
            showRestored = true
            delay(RESTORED_VISIBLE_MILLIS)
            showRestored = false
            wasOffline = false
        }
    }

    AnimatedVisibility(
        visible = !isOnline || showRestored,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isOnline) ZillitTheme.colors.success else ZillitTheme.colors.danger,
                )
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isOnline) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(
                    if (isOnline) R.string.online_banner else R.string.offline_banner,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

private const val RESTORED_VISIBLE_MILLIS = 2_000L
