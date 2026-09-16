package com.zillit.zillitapp.core.ui.html

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatClear
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * The formatting strip under a [RichTextEditor].
 *
 * Same controls as v2 — bold, italic, underline, strikethrough, two list kinds, font size
 * ±, colour, clear — with one difference that matters in use: a button whose format is
 * **active at the caret is shown active**, because [RichTextEditorState.activeFormats]
 * reports it. In v2 every button looks the same at all times, so the only way to find out
 * whether you are about to type in bold is to type something.
 */
@Composable
fun RichTextToolbar(
    state: RichTextEditorState,
    modifier: Modifier = Modifier,
    /** Offered only where images belong in the body. Null hides the button entirely. */
    onInsertImage: (() -> Unit)? = null,
) {
    var showColors by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = ZillitTheme.colors.divider)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surface)
                .horizontalScroll(rememberScrollState())
                .padding(
                    horizontal = ZillitTheme.spacing.sm,
                    vertical = ZillitTheme.spacing.xs,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            ToolbarButton(
                icon = Icons.Outlined.FormatBold,
                description = stringResource(R.string.format_bold),
                active = RichTextFormat.BOLD in state.activeFormats,
                onClick = { state.toggle(RichTextFormat.BOLD) },
            )
            ToolbarButton(
                icon = Icons.Outlined.FormatItalic,
                description = stringResource(R.string.format_italic),
                active = RichTextFormat.ITALIC in state.activeFormats,
                onClick = { state.toggle(RichTextFormat.ITALIC) },
            )
            ToolbarButton(
                icon = Icons.Outlined.FormatUnderlined,
                description = stringResource(R.string.format_underline),
                active = RichTextFormat.UNDERLINE in state.activeFormats,
                onClick = { state.toggle(RichTextFormat.UNDERLINE) },
            )
            ToolbarButton(
                icon = Icons.Outlined.FormatStrikethrough,
                description = stringResource(R.string.format_strikethrough),
                active = RichTextFormat.STRIKETHROUGH in state.activeFormats,
                onClick = { state.toggle(RichTextFormat.STRIKETHROUGH) },
            )

            ToolbarSeparator()

            ToolbarButton(
                icon = Icons.Outlined.FormatListBulleted,
                description = stringResource(R.string.format_bullet_list),
                active = RichTextFormat.BULLET_LIST in state.activeFormats,
                onClick = { state.toggle(RichTextFormat.BULLET_LIST) },
            )
            ToolbarButton(
                icon = Icons.Outlined.FormatListNumbered,
                description = stringResource(R.string.format_numbered_list),
                active = RichTextFormat.NUMBERED_LIST in state.activeFormats,
                onClick = { state.toggle(RichTextFormat.NUMBERED_LIST) },
            )

            ToolbarSeparator()

            // v2's ±2pt, clamped 8-36 in the page itself.
            ToolbarTextButton(
                label = stringResource(R.string.format_size_decrease),
                description = stringResource(R.string.format_size_decrease_description),
                onClick = { state.changeFontSize(-FONT_STEP_PT) },
            )
            Icon(
                imageVector = Icons.Outlined.FormatSize,
                contentDescription = null,
                tint = ZillitTheme.colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
            ToolbarTextButton(
                label = stringResource(R.string.format_size_increase),
                description = stringResource(R.string.format_size_increase_description),
                onClick = { state.changeFontSize(FONT_STEP_PT) },
            )

            ToolbarSeparator()

            ToolbarButton(
                icon = Icons.Outlined.FormatColorText,
                description = stringResource(R.string.format_text_color),
                active = showColors,
                onClick = { showColors = !showColors },
            )
            ToolbarButton(
                icon = Icons.Outlined.FormatClear,
                description = stringResource(R.string.format_clear),
                active = false,
                onClick = state::clearFormatting,
            )

            onInsertImage?.let { insert ->
                ToolbarSeparator()

                ToolbarButton(
                    icon = Icons.Outlined.Image,
                    description = stringResource(R.string.format_insert_image),
                    active = false,
                    onClick = insert,
                )
            }
        }

        if (showColors) {
            ColorStrip(
                onPick = { colour ->
                    state.setTextColor(colour)
                    showColors = false
                },
            )
        }
    }
}

/**
 * v2's eight preset colours.
 *
 * A fixed palette rather than a colour wheel, deliberately: this text is going into
 * somebody else's mail client on somebody else's background, and eight known-legible
 * choices is the whole point.
 */
@Composable
private fun ColorStrip(onPick: (Color) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PRESET_COLORS.forEach { colour ->
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(colour)
                    .border(1.dp, ZillitTheme.colors.border, CircleShape)
                    .clickable { onPick(colour) },
            )
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) ZillitTheme.colors.brandSoft else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) ZillitTheme.colors.brand else ZillitTheme.colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ToolbarTextButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = ZillitTheme.colors.textSecondary,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ToolbarSeparator() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 22.dp)
            .background(ZillitTheme.colors.divider),
    )
}

private const val FONT_STEP_PT = 2

private val PRESET_COLORS = listOf(
    Color(0xFF000000),
    Color(0xFF5F6D88),
    Color(0xFFE53935),
    Color(0xFFFC9404),
    Color(0xFF2E9E5B),
    Color(0xFF1353D1),
    Color(0xFF7B3FF2),
    Color(0xFFC7784A),
)
