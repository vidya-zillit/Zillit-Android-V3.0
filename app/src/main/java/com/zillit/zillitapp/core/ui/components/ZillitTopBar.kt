package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.window.currentWindowSize
import com.zillit.zillitapp.core.ui.window.horizontalPadding

/**
 * The header every ordinary screen uses — v3's equivalent of v2's `custom_toolbar.xml`.
 *
 * v2 had this as an `<include>` present in ~1,300 layouts, with each Activity reaching
 * into `binding.toolbar.*` to show or hide `backBtn`, `infoTap`, `scannerBtn`,
 * `moreHolder` and friends by hand. That is why the same control behaved differently
 * screen to screen. Here the bar takes parameters: passing a lambda shows the control,
 * passing null hides it, so a screen cannot half-configure it.
 *
 * Help ([onHelpClick]) is `infoTap` in v2 and appears on nearly every screen, so it is
 * enabled by default — a screen has to opt out rather than remember to opt in.
 */
@Composable
fun ZillitTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBackClick: (() -> Unit)? = null,
    onHelpClick: (() -> Unit)? = null,
    onScanQrClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    /** Extra screen-specific actions, placed before the shared ones. */
    actions: @Composable RowScope.() -> Unit = {},
) {
    val windowSize = currentWindowSize()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface),
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Edge-to-edge means this Row is drawn behind the system bars, so it must
            // inset itself — Scaffold does not do it for a custom topBar.
            //
            // systemBars UNION displayCutout, and deliberately NOT safeDrawing:
            //  - statusBarsPadding alone is wrong because in landscape on a cutout device
            //    the status-bar inset collapses to ~0 at the top while the cutout moves
            //    to the SIDE, leaving the clock over the title and icons under the camera;
            //  - safeDrawing is wrong because it INCLUDES the IME, and its values animate
            //    with the keyboard — so opening the search field briefly zeroed the top
            //    inset and the bar slid under the status bar.
            .windowInsetsPadding(WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(
                horizontal = windowSize.horizontalPadding(),
                vertical = ZillitTheme.spacing.sm,
            )
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBackClick != null) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .size(40.dp)
                    .offset(x = (-8).dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = ZillitTheme.colors.textPrimary,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (subtitle == null) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.titleLarge
                },
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        actions()

        if (onScanQrClick != null) {
            TopBarAction(
                icon = Icons.Outlined.QrCodeScanner,
                contentDescription = stringResource(R.string.project_scan_qr),
                onClick = onScanQrClick,
            )
        }

        if (onHelpClick != null) {
            TopBarAction(
                icon = Icons.Outlined.HelpOutline,
                contentDescription = stringResource(R.string.action_help),
                onClick = onHelpClick,
            )
        }

        if (onMoreClick != null) {
            TopBarAction(
                icon = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.action_more),
                onClick = onMoreClick,
            )
        }
    }
        HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 1.dp)
    }
}

/**
 * The main-screen header — v3's equivalent of v2's `custom_toolbar_main.xml`.
 *
 * Structurally different from [ZillitTopBar], which is why it is a separate composable
 * rather than a flag: instead of a back button and a title it shows the Zillit logo, the
 * **active project as a switcher** (v2's `projectContainer`/`tvProject`/`imgDropDown`),
 * and an SOS action. Help and More sit in the same place as on every other screen so
 * they stay findable.
 */
@Composable
fun ZillitMainTopBar(
    projectName: String,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
    /** Logo opens notifications; the badge on it is the project-wide unread count. */
    onLogoClick: (() -> Unit)? = null,
    onProjectSwitchClick: (() -> Unit)? = null,
    onSosClick: (() -> Unit)? = null,
    onHelpClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
) {
    val windowSize = currentWindowSize()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface),
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Edge-to-edge means this Row is drawn behind the system bars, so it must
            // inset itself — Scaffold does not do it for a custom topBar.
            //
            // systemBars UNION displayCutout, and deliberately NOT safeDrawing:
            //  - statusBarsPadding alone is wrong because in landscape on a cutout device
            //    the status-bar inset collapses to ~0 at the top while the cutout moves
            //    to the SIDE, leaving the clock over the title and icons under the camera;
            //  - safeDrawing is wrong because it INCLUDES the IME, and its values animate
            //    with the keyboard — so opening the search field briefly zeroed the top
            //    inset and the bar slid under the status bar.
            .windowInsetsPadding(WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(
                horizontal = windowSize.horizontalPadding(),
                vertical = ZillitTheme.spacing.sm,
            )
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitLogoButton(
            unreadCount = unreadCount,
            onClick = onLogoClick,
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onProjectSwitchClick != null) {
                        Modifier.clickable(onClick = onProjectSwitchClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Two lines, as in v2: the action ("Change Project") above, the current
            // project below. The name alone gave no hint it was tappable.
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.action_switch_project),
                        style = MaterialTheme.typography.labelMedium,
                        color = ZillitTheme.colors.accent,
                    )
                    if (onProjectSwitchClick != null) {
                        Icon(
                            imageVector = Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = ZillitTheme.colors.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    text = projectName,
                    style = MaterialTheme.typography.titleMedium,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (onSosClick != null) {
            Text(
                text = stringResource(R.string.action_sos),
                style = MaterialTheme.typography.labelLarge,
                color = ZillitTheme.colors.textOnBrand,
                modifier = Modifier
                    .background(ZillitTheme.colors.danger, CircleShape)
                    .clickable(onClick = onSosClick)
                    .padding(
                        horizontal = ZillitTheme.spacing.md,
                        vertical = ZillitTheme.spacing.xs,
                    ),
            )
        }

        if (onHelpClick != null) {
            TopBarAction(
                icon = Icons.Outlined.HelpOutline,
                contentDescription = stringResource(R.string.action_help),
                onClick = onHelpClick,
            )
        }

        if (onMoreClick != null) {
            TopBarAction(
                icon = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.action_more),
                onClick = onMoreClick,
            )
        }
    }
        HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 1.dp)
    }
}

/**
 * The Zillit mark, as the button that opens notifications.
 *
 * A rounded **card**, not a circle. Two reasons it is built this way:
 *  - the wordmark is roughly 2.4:1, so a circle had to shrink it to fit and the result
 *    read as decoration rather than a control;
 *  - it uses `zillit_wordmark`, not `zillit_logo*`. Every `zillit_logo` asset has an
 *    orange rounded-square frame painted into the artwork, so drawing one inside a card
 *    produced a visible box-inside-a-box. The wordmark carries no frame, which leaves the
 *    card as the only border.
 *
 * The badge is drawn by the wrapping [Box] rather than inside the card, so it can overhang
 * the corner the way v2's `zBadge` does instead of being clipped by the card's shape.
 */
@Composable
private fun ZillitLogoButton(
    unreadCount: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)

    Box(modifier = modifier) {
        Surface(
            shape = shape,
            color = ZillitTheme.colors.brandSoft,
            border = BorderStroke(1.dp, ZillitTheme.colors.brand.copy(alpha = 0.45f)),
            // A small lift, not a floating card: enough to read as pressable against a
            // top bar that is the same colour as an unlifted surface would be.
            shadowElevation = 2.dp,
            modifier = Modifier
                .size(width = 64.dp, height = 40.dp)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            onClick = onClick,
                            // Bounded so the ripple takes the card's shape — an unbounded
                            // one washes over the project name next to it.
                            indication = ripple(bounded = true),
                            interactionSource = remember { MutableInteractionSource() },
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.zillit_wordmark),
                    contentDescription = stringResource(R.string.notifications),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.padding(
                        horizontal = ZillitTheme.spacing.sm,
                        vertical = ZillitTheme.spacing.md,
                    ),
                )
            }
        }

        // v2's `zBadge`. Ringed in the bar's own colour so the digits stay readable where
        // the pill overlaps the card's border.
        CountBadge(
            count = unreadCount,
            ringColor = ZillitTheme.colors.surface,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 7.dp, y = (-7).dp),
        )
    }
}

@Composable
private fun TopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = ZillitTheme.colors.textPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}

