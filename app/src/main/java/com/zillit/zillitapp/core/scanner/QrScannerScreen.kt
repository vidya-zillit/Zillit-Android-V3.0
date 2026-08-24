package com.zillit.zillitapp.core.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Full-screen scanner, built on the reusable [QrScannerView].
 *
 * Used by the project list today and by Settings' linked-devices flow later. Because the
 * viewfinder is a component, a caller that needs scanning *inside* another screen (a
 * bottom sheet, a step in a wizard) can embed [QrScannerView] directly instead of
 * navigating here — see also [QrScannerDialog].
 */
@Composable
fun QrScannerScreen(
    onCodeScanned: (String) -> Unit,
    onBack: () -> Unit,
    onHelpClick: (() -> Unit)? = null,
) {
    val permission = rememberCameraPermissionState()

    Scaffold(
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitTopBar(
                title = stringResource(R.string.project_scan_qr),
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
                permission.isGranted -> ScannerContent(onCodeScanned = onCodeScanned)

                permission.isDenied -> EmptyState(
                    icon = Icons.Outlined.NoPhotography,
                    title = stringResource(R.string.scanner_permission_title),
                    description = stringResource(R.string.scanner_permission_description),
                )

                // Permission dialog is up — render nothing rather than flashing the
                // denial state behind the system prompt.
                else -> Unit
            }
        }
    }
}

@Composable
private fun ScannerContent(onCodeScanned: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(ZillitTheme.spacing.lg)
                .clip(RoundedCornerShape(ZillitTheme.shapes.large))
                .background(ZillitTheme.colors.surfaceSunken),
        ) {
            QrScannerView(onCodeScanned = onCodeScanned)
        }

        Text(
            text = stringResource(R.string.scanner_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ZillitTheme.spacing.xl,
                    vertical = ZillitTheme.spacing.xl,
                ),
        )
    }
}

/**
 * Scanner in a dialog, for flows that should not lose their place — Settings' device
 * linking scans a code and stays on the settings screen underneath.
 */
@Composable
fun QrScannerDialog(
    onCodeScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val permission = rememberCameraPermissionState()

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .size(DIALOG_SIZE)
                .clip(RoundedCornerShape(ZillitTheme.shapes.large))
                .background(ZillitTheme.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (permission.isGranted) {
                QrScannerView(onCodeScanned = onCodeScanned)
            } else {
                Text(
                    text = stringResource(R.string.scanner_permission_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(ZillitTheme.spacing.lg),
                )
            }
        }
    }
}

private val DIALOG_SIZE = 320.dp
