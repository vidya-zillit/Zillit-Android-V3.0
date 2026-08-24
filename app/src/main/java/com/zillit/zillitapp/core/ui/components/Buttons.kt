package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Shared button set.
 *
 * A 48dp minimum height is enforced rather than left to each call site — that is the
 * accessible touch-target floor, and it also keeps buttons visually consistent between
 * phone and tablet where padding otherwise differs.
 */
private val MinButtonHeight = 48.dp

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    fillWidth: Boolean = true,
) {
    Button(
        onClick = onClick,
        // A button in flight must not be clickable again, or a double tap sends two
        // create-project requests.
        enabled = enabled && !loading,
        modifier = Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = MinButtonHeight)
            .then(modifier),
        shape = RoundedCornerShape(ZillitTheme.shapes.medium),
        colors = ButtonDefaults.buttonColors(
            containerColor = ZillitTheme.colors.brand,
            contentColor = ZillitTheme.colors.textOnBrand,
            disabledContainerColor = ZillitTheme.colors.brand.copy(alpha = 0.4f),
            disabledContentColor = ZillitTheme.colors.textOnBrand.copy(alpha = 0.7f),
        ),
    ) {
        ButtonContent(text = text, loading = loading, leadingIcon = leadingIcon)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    fillWidth: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = MinButtonHeight)
            .then(modifier),
        shape = RoundedCornerShape(ZillitTheme.shapes.medium),
        border = BorderStroke(1.dp, ZillitTheme.colors.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = ZillitTheme.colors.textPrimary,
            disabledContentColor = ZillitTheme.colors.textTertiary,
        ),
    ) {
        ButtonContent(text = text, loading = loading, leadingIcon = leadingIcon)
    }
}

@Composable
private fun ButtonContent(
    text: String,
    loading: Boolean,
    leadingIcon: ImageVector?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = androidx.compose.material3.LocalContentColor.current,
            )

            leadingIcon != null -> Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(text = text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}
