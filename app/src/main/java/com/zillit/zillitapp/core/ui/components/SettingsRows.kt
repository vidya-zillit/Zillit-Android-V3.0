package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * The settings vocabulary: a card of rows, and the three kinds of row that go in it.
 *
 * These started life as private composables inside the project settings screen. Email has
 * its own settings screen with the same shapes — a switch for conversation view, then
 * navigation rows to signatures, groups, rules and forwarding — and rules has a third.
 * Rather than three screens each growing their own near-identical row, the vocabulary
 * lives here and the screens supply content.
 *
 * Rows are deliberately dumb: no state, no view model, no knowledge of what they open.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.padding(
                    start = ZillitTheme.spacing.xs,
                    bottom = ZillitTheme.spacing.sm,
                ),
            )
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = ZillitTheme.colors.surface,
            border = BorderStroke(1.dp, ZillitTheme.colors.border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
    }
}

/**
 * The divider between two rows of a group.
 *
 * Inset past the icon column so it separates the *labels*, which is what makes a card read
 * as a list rather than as stacked boxes.
 */
@Composable
fun SettingsRowDivider(inset: Boolean = true) {
    HorizontalDivider(
        color = ZillitTheme.colors.divider,
        modifier = if (inset) Modifier.padding(start = 64.dp) else Modifier,
    )
}

/**
 * How much a row wants to be noticed.
 *
 * Not decoration: "Leave Project" and "Conversation View" sit in the same list, and the only
 * thing standing between the two is how they are drawn.
 */
enum class SettingsEmphasis { NORMAL, ADMIN, DANGER }

/** A row that opens something else. */
@Composable
fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    emphasis: SettingsEmphasis = SettingsEmphasis.NORMAL,
    /** A count or a short status shown before the chevron — "3", "Off", "2 rules". */
    trailingText: String? = null,
    badgeCount: Int = 0,
    /** An unread dot for something that needs attention but has no count. */
    showDot: Boolean = false,
    /** Adds an ⓘ that opens the row's help, without making the whole row do that. */
    onInfo: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val tint = iconTint ?: when (emphasis) {
        SettingsEmphasis.ADMIN -> ZillitTheme.colors.accent
        SettingsEmphasis.DANGER -> ZillitTheme.colors.danger
        SettingsEmphasis.NORMAL -> ZillitTheme.colors.brand
    }

    SettingsRowScaffold(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = tint,
        emphasis = emphasis,
        enabled = enabled,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ZillitTheme.colors.danger),
            )
        }

        if (badgeCount > 0) {
            CountBadge(count = badgeCount)
        }

        if (onInfo != null) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = title,
                tint = ZillitTheme.colors.textTertiary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onInfo),
            )
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * A row that toggles something.
 *
 * The whole row is the target, not just the switch — a 32dp switch at the far edge of a
 * phone is a poor tap target, and every settings screen in the app behaves this way.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    SettingsRowScaffold(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        modifier = modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = zillitSwitchColors(),
        )
    }
}

/** The shared skeleton: optional icon, title over subtitle, caller-supplied trailing. */
@Composable
private fun SettingsRowScaffold(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    iconTint: Color? = null,
    emphasis: SettingsEmphasis = SettingsEmphasis.NORMAL,
    trailing: @Composable () -> Unit,
) {
    val tint = iconTint ?: ZillitTheme.colors.brand

    val titleColor = when {
        !enabled -> ZillitTheme.colors.textTertiary
        emphasis == SettingsEmphasis.NORMAL -> ZillitTheme.colors.textPrimary
        else -> tint
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        if (icon != null) {
            // A tinted disc rather than a bare glyph: it is what makes a settings list
            // skimmable by silhouette, and it gives the emphasis colours somewhere to live
            // that is not the label.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.12f)),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (emphasis == SettingsEmphasis.NORMAL) {
                    FontWeight.Normal
                } else {
                    FontWeight.SemiBold
                },
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        trailing()
    }
}
