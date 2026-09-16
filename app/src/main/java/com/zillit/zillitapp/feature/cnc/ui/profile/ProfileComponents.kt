package com.zillit.zillitapp.feature.cnc.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.cnc.ui.list.PresenceAvatar
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.R

/**
 * The header both the person and the group screens open with.
 *
 * @param onChangePhoto when set, a camera badge appears on the avatar. Only a group admin
 *   gets one, so passing null is how the screen expresses "you may not change this".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileHeader(
    initials: String,
    title: String,
    subtitles: List<String>,
    actions: List<ProfileAction>,
    modifier: Modifier = Modifier,
    pictureKey: String? = null,
    thumbnailKey: String? = null,
    online: Boolean = false,
    onChangePhoto: (() -> Unit)? = null,
    /** Shows a pencil beside the title. Null for a title nobody may change. */
    onEditTitle: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(
                horizontal = ZillitTheme.spacing.lg,
                vertical = ZillitTheme.spacing.xl,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Box {
            if (online) {
                PresenceAvatar(
                    initials = initials,
                    online = true,
                    pictureKey = pictureKey,
                    thumbnailKey = thumbnailKey,
                    size = 96.dp,
                )
            } else {
                UserAvatar(
                    initials = initials,
                    pictureKey = pictureKey,
                    thumbnailKey = thumbnailKey,
                    size = 96.dp,
                )
            }

            onChangePhoto?.let { change ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = change),
                    shape = CircleShape,
                    color = ZillitTheme.colors.brand,
                    border = androidx.compose.foundation.BorderStroke(3.dp, ZillitTheme.colors.surface),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.PhotoCamera,
                            contentDescription = null,
                            tint = ZillitTheme.colors.textOnBrand,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = ZillitTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            // Beside the name rather than in a menu: renaming a group is the one thing an
            // admin comes to this screen to do, and a pencil says so without a label.
            onEditTitle?.let { edit ->
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.edit),
                    tint = ZillitTheme.colors.accent,
                    modifier = Modifier.size(18.dp).clickable(onClick = edit),
                )
            }
        }
        subtitles.filter { it.isNotBlank() }.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textTertiary,
                textAlign = TextAlign.Center,
            )
        }

        // Wraps rather than scrolls: four pills fit a phone, and a fifth should drop to a
        // second line instead of hiding off the edge.
        FlowRow(
            modifier = Modifier.padding(top = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            actions.forEach { action -> ActionPill(action) }
        }
    }
}

/** One of the orange actions under a profile header. */
data class ProfileAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

@Composable
private fun ActionPill(action: ProfileAction) {
    Surface(
        modifier = Modifier.clip(CircleShape).clickable(onClick = action.onClick),
        shape = CircleShape,
        color = ZillitTheme.colors.brand,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                action.icon,
                contentDescription = null,
                tint = ZillitTheme.colors.textOnBrand,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = ZillitTheme.colors.textOnBrand,
            )
        }
    }
}

/** The small uppercase label above a group of rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = ZillitTheme.colors.textTertiary,
        modifier = modifier.padding(
            start = ZillitTheme.spacing.lg,
            end = ZillitTheme.spacing.lg,
            top = ZillitTheme.spacing.lg,
            bottom = ZillitTheme.spacing.xs,
        ),
    )
}

/** A settings-style row: icon, label, and a value or trailing content. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DetailRow(
    icon: ImageVector?,
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** Long press. Used for actions that must not happen by accident. */
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tint = if (destructive) ZillitTheme.colors.danger else ZillitTheme.colors.textPrimary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .then(
                when {
                    onClick == null && onLongClick == null -> Modifier
                    else -> Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                },
            )
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        icon?.let {
            Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (destructive) FontWeight.Bold else FontWeight.Normal,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        value?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.textTertiary,
            )
        }
        trailing?.invoke()
    }
}
