package com.zillit.zillitapp.feature.help.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * One row of the help list: a label and the page it opens.
 *
 * Modelled as data rather than as a hand-written column so the list stays a list — v2
 * builds the same six rows imperatively and the order drifted between its two entry points.
 */
private enum class HelpEntry(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val url: String,
) {
    CONTACT(R.string.txt_help_contact, Icons.Outlined.MailOutline, "${CORPORATE}contact-us"),
    FAQ(
        R.string.txt_help_faq,
        Icons.Outlined.HelpOutline,
        "${CORPORATE}frequently-asked-questions-for-zillit-application-and-web-platform",
    ),
    HOW_TO_USE(
        R.string.txt_help_use_zillit,
        Icons.Outlined.Description,
        "https://corporate.zillit.com/videos-mobile.php",
    ),
    PRIVACY(
        R.string.txt_help_privacy,
        Icons.Outlined.PrivacyTip,
        "${CORPORATE}privacy-policy-for-zillit-application-and-web-platform",
    ),
    TERMS(
        R.string.txt_help_condition,
        Icons.Outlined.Gavel,
        "${CORPORATE}terms-conditions-for-zillit-application-and-web-platform",
    ),
    REVIEWS(
        R.string.txt_help_reviews,
        Icons.Outlined.StarOutline,
        "https://corporate.zillit.com/reviews.php",
    ),
}

private const val CORPORATE = "https://corporate.zillit.com/"

/**
 * Zillit Help.
 *
 * Every entry is a page on the corporate site, so each opens in the browser rather than an
 * in-app WebView as v2 does. That is deliberate: these are static marketing and legal pages
 * that the user may well want to share, print or keep open, and an embedded WebView takes
 * all of that away while adding a surface to maintain.
 */
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background)
            // The nav host draws edge to edge, so the header has to inset itself or it
            // sits under the clock.
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surface)
                .padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_close),
                tint = ZillitTheme.colors.textPrimary,
                modifier = Modifier.size(24.dp).clickable(onClick = onBack),
            )
            Text(
                text = stringResource(R.string.zillit_help),
                style = MaterialTheme.typography.titleMedium,
                color = ZillitTheme.colors.textPrimary,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(vertical = ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            HelpEntry.entries.forEach { entry ->
                HelpRow(
                    label = stringResource(entry.labelRes),
                    icon = entry.icon,
                    onClick = { runCatching { uriHandler.openUri(entry.url) } },
                )
            }
        }
    }
}

@Composable
private fun HelpRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md)
            .clip(RoundedCornerShape(ZillitTheme.shapes.medium))
            .background(ZillitTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ZillitTheme.colors.brand,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = ZillitTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        // Marks the row as leaving the app, so a browser opening is not a surprise.
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}
