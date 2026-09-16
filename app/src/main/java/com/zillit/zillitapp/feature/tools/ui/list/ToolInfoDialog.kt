package com.zillit.zillitapp.feature.tools.ui.list

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.openLink
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * What a tile's ⓘ opens.
 *
 * Two buttons and one inline link, as v2 has: dismiss, watch the tutorial, and a "More"
 * appended to the end of the explainer that opens the help page.
 *
 * "More" is part of the sentence rather than a third button because that is what it is —
 * the explainer is a summary, and the link continues it. Both links come from the tool's
 * spec, so a tool with neither gets a plain acknowledgement rather than controls that go
 * nowhere; v2 offers a video on every tool and several of them open the same placeholder.
 */
@Composable
fun ToolInfoDialog(
    tool: ToolRowState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZillitTheme.colors.surface,
        title = {
            Text(
                // v2 puts a colon after the tool's name here.
                text = "${tool.title}:",
                style = MaterialTheme.typography.titleLarge,
                color = ZillitTheme.colors.textPrimary,
            )
        },
        text = {
            val explainer = stringResource(tool.infoText)
            val moreLabel = stringResource(R.string.more)
            val linkColour = ZillitTheme.colors.brand

            val body = remember(explainer, tool.helpUrl, moreLabel, linkColour) {
                buildAnnotatedString {
                    append(explainer)

                    // Appended to the sentence, underlined and coloured. The explainer is a
                    // summary and this carries on from it, so it reads as part of the text
                    // rather than as another control competing with OK.
                    tool.helpUrl?.let { url ->
                        append(" ")
                        withLink(LinkAnnotation.Url(url)) {
                            withStyle(
                                SpanStyle(
                                    color = linkColour,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ) {
                                append(moreLabel)
                            }
                        }
                    }
                }
            }

            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
                // Several of these run to a paragraph, and a dialog clips rather than
                // scrolls what it cannot fit.
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Row {
                tool.videoUrl?.let { url ->
                    TextButton(onClick = { context.openLink(url) }) {
                        Text(
                            text = stringResource(R.string.watch_video),
                            color = ZillitTheme.colors.accentWarm,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.ok),
                        color = ZillitTheme.colors.brand,
                    )
                }
            }
        },
    )
}
