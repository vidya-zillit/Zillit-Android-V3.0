package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.help.HelpLinksFor
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * The ⓘ explanation, used everywhere in the app.
 *
 * Three things in one dialog, and each is optional:
 *  - the **explanation** itself;
 *  - **More**, appended to the last sentence as a link into the documentation site — v2
 *    splices it onto the message with a `ClickableSpan` for exactly this reason: it reads as
 *    a continuation of the sentence rather than a second control competing with the buttons;
 *  - **Watch Video**, when a tutorial was recorded for the topic.
 *
 * Both links are resolved per user before they get here — see
 * [com.zillit.zillitapp.core.help.HelpLinks] — so this composable never has to know whether
 * the reader is an admin.
 *
 * Opens links in the browser rather than an embedded WebView, matching what
 * [com.zillit.zillitapp.feature.help.ui.HelpScreen] already chose: these are shareable
 * pages and a full-screen video, both better handled by the device.
 */
@Composable
fun InfoDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    links: HelpLinksFor = HelpLinksFor(),
) {
    val uriHandler = LocalUriHandler.current

    val moreLabel = stringResource(R.string.more)
    val body = remember(message, links.moreUrl, moreLabel) {
        buildAnnotatedString {
            append(message)
            links.moreUrl?.let { url ->
                append(" ")
                withLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(textDecoration = TextDecoration.Underline),
                        ),
                    ),
                ) { append(moreLabel) }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = ZillitTheme.colors.surface,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = ZillitTheme.colors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.action_ok),
                    color = ZillitTheme.colors.brand,
                )
            }
        },
        dismissButton = links.videoUrl?.let { url ->
            {
                TextButton(
                    onClick = {
                        onDismiss()
                        runCatching { uriHandler.openUri(url) }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PlayCircleOutline,
                        contentDescription = null,
                        tint = ZillitTheme.colors.brand,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.watch_video),
                        color = ZillitTheme.colors.brand,
                        modifier = Modifier.padding(start = ZillitTheme.spacing.xs),
                    )
                }
            }
        },
    )
}
