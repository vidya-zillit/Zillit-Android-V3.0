package com.zillit.zillitapp.feature.email.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.AttachmentRow
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.html.HtmlBodyView
import com.zillit.zillitapp.core.ui.html.linkifyPlainText
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.Email
import com.zillit.zillitapp.feature.email.domain.EmailAddress
import com.zillit.zillitapp.feature.email.domain.EmailAttachment
import com.zillit.zillitapp.feature.email.ui.EmailDates

/**
 * One message in a thread — v2's `item_email_trail`.
 *
 * Two deliberate departures from v2, both flagged in the module audit as things not to
 * carry over:
 *
 *  1. **Messages are not all expanded.** v2's adapter adds every id to its expanded set
 *     inside `submitList`, so a ten-message thread renders ten full bodies and the
 *     collapsed-preview branch it ships is dead code. Here the newest message opens and the
 *     rest are one-line previews, which is what every mail client does and what makes a
 *     long thread readable on a phone.
 *  2. **Quoted history folds.** v2 renders the quote inline, so the same text appears in
 *     every reply below it — a ten-message thread contains the first message ten times.
 *     Here it sits behind a control.
 *
 * The header tap toggles the *details* block (full recipient lists and the long date), which
 * is v2's behaviour and stays.
 */
@Composable
fun EmailTrailItem(
    email: Email,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onMoreClick: () -> Unit,
    onSenderClick: () -> Unit,
    onAttachmentClick: (EmailAttachment) -> Unit,
    onAttachmentDownload: (EmailAttachment) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    resolveContentId: (String) -> String? = { null },
    resolveAvatar: (EmailAddress) -> Pair<String?, String?> = { null to null },
) {
    var showDetails by remember(email.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface),
    ) {
        TrailHeader(
            email = email,
            expanded = expanded,
            showDetails = showDetails,
            onHeaderClick = {
                // Collapsed, the header's job is to open the message; open, it reveals who
                // else was on it. One target, two jobs, decided by state rather than by
                // two competing tap areas in the same row.
                if (expanded) showDetails = !showDetails else onToggleExpanded()
            },
            onMoreClick = onMoreClick,
            onSenderClick = onSenderClick,
            resolveAvatar = resolveAvatar,
        )

        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider(
                    color = ZillitTheme.colors.divider,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg),
                )

                MessageBody(
                    email = email,
                    onLinkClick = onLinkClick,
                    resolveContentId = resolveContentId,
                )

                val attachments = email.visibleAttachments
                if (attachments.isNotEmpty()) {
                    HorizontalDivider(
                        color = ZillitTheme.colors.divider,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg),
                    )

                    Column(
                        modifier = Modifier.padding(
                            horizontal = ZillitTheme.spacing.lg,
                            vertical = ZillitTheme.spacing.md,
                        ),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.email_attachment_count,
                                attachments.size,
                                attachments.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = ZillitTheme.colors.textSecondary,
                        )

                        attachments.forEach { attachment ->
                            AttachmentRow(
                                fileName = attachment.name,
                                sizeBytes = attachment.size.takeIf { it > 0 },
                                onClick = { onAttachmentClick(attachment) },
                                onAction = { onAttachmentDownload(attachment) },
                                actionIcon = Icons.Outlined.Download,
                                actionDescription = stringResource(R.string.email_attachment_download),
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)
    }
}

@Composable
private fun TrailHeader(
    email: Email,
    expanded: Boolean,
    showDetails: Boolean,
    onHeaderClick: () -> Unit,
    onMoreClick: () -> Unit,
    onSenderClick: () -> Unit,
    resolveAvatar: (EmailAddress) -> Pair<String?, String?>,
) {
    val (picture, thumbnail) = resolveAvatar(email.from)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onHeaderClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        // The avatar is the sender, so tapping it asks about the sender — whether they are
        // in the address book, and an offer to add them if not. The rest of the row keeps
        // its own job of opening the message.
        UserAvatar(
            initials = email.from.initials,
            pictureKey = picture,
            thumbnailKey = thumbnail,
            size = 40.dp,
            modifier = Modifier.clickable(onClick = onSenderClick),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = email.from.display,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (showDetails) {
                    ZillitTheme.colors.brand
                } else {
                    ZillitTheme.colors.textPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            when {
                showDetails -> MessageDetails(email)

                expanded -> Text(
                    // "To Vaibhav + 2" — enough to know whether a reply-all is going
                    // somewhere you expect, without the full list.
                    text = recipientsSummary(email),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                else -> Text(
                    text = email.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = EmailDates.trailHeader(email.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.textTertiary,
            )
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.email_more_options),
                tint = ZillitTheme.colors.textTertiary,
                modifier = Modifier
                    .padding(top = ZillitTheme.spacing.xs)
                    .size(20.dp)
                    .clickable(onClick = onMoreClick),
            )
        }
    }
}

/** The full recipient lists and the long-form date, shown when the header is tapped open. */
@Composable
private fun MessageDetails(email: Email) {
    Column(modifier = Modifier.padding(top = ZillitTheme.spacing.sm)) {
        Text(
            text = email.from.address,
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.textTertiary,
        )

        AddressLine(stringResource(R.string.email_field_to), email.to)
        AddressLine(stringResource(R.string.email_field_cc), email.cc)
        AddressLine(stringResource(R.string.email_field_bcc), email.bcc)

        Text(
            text = EmailDates.full(email.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = ZillitTheme.colors.textTertiary,
            modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
        )
    }
}

@Composable
private fun AddressLine(label: String, addresses: List<EmailAddress>) {
    if (addresses.isEmpty()) return

    Row(modifier = Modifier.padding(top = ZillitTheme.spacing.xxs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = ZillitTheme.colors.textTertiary,
            modifier = Modifier.width(28.dp),
        )
        Text(
            // Comma-separated, not v2's newline-per-address: a five-recipient mail there
            // becomes ten lines of header before the body starts. The address is shown
            // alongside the name, because checking who a mail actually went to is the
            // whole reason for opening the details.
            text = addresses.joinToString(", ") { address ->
                if (address.name.isBlank()) {
                    address.address
                } else {
                    "${address.name} <${address.address}>"
                }
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = ZillitTheme.colors.brand,
            modifier = Modifier.padding(start = ZillitTheme.spacing.xs),
        )
    }
}

/**
 * The body, with the quoted history folded away behind a control.
 *
 * Splitting on the quote is done here rather than in the model because it is presentation:
 * the full body is what gets forwarded, printed and re-quoted, and must stay intact.
 */
@Composable
private fun MessageBody(
    email: Email,
    onLinkClick: (String) -> Unit,
    resolveContentId: (String) -> String?,
) {
    var showQuoted by remember(email.id) { mutableStateOf(false) }
    val split = remember(email.id, email.body, email.text) { splitQuotedHistory(email) }

    Column(modifier = Modifier.padding(vertical = ZillitTheme.spacing.md)) {
        BodyContent(
            content = split.latest,
            isHtml = email.isHtml,
            onLinkClick = onLinkClick,
            resolveContentId = resolveContentId,
        )

        if (split.quoted != null) {
            Row(
                modifier = Modifier
                    .padding(
                        start = ZillitTheme.spacing.lg,
                        top = ZillitTheme.spacing.sm,
                    )
                    .clickable { showQuoted = !showQuoted },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Outlined.UnfoldMore,
                    contentDescription = null,
                    tint = ZillitTheme.colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (showQuoted) {
                        stringResource(R.string.email_hide_quoted)
                    } else {
                        stringResource(R.string.email_show_quoted)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = ZillitTheme.colors.textSecondary,
                )
            }

            AnimatedVisibility(visible = showQuoted) {
                BodyContent(
                    content = split.quoted,
                    isHtml = email.isHtml,
                    onLinkClick = onLinkClick,
                    resolveContentId = resolveContentId,
                )
            }
        }
    }
}

@Composable
private fun BodyContent(
    content: String,
    isHtml: Boolean,
    onLinkClick: (String) -> Unit,
    resolveContentId: (String) -> String?,
) {
    if (isHtml) {
        HtmlBodyView(
            html = content,
            onLinkClick = onLinkClick,
            resolveContentId = resolveContentId,
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg),
        )
    } else {
        Text(
            // A plain-text mail has no anchors at all, so every link in it is bare. Without
            // this the URL is dead text the user has to retype.
            text = linkifyPlainText(content, ZillitTheme.colors.brand),
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg),
        )
    }
}

/** "To Sam + 2" — the first recipient, and how many others there were. */
@Composable
private fun recipientsSummary(email: Email): String {
    // The name is cut rather than left to the line's own ellipsis: on one line a long first
    // recipient pushes the "+ 3" off the end, which is the only part that says there were
    // others at all.
    val first = email.to.firstOrNull()?.display.orEmpty().let { name ->
        if (name.length > RECIPIENT_NAME_MAX) name.take(RECIPIENT_NAME_MAX) + "..." else name
    }
    val others = (email.to.size - 1).coerceAtLeast(0) + email.cc.size

    return if (others > 0) {
        stringResource(R.string.email_recipients_summary_more, first, others)
    } else {
        stringResource(R.string.email_recipients_summary, first)
    }
}

private data class BodySplit(val latest: String, val quoted: String?)

/**
 * Separates this message's own text from the history quoted underneath it.
 *
 * Heuristic, necessarily — there is no standard for this and every client marks quotes
 * differently. The markers checked are the ones the clients in use here actually emit, and
 * when none is found the whole body is treated as the message, which is the safe failure:
 * showing too much beats hiding something the sender wrote.
 */
private fun splitQuotedHistory(email: Email): BodySplit {
    val body = if (email.isHtml) email.body else email.text.ifBlank { email.body }
    if (body.isBlank()) return BodySplit(body, null)

    val marker = QUOTE_MARKERS
        .mapNotNull { pattern -> pattern.find(body)?.range?.first }
        .filter { it > MIN_LATEST_LENGTH }
        .minOrNull()
        ?: return BodySplit(body, null)

    return BodySplit(
        latest = body.substring(0, marker).trimEnd(),
        quoted = body.substring(marker),
    )
}

private val QUOTE_MARKERS = listOf(
    Regex("""<blockquote""", RegexOption.IGNORE_CASE),
    Regex("""-{2,}\s*Forwarded message\s*-{2,}""", RegexOption.IGNORE_CASE),
    Regex("""\bOn .{4,60}\bwrote:""", RegexOption.IGNORE_CASE),
    Regex("""(?m)^>\s""", RegexOption.IGNORE_CASE),
)

/**
 * A quote marker before this point is almost certainly the whole body being a quote — a
 * bare forward with no note on top. Folding that leaves an empty message.
 */
private const val MIN_LATEST_LENGTH = 24

/** v2 truncates the leading recipient at the same width. */
private const val RECIPIENT_NAME_MAX = 20
