package com.zillit.zillitapp.feature.cnc.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.cnc.ui.list.PresenceAvatar

/**
 * Who you are talking to, and what you can do about it — including leaving.
 *
 * **This is the whole top bar of the thread**, back arrow included, so the screen has one
 * bar rather than a strip holding a lone arrow above a strip holding the person. Reading
 * left to right it is: back, who, what you can do. The conversation is a page of its own,
 * so the project bar and the tab bar are not on it at all.
 */
@Composable
fun CncThreadHeader(
    title: String,
    subtitle: String,
    initials: String,
    onBack: () -> Unit,
    onOpenDetails: () -> Unit,
    onAttachments: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    modifier: Modifier = Modifier,
    pictureKey: String? = null,
    thumbnailKey: String? = null,
    online: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth().background(ZillitTheme.colors.surface)) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ZillitTheme.spacing.sm,
                end = ZillitTheme.spacing.lg,
                top = ZillitTheme.spacing.sm,
                bottom = ZillitTheme.spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back is its own target, outside the tap area that opens the details — they sit
        // on the same line and must not be the same gesture.
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(ZillitTheme.spacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = ZillitTheme.colors.textPrimary,
                modifier = Modifier.size(22.dp),
            )
        }

        // Picture and name open the details together: they are one thing on screen, so
        // tapping either must do the same.
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenDetails),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PresenceAvatar(
                initials = initials,
                online = online,
                pictureKey = pictureKey,
                thumbnailKey = thumbnailKey,
                size = 38.dp,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        HeaderAction(Icons.Outlined.AttachFile, stringResource(R.string.cnc_files), onAttachments)
        HeaderAction(Icons.Outlined.Call, stringResource(R.string.cnc_action_audio), onAudioCall)
        HeaderAction(Icons.Outlined.Videocam, stringResource(R.string.cnc_action_video), onVideoCall)
    }

        // Surface, like every other top bar in the app, rather than a tinted band: the
        // avatar's own circle is the tint, and on a tinted band it vanished into it.
        HorizontalDivider(color = ZillitTheme.colors.divider)
    }
}

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape,
        color = ZillitTheme.colors.brandSoft,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = ZillitTheme.colors.brand,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp).size(16.dp),
            )
        }
    }
}
