package com.zillit.zillitapp.feature.email.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.components.SettingsGroup
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.MailCredentials

/**
 * What an external mail client needs — v2's `mail_credentials_vw`.
 *
 * Seven read-only values and one action. The password is the only one that is **not** on
 * the profile: it is stored server-side as `enc:v1:…` ciphertext and fetched from
 * `imap-credentials/reveal` on an explicit tap, and every reveal is audit-logged. That is
 * why tapping the eye a second time *discards* the plaintext rather than just re-masking —
 * showing it again should mean another audited fetch, not a replay of a value the process
 * has been holding.
 *
 * The sheet marks its window `FLAG_SECURE` while open, so the values cannot be screenshotted
 * or captured by screen recording.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailCredentialsSheet(
    credentials: MailCredentials,
    revealedPassword: String?,
    revealing: Boolean,
    revealError: String?,
    canChangePassword: Boolean,
    onToggleReveal: () -> Unit,
    /** Returns the plaintext to copy, or null when it is not revealed. */
    onCopyPassword: () -> String?,
    onChangePassword: () -> Unit,
    onDismiss: () -> Unit,
) {
    SecureWhileVisible()

    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = ZillitTheme.spacing.lg,
                    end = ZillitTheme.spacing.lg,
                    bottom = ZillitTheme.spacing.xl,
                ),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.email_credentials),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ZillitTheme.colors.textPrimary,
            )

            CredentialSection(stringResource(R.string.email_address)) {
                CredentialRow(
                    label = stringResource(R.string.email_address),
                    value = credentials.emailAddress,
                    onCopy = { context.copyCredential(credentials.emailAddress) },
                )
            }

            CredentialSection(stringResource(R.string.credentials_text)) {
                CredentialRow(
                    label = stringResource(R.string.user_name),
                    value = credentials.username,
                    onCopy = { context.copyCredential(credentials.username) },
                )
                HorizontalDivider(color = ZillitTheme.colors.divider)
                PasswordRow(
                    revealed = revealedPassword,
                    revealing = revealing,
                    error = revealError,
                    onToggleReveal = onToggleReveal,
                    // Copy copies. It used to be wired to the same handler as the eye, so
                    // the clipboard icon revealed the password and never copied anything.
                    onCopy = { onCopyPassword()?.let { context.copyCredential(it) } },
                )
            }

            // Hidden on the shared Accounts mailbox: its password is not any one person's
            // to change, and v2 hides the control there for the same reason.
            if (canChangePassword) {
                SecondaryButton(
                    text = stringResource(R.string.change_password_txt),
                    onClick = onChangePassword,
                )
            }

            CredentialSection(stringResource(R.string.smtp_text)) {
                CredentialRow(
                    label = stringResource(R.string.host_txt),
                    value = credentials.smtpHost,
                    onCopy = { context.copyCredential(credentials.smtpHost) },
                )
                HorizontalDivider(color = ZillitTheme.colors.divider)
                CredentialRow(
                    label = stringResource(R.string.port_txt),
                    value = credentials.smtpPort.toString(),
                    onCopy = { context.copyCredential(credentials.smtpPort.toString()) },
                )
            }

            CredentialSection(stringResource(R.string.imap_text)) {
                CredentialRow(
                    label = stringResource(R.string.host_txt),
                    value = credentials.imapHost,
                    onCopy = { context.copyCredential(credentials.imapHost) },
                )
                HorizontalDivider(color = ZillitTheme.colors.divider)
                CredentialRow(
                    label = stringResource(R.string.port_txt),
                    value = credentials.imapPort.toString(),
                    onCopy = { context.copyCredential(credentials.imapPort.toString()) },
                )
            }
        }
    }
}

@Composable
private fun CredentialSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.textTertiary,
        )
        SettingsGroup { content() }
    }
}

@Composable
private fun CredentialRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = stringResource(R.string.copy_success),
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onCopy),
        )
    }
}

@Composable
private fun PasswordRow(
    revealed: String?,
    revealing: Boolean,
    error: String?,
    onToggleReveal: () -> Unit,
    onCopy: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.password_txt),
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
            Text(
                text = revealed ?: stringResource(R.string.password_masked_txt),
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (revealing) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = ZillitTheme.colors.brand,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(
                    imageVector = if (revealed != null) {
                        Icons.Outlined.VisibilityOff
                    } else {
                        Icons.Outlined.Visibility
                    },
                    contentDescription = stringResource(
                        if (revealed != null) {
                            R.string.hide_password_txt
                        } else {
                            R.string.show_password_txt
                        },
                    ),
                    tint = ZillitTheme.colors.brand,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onToggleReveal),
                )
            }

            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.copy_success),
                tint = ZillitTheme.colors.textTertiary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onCopy),
            )
        }

        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
                modifier = Modifier.padding(
                    start = ZillitTheme.spacing.md,
                    bottom = ZillitTheme.spacing.sm,
                ),
            )
        }
    }
}

/**
 * Blocks screenshots and screen recording for as long as this is composed.
 *
 * Scoped to the sheet rather than set on the Activity for its lifetime: `FLAG_SECURE` also
 * blanks the app in the recents switcher, and leaving it on would do that to every screen.
 */
@Composable
private fun SecureWhileVisible() {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * Copies a credential, marked sensitive.
 *
 * `EXTRA_IS_SENSITIVE` keeps the value out of the clipboard preview Android 13+ shows after
 * a copy — without it, tapping copy on the password displays it in a toast to the room.
 */
fun Context.copyCredential(value: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(null, value).apply {
        description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}
