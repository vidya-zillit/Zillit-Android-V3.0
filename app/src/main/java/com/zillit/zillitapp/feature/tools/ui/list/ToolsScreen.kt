package com.zillit.zillitapp.feature.tools.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.SearchField
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * The Tools tab.
 *
 * A hint card for admins, a search box, and the sections. Every section is a header carrying
 * its own count plus one card holding its tiles, which is what v2's grouped adapter draws.
 */
@Composable
fun ToolsScreen(
    state: ToolsUiState,
    onQueryChange: (String) -> Unit,
    onOpenCustomize: () -> Unit,
    onReorder: () -> Unit,
    onToolClick: (ToolRowState) -> Unit,
    onToolInfo: (ToolRowState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background),
    ) {
        // Admin only, and the only way in to customization — so it is a card that explains
        // where the setting lives rather than a button that assumes the user knows.
        if (state.isAdmin) {
            CustomizeHint(onClick = onOpenCustomize)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ZillitTheme.spacing.lg,
                    vertical = ZillitTheme.spacing.sm,
                ),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                placeholder = stringResource(R.string.tools_search_hint),
                modifier = Modifier.weight(1f),
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ZillitTheme.colors.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, ZillitTheme.colors.border),
            ) {
                IconButton(onClick = onReorder) {
                    Icon(
                        imageVector = Icons.Outlined.SwapVert,
                        contentDescription = stringResource(R.string.tools_reorder_open),
                        tint = ZillitTheme.colors.accentWarm,
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading && state.sections.isEmpty() -> LoadingState()

                // Two different empty states. "Nothing matched" and "you have no tools" are
                // different problems and the second one tells the user who to ask.
                state.isEmpty -> EmptyState(
                    icon = Icons.Outlined.Build,
                    title = stringResource(
                        if (state.hasQuery) R.string.tools_no_match else R.string.tools_none,
                    ),
                    description = "",
                )

                // Every row is its own list item.
                //
                // Emitting a whole section as one item — the card holding a Column of its
                // tools — defeats the point of a lazy list: a twelve-tool section composes
                // and measures all twelve rows at once, off screen or not, and re-measures
                // the lot on every scroll frame. That measured at 100% janky frames with a
                // 90th percentile over a second. The card look is kept by rounding the
                // first and last row of each section instead.
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = ZillitTheme.spacing.xl),
                ) {
                    state.sections.forEach { section ->
                        item(key = "header:${section.identifier}") {
                            SectionHeader(section)
                        }

                        itemsIndexed(
                            items = section.tools,
                            key = { _, tool -> "${section.identifier}:${tool.identifier}" },
                        ) { index, tool ->
                            ToolRow(
                                tool = tool,
                                query = state.query,
                                first = index == 0,
                                last = index == section.tools.lastIndex,
                                onClick = { onToolClick(tool) },
                                onInfo = { onToolInfo(tool) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomizeHint(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ZillitTheme.spacing.lg,
                vertical = ZillitTheme.spacing.sm,
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = ZillitTheme.colors.warningSoft,
    ) {
        Row(
            modifier = Modifier.padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Icon(
                imageVector = Icons.Outlined.Build,
                contentDescription = null,
                tint = ZillitTheme.colors.textPrimary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.tools_customize_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

/** "ACCOUNTS / PAYROLL     9" — the name, and how many tiles are under it. */
@Composable
private fun SectionHeader(section: ToolSectionState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ZillitTheme.spacing.lg,
                end = ZillitTheme.spacing.lg,
                top = ZillitTheme.spacing.md,
                bottom = ZillitTheme.spacing.xs,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = section.title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = ZillitTheme.colors.accentWarm,
        )
        Text(
            text = section.count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = ZillitTheme.colors.textTertiary,
        )
    }
}

@Composable
private fun ToolRow(
    tool: ToolRowState,
    query: String,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onInfo: () -> Unit,
) {
    // Rounded only where the section starts and ends, so a column of independent rows still
    // reads as one card.
    val shape = RoundedCornerShape(
        topStart = if (first) CARD_RADIUS else 0.dp,
        topEnd = if (first) CARD_RADIUS else 0.dp,
        bottomStart = if (last) CARD_RADIUS else 0.dp,
        bottomEnd = if (last) CARD_RADIUS else 0.dp,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg)
            .clip(shape)
            .background(ZillitTheme.colors.surface),
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = ZillitTheme.spacing.md,
                vertical = ZillitTheme.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ZillitTheme.colors.brandSoft,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = null,
                    tint = ZillitTheme.colors.brand,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        val highlightColour = ZillitTheme.colors.warningSoft
        val label = remember(tool.title, query, highlightColour) {
            highlight(tool.title, query, highlightColour)
        }

        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (tool.openable) {
                ZillitTheme.colors.textPrimary
            } else {
                // A tool with no screen here is drawn dimmed, so the tap that explains
                // itself is not a surprise.
                ZillitTheme.colors.textTertiary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (tool.badgeCount > 0) {
            Surface(shape = CircleShape, color = ZillitTheme.colors.danger) {
                Text(
                    text = tool.badgeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ZillitTheme.colors.textOnBrand,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }

        IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = tool.title,
                tint = ZillitTheme.colors.accentWarm,
                modifier = Modifier.size(20.dp),
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
        )
    }

        if (!last) {
            HorizontalDivider(
                color = ZillitTheme.colors.divider,
                thickness = 0.5.dp,
                // Inset past the icon tile, so the divider separates the names.
                modifier = Modifier.padding(start = 64.dp),
            )
        }
    }
}

/** Bolds the part of the name the search matched, so a long list is scannable. */
private fun highlight(
    text: String,
    query: String,
    // Passed in rather than read from the theme, so this stays a plain function and its
    // result can be remembered instead of rebuilt on every frame of a scroll.
    highlightColour: Color,
): AnnotatedString {
    val term = query.trim()
    val start = if (term.isEmpty()) -1 else text.indexOf(term, ignoreCase = true)

    return buildAnnotatedString {
        if (start < 0) {
            append(text)
            return@buildAnnotatedString
        }
        append(text.substring(0, start))
        withStyle(
            SpanStyle(
                fontWeight = FontWeight.Bold,
                background = highlightColour,
            ),
        ) {
            append(text.substring(start, start + term.length))
        }
        append(text.substring(start + term.length))
    }
}

/** The corner the first and last row of a section carry. */
private val CARD_RADIUS = 16.dp
