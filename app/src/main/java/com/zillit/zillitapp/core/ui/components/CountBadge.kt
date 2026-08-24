package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * The red unread pill, drawn the same way everywhere it appears.
 *
 * The unit strip, the logo button, the dashboard tiles and the calendar's Events button all
 * showed one of these, and each had grown its own copy — four `BADGE_CAP` constants and
 * four slightly different paddings, which is how a badge ends up a different size depending
 * on which screen you are looking at.
 *
 * Renders nothing at all when [count] is zero or less, so callers can pass a count straight
 * through without guarding first.
 *
 * @param containerColor overridden where the pill sits on a brand-coloured chip and red on
 *   orange would vibrate.
 * @param ringColor draws a ring of the surface behind the pill, for the case where it
 *   overhangs a border — without it the digits collide with whatever edge it overlaps.
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = ZillitTheme.colors.danger,
    contentColor: Color = ZillitTheme.colors.textOnBrand,
    ringColor: Color? = null,
) {
    if (count <= 0) return

    Box(
        modifier = modifier
            .then(
                if (ringColor != null) {
                    Modifier.background(ringColor, CircleShape).padding(2.dp)
                } else {
                    Modifier
                },
            )
            .background(containerColor, CircleShape)
            // A floor rather than a fixed size: single digits stay circular, and three
            // characters ("99+") still fit without the pill turning into an oval.
            .widthIn(min = 18.dp)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > BADGE_CAP) {
                stringResource(R.string.badge_overflow, BADGE_CAP)
            } else {
                count.toString()
            },
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** Past this the pill shows `99+`. One definition, not one per screen. */
const val BADGE_CAP = 99
