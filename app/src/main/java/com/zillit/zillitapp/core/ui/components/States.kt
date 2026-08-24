package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.window.currentWindowSize
import com.zillit.zillitapp.core.ui.window.horizontalPadding

/**
 * The three states every data-backed screen needs.
 *
 * Having one implementation of each means an empty list looks the same everywhere and,
 * more usefully, that an error is always actionable — every [ErrorState] takes a retry.
 */

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = ZillitTheme.colors.brand)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    /** Optional: some empty states say all they need to in the title alone. */
    description: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val windowSize = currentWindowSize()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = windowSize.horizontalPadding()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // Capped so the copy does not run the full width of a tablet.
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(ZillitTheme.colors.brandSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = ZillitTheme.colors.brand,
                    modifier = Modifier.size(32.dp),
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = ZillitTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )

            // Skipped when absent rather than rendered blank, which would still take its
            // spacing and leave a gap under the title.
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                actions()
            }
        }
    }
}

@Composable
fun ErrorState(
    icon: ImageVector,
    title: String,
    description: String,
    onRetry: () -> Unit,
    retryLabel: String,
    modifier: Modifier = Modifier,
) {
    val windowSize = currentWindowSize()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = windowSize.horizontalPadding()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(ZillitTheme.colors.dangerSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = ZillitTheme.colors.danger,
                    modifier = Modifier.size(32.dp),
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = ZillitTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            SecondaryButton(
                text = retryLabel,
                onClick = onRetry,
                modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
            )
        }
    }
}
