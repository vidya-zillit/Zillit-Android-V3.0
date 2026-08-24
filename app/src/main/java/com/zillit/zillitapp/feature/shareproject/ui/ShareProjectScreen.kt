package com.zillit.zillitapp.feature.shareproject.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.resolve
import com.zillit.zillitapp.core.ui.components.ZillitConfirmDialog
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.toUiText
import com.zillit.zillitapp.core.ui.window.contentMaxWidth
import com.zillit.zillitapp.core.ui.window.currentWindowSize
import com.zillit.zillitapp.core.ui.window.horizontalPadding
import kotlinx.coroutines.launch

@Composable
fun ShareProjectRoute(
    projectName: String,
    projectCode: String,
    onProceed: () -> Unit,
    onHelpClick: () -> Unit,
    viewModel: ShareProjectViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ShareProjectScreen(
        projectName = projectName,
        projectCode = projectCode,
        uiState = uiState,
        onProceed = onProceed,
        onHelpClick = onHelpClick,
        onRequestProceed = viewModel::requestProceed,
        onDismissProceed = viewModel::dismissProceedPrompt,
        onOpenRecovery = viewModel::openRecoverySheet,
        onDismissRecovery = viewModel::dismissRecoverySheet,
        onRecoveryEmailChange = viewModel::onRecoveryEmailChange,
        onSaveRecoveryEmail = viewModel::saveRecoveryEmail,
    )
}

/**
 * Shown once, immediately after a project is created — v2's `ShareProjectCodeActivity`.
 *
 * This is the only time the project code is presented, and it is what other people need
 * in order to join. So leaving is deliberately gated behind a confirmation, including on
 * back: a swipe that silently discards the code costs the user their invite path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareProjectScreen(
    projectName: String,
    projectCode: String,
    uiState: ShareProjectUiState,
    onProceed: () -> Unit,
    onHelpClick: () -> Unit,
    onRequestProceed: () -> Unit,
    onDismissProceed: () -> Unit,
    onOpenRecovery: () -> Unit,
    onDismissRecovery: () -> Unit,
    onRecoveryEmailChange: (String) -> Unit,
    onSaveRecoveryEmail: () -> Unit,
) {
    val context = LocalContext.current
    val windowSize = currentWindowSize()
    val maxWidth = windowSize.contentMaxWidth()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.code_copied)

    // Back raises the same prompt as Proceed rather than leaving silently.
    BackHandler { onRequestProceed() }

    if (uiState.isProceedPromptOpen) {
        // Both buttons proceed — Skip only declines the extra step, it does not cancel —
        // so they share one handler.
        ZillitConfirmDialog(
            title = null,
            message = stringResource(R.string.proceed_string_4),
            confirmLabel = stringResource(R.string.action_ok),
            dismissLabel = stringResource(R.string.action_skip),
            onConfirm = { onDismissProceed(); onProceed() },
            onDismiss = { onDismissProceed(); onProceed() },
            onCancel = onDismissProceed,
        )
    }

    if (uiState.isRecoverySheetOpen) {
        RecoveryEmailSheet(
            email = uiState.recoveryEmail,
            onEmailChange = onRecoveryEmailChange,
            onSave = onSaveRecoveryEmail,
            onDismiss = onDismissRecovery,
            canSave = uiState.canSaveRecoveryEmail,
            isSubmitting = uiState.isSubmitting,
            error = uiState.error,
        )
    }

    Scaffold(
        containerColor = ZillitTheme.colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ZillitTopBar(
                title = stringResource(R.string.share_project_code),
                onHelpClick = onHelpClick,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier)
                    .padding(
                        horizontal = windowSize.horizontalPadding(),
                        vertical = ZillitTheme.spacing.xl,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                Text(
                    text = projectName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = ZillitTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = stringResource(R.string.invite_code_text_2),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )

                // The code is the point of the screen, so it gets the visual weight.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            ZillitTheme.colors.brandSoft,
                            RoundedCornerShape(ZillitTheme.shapes.large),
                        )
                        .padding(ZillitTheme.spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.project_code_value, projectCode),
                        style = MaterialTheme.typography.headlineSmall,
                        color = ZillitTheme.colors.brand,
                        textAlign = TextAlign.Center,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    SecondaryButton(
                        text = stringResource(R.string.action_copy),
                        leadingIcon = Icons.Outlined.ContentCopy,
                        onClick = {
                            context.copyToClipboard(projectCode)
                            scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        text = stringResource(R.string.action_share),
                        leadingIcon = Icons.Outlined.Share,
                        onClick = {
                            context.shareText(
                                context.buildShareMessage(projectName, projectCode),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                SecondaryButton(
                    text = stringResource(R.string.recovery_email),
                    onClick = onOpenRecovery,
                )

                PrimaryButton(
                    text = stringResource(R.string.proceed),
                    onClick = onRequestProceed,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoveryEmailSheet(
    email: String,
    onEmailChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    canSave: Boolean,
    isSubmitting: Boolean,
    error: com.zillit.zillitapp.core.network.ApiError?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg)
                .padding(bottom = ZillitTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.recovery_email),
                style = MaterialTheme.typography.titleLarge,
                color = ZillitTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(R.string.recovery_email_description),
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )

            error?.let {
                Text(
                    text = it.toUiText().resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.danger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            ZillitTheme.colors.dangerSoft,
                            RoundedCornerShape(ZillitTheme.shapes.medium),
                        )
                        .padding(ZillitTheme.spacing.md),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        ZillitTheme.colors.surface,
                        RoundedCornerShape(ZillitTheme.shapes.medium),
                    )
                    .border(
                        1.dp,
                        ZillitTheme.colors.border,
                        RoundedCornerShape(ZillitTheme.shapes.medium),
                    )
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = 14.dp),
            ) {
                if (email.isEmpty()) {
                    Text(
                        text = stringResource(R.string.recovery_email_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textTertiary,
                    )
                }
                BasicTextField(
                    value = email,
                    onValueChange = onEmailChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = ZillitTheme.colors.textPrimary,
                    ),
                    cursorBrush = SolidColor(ZillitTheme.colors.brand),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = onSave,
                enabled = canSave,
                loading = isSubmitting,
            )
        }
    }
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("project_code", text))
}

private fun Context.shareText(text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, null))
}

/** Assembled from v2's four message parts so the invite text reads identically. */
private fun Context.buildShareMessage(projectName: String, projectCode: String): String =
    "${getString(R.string.share_code_msg_part1)} '$projectName' " +
        "${getString(R.string.share_code_msg_part2)} '$projectCode' " +
        "${getString(R.string.project_code)}.\n" +
        "${getString(R.string.share_code_msg_part3)} ${getString(R.string.click_here)} " +
        "$DOWNLOAD_LINK ${getString(R.string.share_code_msg_part4)} '$projectCode' " +
        getString(R.string.project_code)

private const val DOWNLOAD_LINK = "http://www.zillit.com/d"
