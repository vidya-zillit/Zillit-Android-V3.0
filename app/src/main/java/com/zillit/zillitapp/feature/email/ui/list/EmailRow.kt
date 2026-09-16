package com.zillit.zillitapp.feature.email.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * One row of the mail list — v2's `item_email_card`.
 *
 * Everything the row needs is already computed by the time it gets here ([EmailRowState]),
 * because v2's adapter does the work in `onBindViewHolder`: it parses the sender out of a
 * `"Name <addr>"` string, strips the HTML for the snippet and formats the date, per row, on
 * the scroll thread. The same list re-binds on every badge emission — read state lives in
 * the badge tree, not in Realm — so that work happened constantly.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EmailRow(
    state: EmailRowState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionActive: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            // Unread is a **brand wash**, not a lighter background.
            //
            // Light and dark cannot share a "lighter means unread" rule: in the dark
            // palette `surface` and `background` are eight levels apart and the difference
            // simply is not visible on a phone. The brand orange is vivid in both themes,
            // so a few percent of it over the surface reads as a tint either way — and it
            // is the same signal as the bar, the dot and the bold text rather than a
            // fourth, different one.
            .background(
                when {
                    selected -> ZillitTheme.colors.brandSoft
                    state.unread -> ZillitTheme.colors.brand
                        .copy(alpha = UNREAD_TINT)
                        .compositeOver(ZillitTheme.colors.surface)

                    else -> ZillitTheme.colors.surface
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // The unread bar: a full-height stripe down the leading edge. Cheaper to read at a
        // glance than bold text, and it survives the row being tinted by a selection.
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(
                    if (state.unread) {
                        ZillitTheme.colors.brand
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                ),
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(
                    start = ZillitTheme.spacing.md,
                    end = ZillitTheme.spacing.xs,
                    top = ZillitTheme.spacing.md,
                    bottom = ZillitTheme.spacing.md,
                ),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            SelectableAvatar(
                state = state,
                selected = selected,
                selectionActive = selectionActive,
            )

            Column(modifier = Modifier.weight(1f)) {
                // Line 1 — who, and when.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.correspondent,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (state.unread) FontWeight.Bold else FontWeight.Normal,
                        color = ZillitTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    // "(3)" — how many messages are in this conversation.
                    if (state.threadCount > 1) {
                        Text(
                            text = stringResource(R.string.email_thread_count, state.threadCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZillitTheme.colors.textSecondary,
                        )
                    }

                    Box(modifier = Modifier.weight(1f))

                    if (state.hasAttachments) {
                        Icon(
                            imageVector = Icons.Outlined.AttachFile,
                            contentDescription = null,
                            tint = ZillitTheme.colors.textTertiary,
                            modifier = Modifier
                                .size(14.dp)
                                .padding(end = ZillitTheme.spacing.xxs),
                        )
                    }

                    // Queued, not yet sent. v2 shows this and shows nothing at all when the
                    // send finally fails, so a stuck mail looks like a slow one forever.
                    if (state.pending) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = ZillitTheme.colors.warning,
                            modifier = Modifier
                                .size(14.dp)
                                .padding(end = ZillitTheme.spacing.xxs),
                        )
                    }

                    Text(
                        text = state.time,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (state.unread) FontWeight.Bold else FontWeight.Normal,
                        color = if (state.unread) {
                            ZillitTheme.colors.brand
                        } else {
                            ZillitTheme.colors.textSecondary
                        },
                        maxLines = 1,
                    )
                }

                // Line 2 — the subject.
                Text(
                    text = state.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (state.unread) FontWeight.Bold else FontWeight.Normal,
                    color = if (state.unread) {
                        ZillitTheme.colors.textPrimary
                    } else {
                        ZillitTheme.colors.textSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = ZillitTheme.spacing.xxs),
                )

                // Line 3 — the first 120 characters of the body.
                // Never shown for a draft: v2 hides it, because a draft's snippet is
                // whatever the user has typed so far and repeating it under the subject
                // reads as a duplicate.
                if (state.snippet.isNotBlank() && !state.isDraft) {
                    Text(
                        text = state.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (state.unread) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (state.unread) {
                            ZillitTheme.colors.textSecondary
                        } else {
                            ZillitTheme.colors.textTertiary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Hidden while selecting: a row menu that acts on one mail, offered in the
            // middle of choosing several, is a way to lose the selection by accident.
            if (!selectionActive) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = state.moreDescription,
                    tint = ZillitTheme.colors.textTertiary,
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.CenterVertically)
                        .clickable(onClick = onMoreClick),
                )
            }
        }
    }
}

/**
 * The avatar, which becomes the selection tick while a selection is running.
 *
 * Gmail's gesture, and the reason it works is that the tick appears exactly where the thing
 * you tapped was — v2 instead tints the whole row and leaves the avatar in place, so on a
 * long list it is genuinely hard to see what is selected.
 */
@Composable
private fun SelectableAvatar(
    state: EmailRowState,
    selected: Boolean,
    selectionActive: Boolean,
) {
    Box(modifier = Modifier.size(AVATAR_SIZE), contentAlignment = Alignment.Center) {
        if (selectionActive && selected) {
            Box(
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .background(ZillitTheme.colors.brand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = ZillitTheme.colors.textOnBrand,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            UserAvatar(
                initials = state.initials,
                pictureKey = state.pictureKey,
                thumbnailKey = state.thumbnailKey,
                size = AVATAR_SIZE,
            )

            // v2's `unread_dot` — an 8dp brand dot ringed in the row's own colour, pinned
            // to the avatar's top-right. Redundant with the leading bar on purpose: the
            // bar is what you catch scanning the list, the dot is what you see once your
            // eye has landed on a row.
            if (state.unread) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(10.dp)
                        .clip(CircleShape)
                        // Ringed in the tinted row colour, not in `surface` — on an unread
                        // row those differ, and the mismatch shows as a halo.
                        .background(
                            ZillitTheme.colors.brand
                                .copy(alpha = UNREAD_TINT)
                                .compositeOver(ZillitTheme.colors.surface),
                        )
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(ZillitTheme.colors.brand),
                )
            }
        }
    }
}

private val AVATAR_SIZE = 40.dp

/**
 * How much brand to wash an unread row with.
 *
 * Low enough that a screen of unread mail does not read as an error state, high enough to
 * survive a dark palette where the surface is already near-black.
 */
private const val UNREAD_TINT = 0.10f
