package com.zillit.zillitapp.feature.cnc.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Chat / Call / Contacts.
 *
 * A segmented control rather than v2's full-width tab bar: three fixed destinations that
 * never change, sitting in the same pill language as the unit strip on Home.
 */
@Composable
fun CncTabs(
    selected: CncTab,
    onSelect: (CncTab) -> Unit,
    labels: @Composable (CncTab) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg)
            .clip(RoundedCornerShape(14.dp))
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CncTab.entries.forEach { tab ->
            val on = tab == selected
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .clickable { onSelect(tab) },
                color = if (on) ZillitTheme.colors.brand else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(11.dp),
            ) {
                Text(
                    text = labels(tab),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (on) ZillitTheme.colors.textOnBrand else ZillitTheme.colors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** One filter chip, with its count where it has one. */
@Composable
fun CncChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int = 0,
) {
    Surface(
        modifier = modifier.clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) ZillitTheme.colors.brand else ZillitTheme.colors.surface,
        border = BorderStroke(1.dp, if (selected) ZillitTheme.colors.brand else ZillitTheme.colors.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) ZillitTheme.colors.textOnBrand else ZillitTheme.colors.textSecondary,
            )
            // Only where there is something waiting — a chip reading "Unread 0" is noise.
            if (count > 0) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) {
                        ZillitTheme.colors.textOnBrand.copy(alpha = 0.85f)
                    } else {
                        ZillitTheme.colors.brand
                    },
                )
            }
        }
    }
}

/** A scrollable row of chips, so a narrow phone never truncates the last filter. */
@Composable
fun CncChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) { content() }
}

/**
 * An avatar with a presence ring.
 *
 * The ring sits on the avatar rather than in a column of its own, which is what lets the
 * row keep one straight right-hand edge for time, unread and the star.
 */
@Composable
fun PresenceAvatar(
    initials: String,
    online: Boolean,
    modifier: Modifier = Modifier,
    pictureKey: String? = null,
    thumbnailKey: String? = null,
    size: androidx.compose.ui.unit.Dp = 44.dp,
) {
    Box(modifier = modifier) {
        UserAvatar(
            initials = initials,
            pictureKey = pictureKey,
            thumbnailKey = thumbnailKey,
            size = size,
        )
        if (online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(ZillitTheme.colors.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(ZillitTheme.colors.success),
            )
        }
    }
}

/** The unread bubble, shared by the rows and the chips. */
@Composable
fun UnreadDot(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Surface(shape = CircleShape, color = ZillitTheme.colors.danger, modifier = modifier) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = ZillitTheme.colors.textOnBrand,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** The favourite toggle. Filled and tinted when on, outlined and quiet when off. */
@Composable
fun FavouriteButton(
    favourite: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (favourite) ZillitTheme.colors.brandSoft else androidx.compose.ui.graphics.Color.Transparent,
        border = if (favourite) null else BorderStroke(1.dp, ZillitTheme.colors.border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = contentDescription,
                tint = if (favourite) ZillitTheme.colors.brand else ZillitTheme.colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * A small round action, as used on the Contacts and Call rows.
 *
 * Tinted by default and outlined when [quiet] — the quiet form is for the secondary one in
 * a row, so five buttons do not read as five equally important things.
 */
@Composable
fun RowAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
) {
    Surface(
        modifier = modifier.clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (quiet) androidx.compose.ui.graphics.Color.Transparent else ZillitTheme.colors.brandSoft,
        border = if (quiet) BorderStroke(1.dp, ZillitTheme.colors.border) else null,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (quiet) ZillitTheme.colors.textTertiary else ZillitTheme.colors.brand,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).size(16.dp),
        )
    }
}
