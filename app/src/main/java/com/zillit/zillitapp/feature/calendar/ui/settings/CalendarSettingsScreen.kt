package com.zillit.zillitapp.feature.calendar.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.core.app.ActivityCompat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.calendar.sync.CalendarTarget
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Turning the device-calendar mirror on, and seeing whether it is working.
 *
 * The switch asks for the calendar permission at the moment it is flipped, not at launch:
 * a permission prompt makes sense when the user has just asked for the thing that needs it,
 * and makes the app look intrusive at any other time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var pickerOpen by remember { mutableStateOf(false) }

    // The permission can change in Android's settings while this screen is away, so the
    // state is re-derived on every return rather than trusted from before.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val context = LocalContext.current

    // Android stops showing the dialog after a second refusal. The launcher then returns
    // immediately with a denial, which is indistinguishable from a tap on "Don't allow" —
    // except that asking again will never work, so the screen must offer the OS page.
    var permissionPromptBlocked by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // Both are needed: reading finds the calendar, writing puts events in it.
        if (granted.values.all { it }) {
            permissionPromptBlocked = false
            viewModel.setEnabled(true)
        } else {
            permissionPromptBlocked = !context.canAskForCalendarPermission()
            viewModel.onResumed()
        }
    }

    Scaffold(
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitTopBar(
                title = stringResource(R.string.calendar_settings),
                onBackClick = onBack,
                onHelpClick = null,
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.sync_section_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.padding(top = ZillitTheme.spacing.lg),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (state.isEnabled) {
                            viewModel.setEnabled(false)
                        } else {
                            permissionLauncher.launch(CALENDAR_PERMISSIONS)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sync_toggle_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.isEnabled,
                    onCheckedChange = { wantsOn ->
                        if (wantsOn) {
                            permissionLauncher.launch(CALENDAR_PERMISSIONS)
                        } else {
                            viewModel.setEnabled(false)
                        }
                    },
                )
            }

            Text(
                text = stringResource(R.string.sync_toggle_summary),
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )

            HealthBanner(
                health = state.health,
                onGrant = { permissionLauncher.launch(CALENDAR_PERMISSIONS) },
                onRetry = viewModel::syncNow,
                onOpenSystemSettings = { context.openAppSettings() },
                // Once Android stops showing the prompt, the in-app request does nothing
                // and the OS settings page is the only way back.
                promptBlocked = permissionPromptBlocked,
            )

            if (state.isEnabled) {
                HorizontalDivider(color = ZillitTheme.colors.divider)

                state.target?.let { target ->
                    Text(
                        text = stringResource(R.string.sync_target, target.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }

                SecondaryButton(
                    text = stringResource(R.string.sync_change_calendar),
                    onClick = { pickerOpen = true },
                    enabled = state.availableCalendars.isNotEmpty(),
                )

                SecondaryButton(
                    text = stringResource(R.string.sync_action_sync_now),
                    onClick = viewModel::syncNow,
                )

                SecondaryButton(
                    text = stringResource(R.string.sync_action_reset),
                    onClick = viewModel::resetAndResync,
                )
            }

            // Always available, enabled or not: "it will not turn on" is exactly the
            // question the report answers.
            SecondaryButton(
                text = stringResource(R.string.sync_action_diagnose),
                onClick = viewModel::gatherDiagnostics,
                modifier = Modifier.padding(bottom = ZillitTheme.spacing.xl),
            )
        }
    }

    report?.let { text ->
        DiagnosticsSheet(
            report = text,
            onCopy = {
                clipboard.setText(AnnotatedString(text))
                viewModel.dismissDiagnostics()
            },
            onDismiss = viewModel::dismissDiagnostics,
        )
    }

    if (pickerOpen) {
        CalendarPickerSheet(
            calendars = state.availableCalendars,
            selectedId = state.target?.id,
            onPick = {
                pickerOpen = false
                viewModel.selectCalendar(it)
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * One line saying whether the mirror is keeping up.
 *
 * Says what to do about it when there is something to do — a status that only reports a
 * problem leaves the user stuck.
 */
@Composable
private fun HealthBanner(
    health: SyncHealth,
    onGrant: () -> Unit,
    onRetry: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    /** True once Android will no longer show the permission dialog. */
    promptBlocked: Boolean,
) {
    if (health is SyncHealth.Off) return

    val (text, color) = when (health) {
        is SyncHealth.Healthy ->
            stringResource(R.string.sync_status_healthy) to ZillitTheme.colors.success

        is SyncHealth.Working ->
            stringResource(R.string.sync_status_pending, health.pending) to
                ZillitTheme.colors.textSecondary

        is SyncHealth.Failing ->
            stringResource(R.string.sync_status_failed, health.failed) to ZillitTheme.colors.danger

        is SyncHealth.NeedsPermission ->
            stringResource(R.string.sync_status_permission) to ZillitTheme.colors.warning

        is SyncHealth.NoCalendar ->
            stringResource(R.string.sync_status_no_calendar) to ZillitTheme.colors.warning

        SyncHealth.Off -> return
    }

    Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.12f)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)

            when (health) {
                // Once the prompt is blocked, asking again does nothing — the OS page is
                // the only route, so that is what the button offers.
                is SyncHealth.NeedsPermission -> if (promptBlocked) {
                    Text(
                        text = stringResource(R.string.sync_permission_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                    SecondaryButton(
                        text = stringResource(R.string.sync_action_open_settings),
                        onClick = onOpenSystemSettings,
                    )
                } else {
                    SecondaryButton(
                        text = stringResource(R.string.sync_action_grant),
                        onClick = onGrant,
                    )
                }

                is SyncHealth.Failing -> SecondaryButton(
                    text = stringResource(R.string.sync_action_retry),
                    onClick = onRetry,
                )

                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarPickerSheet(
    calendars: List<CalendarTarget>,
    selectedId: Long?,
    onPick: (CalendarTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg)) {
            Text(
                text = stringResource(R.string.sync_select_calendar),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm),
            )

            calendars.forEach { calendar ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(calendar) }
                        .padding(vertical = ZillitTheme.spacing.sm),
                ) {
                    Text(
                        text = calendar.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (calendar.id == selectedId) {
                            ZillitTheme.colors.brand
                        } else {
                            ZillitTheme.colors.textPrimary
                        },
                    )
                    Text(
                        text = calendar.accountName,
                        style = MaterialTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textTertiary,
                    )
                }
            }

            Column(modifier = Modifier.padding(bottom = ZillitTheme.spacing.xl)) {}
        }
    }
}

/**
 * The report, with a copy action.
 *
 * Copyable because the point is to paste it into a message to whoever can act on it —
 * reading a screenful of diagnostics aloud is not support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsSheet(report: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = ZillitTheme.spacing.lg)
                .padding(bottom = ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.sync_diagnose_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ZillitTheme.colors.textPrimary,
            )

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = ZillitTheme.colors.surfaceSunken,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(ZillitTheme.spacing.md),
                )
            }

            SecondaryButton(
                text = stringResource(R.string.sync_diagnose_copy),
                onClick = onCopy,
            )
        }
    }
}

/** Opens this app's page in Android settings, where the permission can be re-granted. */
private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Whether asking again would still show a dialog.
 *
 * False once Android has decided the user means it, which is the moment the in-app button
 * has to stop pretending it can help.
 */
private fun Context.canAskForCalendarPermission(): Boolean {
    val activity = findActivity() ?: return true
    return CALENDAR_PERMISSIONS.any {
        ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private val CALENDAR_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
)
