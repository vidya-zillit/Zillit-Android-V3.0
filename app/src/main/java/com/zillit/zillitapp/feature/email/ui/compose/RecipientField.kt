package com.zillit.zillitapp.feature.email.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailAddress
import com.zillit.zillitapp.feature.email.domain.isPlausibleEmail
import androidx.compose.ui.res.stringResource

/**
 * One of To / Cc / Bcc — v2's `row_to` / `row_cc` / `row_bcc` plus `item_recipient_chip`.
 *
 * The field has two looks: **expanded**, a wrapping row of chips with an input at the end,
 * and **collapsed**, a single chip and a `+2`. It collapses when focus leaves and there is
 * something in it, which is what keeps three recipient rows from eating half the screen
 * before the user has typed a subject.
 *
 * v2 always shows all three rows and never offers a Cc/Bcc toggle — its `showCc`/`showBcc`
 * state and `toggleCc()`/`toggleBcc()` exist and are never read. Kept as-is: three visible
 * rows is the client-approved layout.
 *
 * A typed address is committed on a comma, a semicolon, Enter, Tab, or losing focus.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipientField(
    label: String,
    recipients: List<EmailAddress>,
    input: String,
    expanded: Boolean,
    onInputChange: (String) -> Unit,
    onCommit: () -> Unit,
    onRemove: (EmailAddress) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                // Collapsed, the whole row is the way back in — there is no input to tap.
                if (!expanded) Modifier.clickable { onFocusChange(true) } else Modifier,
            )
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textTertiary,
            modifier = Modifier.width(36.dp),
        )

        if (expanded) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                recipients.forEach { recipient ->
                    RecipientChip(
                        recipient = recipient,
                        onRemove = { onRemove(recipient) },
                    )
                }

                RecipientInput(
                    value = input,
                    onValueChange = onInputChange,
                    onCommit = onCommit,
                    onFocusChange = onFocusChange,
                )
            }
        } else {
            CollapsedSummary(
                recipients = recipients,
                onRemoveFirst = { recipients.firstOrNull()?.let(onRemove) },
                onExpand = { onFocusChange(true) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A committed recipient. The ✕ is always present — a chip you cannot remove is a trap. */
@Composable
private fun RecipientChip(recipient: EmailAddress, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ZillitTheme.colors.brandSoft)
            .padding(start = 2.dp, end = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ChipAvatar(recipient)

        Text(
            text = recipient.display,
            style = MaterialTheme.typography.labelLarge,
            color = ZillitTheme.colors.brand,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )

        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = stringResource(R.string.action_remove),
            tint = ZillitTheme.colors.brand,
            modifier = Modifier
                .size(16.dp)
                .clickable(onClick = onRemove),
        )
    }
}

@Composable
private fun ChipAvatar(recipient: EmailAddress) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(ZillitTheme.colors.brand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = recipient.initials.take(1),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = ZillitTheme.colors.textOnBrand,
        )
    }
}

@Composable
private fun RecipientInput(
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = { typed ->
            // A separator means "that one is done" — commit and clear rather than leaving
            // the punctuation in the field.
            if (typed.endsWith(',') || typed.endsWith(';')) {
                onValueChange(typed.dropLast(1))
                onCommit()
            } else {
                onValueChange(typed)
            }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = ZillitTheme.colors.textPrimary,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(ZillitTheme.colors.brand),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.email_add_recipient),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textTertiary,
                    )
                }
                inner()
            }
        },
        modifier = Modifier
            .widthIn(min = 120.dp)
            .onFocusChanged { onFocusChange(it.isFocused) }
            .onKeyEvent { event ->
                // A hardware keyboard's Enter and Tab commit too — people compose mail on
                // tablets with keyboards attached.
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.Tab)
                ) {
                    onCommit()
                    true
                } else {
                    false
                }
            },
    )
}

/**
 * "Sam Whitfield ✕  +2" — the first recipient and a count of the rest.
 *
 * The chip keeps its **✕**, and the "+2" is its own tap target that expands the row. Without
 * both, a collapsed field is a dead end: the only recipient you can see cannot be removed,
 * and the ones you cannot see cannot be reached at all — which is exactly what happens when
 * you add two addresses and want to drop the second.
 */
@Composable
private fun CollapsedSummary(
    recipients: List<EmailAddress>,
    onRemoveFirst: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = recipients.firstOrNull() ?: return
    val others = recipients.size - 1

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(ZillitTheme.colors.brandSoft)
                .padding(start = 2.dp, end = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ChipAvatar(first)
            Text(
                text = first.display,
                style = MaterialTheme.typography.labelLarge,
                color = ZillitTheme.colors.brand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.action_remove),
                tint = ZillitTheme.colors.brand,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onRemoveFirst),
            )
        }

        if (others > 0) {
            // Tappable: this is the only route to the recipients the summary is hiding.
            Text(
                text = stringResource(R.string.email_recipient_overflow, others),
                style = MaterialTheme.typography.labelLarge,
                color = ZillitTheme.colors.brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onExpand)
                    .padding(
                        horizontal = ZillitTheme.spacing.sm,
                        vertical = ZillitTheme.spacing.xxs,
                    ),
            )
        }
    }
}

/**
 * Turns typed text into a recipient, or refuses.
 *
 * v2 accepts anything containing `@`, so `a@` and `@b` both become chips and the send fails
 * at the server with a message nobody can act on. Here the check is
 * [isPlausibleEmail], and a rejection leaves the text in the field to be corrected rather
 * than silently dropping it.
 */
fun parseRecipient(input: String): EmailAddress? {
    val trimmed = input.trim().trim(',', ';')
    if (trimmed.isEmpty()) return null

    val parsed = EmailAddress.parse(trimmed)
    return parsed.takeIf { it.address.isPlausibleEmail() }
}
