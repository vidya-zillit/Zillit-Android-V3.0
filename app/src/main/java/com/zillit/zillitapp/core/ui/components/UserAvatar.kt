package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zillit.zillitapp.core.ui.chat.rememberAttachmentImage
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * A person, as a circle: their photo when there is one, their initials when there is not.
 *
 * Most crew never upload a picture, so initials are the normal case rather than a fallback —
 * and they still have to tell two people apart at a glance, which is why they keep a colour
 * and a weight rather than being grey filler.
 *
 * @param pictureKey the **object key** the server stores (`profile_picture.media`), not a
 *   URL. Profile pictures live in the project's private bucket and are fetched exactly like
 *   a chat attachment: signed, downloaded once and cached. Handing the raw key to an image
 *   loader — which is what this used to do — silently loads nothing, which is why avatars
 *   were always initials.
 * @param thumbnailKey the server's small copy. Preferred when the full file is not already
 *   cached: a circle this size does not need the original.
 */
@Composable
fun UserAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    pictureKey: String? = null,
    thumbnailKey: String? = null,
    size: Dp = 36.dp,
) {
    val picture by rememberAttachmentImage(
        remoteKey = pictureKey?.takeIf { it.isNotBlank() },
        thumbnailKey = thumbnailKey?.takeIf { it.isNotBlank() },
        fileName = pictureKey?.substringAfterLast('/').orEmpty(),
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(ZillitTheme.colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            AsyncImage(
                model = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            // Shown while the picture downloads too, so the circle is never empty.
            Text(
                text = initials,
                style = if (size >= LARGE) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.labelMedium
                },
                color = ZillitTheme.colors.accent,
            )
        }
    }
}

/** Above this, the initials need the larger type or they float in the circle. */
private val LARGE = 48.dp
