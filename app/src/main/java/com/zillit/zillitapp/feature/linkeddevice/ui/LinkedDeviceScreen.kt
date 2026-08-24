package com.zillit.zillitapp.feature.linkeddevice.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.ErrorState
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.resolve
import com.zillit.zillitapp.core.common.toDateTimeLabel
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.toUiText
import com.zillit.zillitapp.core.ui.window.contentMaxWidth
import com.zillit.zillitapp.core.ui.window.currentWindowSize
import com.zillit.zillitapp.core.ui.window.horizontalPadding
import com.zillit.zillitapp.feature.linkeddevice.domain.LinkedDevice

@Composable
fun LinkedDeviceRoute(
    onBack: () -> Unit,
    onLinkDevice: () -> Unit,
    onHelpClick: () -> Unit,
    viewModel: LinkedDeviceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LinkedDeviceScreen(
        uiState = uiState,
        onBack = onBack,
        onHelpClick = onHelpClick,
        onLinkDevice = onLinkDevice,
        onDeviceClick = viewModel::onDeviceClick,
        onConfirmLogout = viewModel::confirmLogout,
        onDismissLogout = viewModel::dismissLogoutPrompt,
        onRetry = viewModel::refresh,
    )
}

/**
 * Linked devices — reached from the QR action, and later from Settings.
 *
 * Mirrors v2's `LinkedDevicePage`: the same explanatory copy, the same "Tap a device to
 * logout" affordance (the row itself is the logout control, there is no separate button),
 * and the same Link Device button that opens the scanner.
 */
@Composable
fun LinkedDeviceScreen(
    uiState: LinkedDeviceUiState,
    onBack: () -> Unit,
    onHelpClick: () -> Unit,
    onLinkDevice: () -> Unit,
    onDeviceClick: (LinkedDevice) -> Unit,
    onConfirmLogout: () -> Unit,
    onDismissLogout: () -> Unit,
    onRetry: () -> Unit,
) {
    val windowSize = currentWindowSize()
    val maxWidth = windowSize.contentMaxWidth()

    uiState.pendingLogout?.let { device ->
        LogoutConfirmationDialog(
            device = device,
            onConfirm = onConfirmLogout,
            onDismiss = onDismissLogout,
        )
    }

    Scaffold(
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitTopBar(
                title = stringResource(R.string.linked_device),
                onBackClick = onBack,
                onHelpClick = onHelpClick,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.isLoading -> LoadingState()

                uiState.error != null && uiState.devices.isEmpty() -> ErrorState(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.linked_device),
                    description = uiState.error.toUiText().resolve(),
                    onRetry = onRetry,
                    retryLabel = stringResource(R.string.action_retry),
                )

                else -> LinkedDeviceContent(
                    uiState = uiState,
                    maxWidth = maxWidth,
                    horizontalPadding = windowSize.horizontalPadding(),
                    onDeviceClick = onDeviceClick,
                    onLinkDevice = onLinkDevice,
                )
            }
        }
    }
}

@Composable
private fun LinkedDeviceContent(
    uiState: LinkedDeviceUiState,
    maxWidth: Dp,
    horizontalPadding: Dp,
    onDeviceClick: (LinkedDevice) -> Unit,
    onLinkDevice: () -> Unit,
) {
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (maxWidth != Dp.Unspecified) {
                            Modifier.widthIn(max = maxWidth)
                        } else {
                            Modifier
                        },
                    ),
                contentPadding = PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    bottom = ZillitTheme.spacing.lg,
                ),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                item { LinkedDeviceHeader() }

                if (uiState.devices.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.xxl)) {
                            EmptyState(
                                icon = Icons.Outlined.Devices,
                                title = stringResource(R.string.linked_device_empty_title),
                                description = stringResource(R.string.linked_device_empty_description),
                            )
                        }
                    }
                } else {
                    item { DeviceStatusHeading() }

                    items(items = uiState.devices, key = { it.id }) { device ->
                        LinkedDeviceRow(device = device, onClick = { onDeviceClick(device) })
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.background)
                .padding(horizontal = horizontalPadding, vertical = ZillitTheme.spacing.md),
        ) {
            PrimaryButton(
                text = stringResource(R.string.link_a_device),
                onClick = onLinkDevice,
                loading = uiState.isMutating,
            )
        }
    }
}

/** The illustration plus the "before you click Link Device…" explainer, as in v2. */
@Composable
private fun LinkedDeviceHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Image(
            painter = painterResource(R.drawable.mobile_laptop_sync),
            contentDescription = null,
            modifier = Modifier.size(120.dp),
        )

        Text(
            text = stringResource(R.string.use_zillit_on_web_tab_and_other_devices),
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.fillMaxWidth(),
        )

        // Steps copy is authored as HTML in strings.xml (bold step labels), so it is
        // parsed rather than shown with tags visible.
        Text(
            text = HtmlCompat.fromHtml(
                stringResource(R.string.steps_to_link_txt),
                HtmlCompat.FROM_HTML_MODE_COMPACT,
            ).toString().trim(),
            style = MaterialTheme.typography.bodySmall,
            color = ZillitTheme.colors.textTertiary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeviceStatusHeading() {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Text(
            text = HtmlCompat.fromHtml(
                stringResource(R.string.tap_to_logout),
                HtmlCompat.FROM_HTML_MODE_COMPACT,
            ).toString(),
            style = MaterialTheme.typography.titleSmall,
            color = ZillitTheme.colors.textPrimary,
        )
        Text(
            text = stringResource(R.string.tap_to_logout_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun LinkedDeviceRow(
    device: LinkedDevice,
    onClick: () -> Unit,
) {
    val lastActivity = stringResource(
        R.string.last_actvivity_value,
        remember(device.lastActivity) { formatTimestamp(device.lastActivity) },
    )
    val rowDescription = stringResource(R.string.cd_linked_device, device.displayName, lastActivity)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZillitTheme.shapes.large))
            .background(ZillitTheme.colors.surface)
            .border(
                width = 1.dp,
                color = ZillitTheme.colors.border,
                shape = RoundedCornerShape(ZillitTheme.shapes.large),
            )
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.lg)
            .clearAndSetSemantics { contentDescription = rowDescription },
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Same two icons v2 uses: phone for Android/iOS, laptop for everything else.
        Icon(
            painter = painterResource(
                if (device.isMobile) R.drawable.ic_mobile else R.drawable.ic_laptop,
            ),
            contentDescription = null,
            tint = ZillitTheme.colors.textSecondary,
            modifier = Modifier.size(28.dp),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            Text(
                text = device.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = lastActivity,
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Icon(
            painter = painterResource(R.drawable.ic_next),
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun LogoutConfirmationDialog(
    device: LinkedDevice,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ZillitConfirmDialog(
        title = stringResource(R.string.logout),
        message = stringResource(R.string.are_you_sure_you_want_to_logout_from_device),
        confirmLabel = stringResource(R.string.logout),
        dismissLabel = stringResource(R.string.cancel),
        isDestructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Matches v2's `DATE_FORMAT_TIME`. Locale-aware so it follows the app language. */
private fun formatTimestamp(millis: Long): String =
    millis.toDateTimeLabel()
