package com.zillit.zillitapp.core.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Where an in-thread search has got to.
 *
 * @param matchIds the ids of matching messages, oldest first — the same order as the feed,
 *   so stepping through them moves consistently up or down the thread.
 * @param currentIndex which match is highlighted, or -1 before the first search runs.
 */
data class ChatSearchState(
    val query: String = "",
    val matchIds: List<String> = emptyList(),
    val currentIndex: Int = -1,
) {
    val currentId: String? = matchIds.getOrNull(currentIndex)

    /** "3/17" — position and total, which is what tells you whether to keep pressing. */
    val positionLabel: String
        get() = if (matchIds.isEmpty()) "0" else "${currentIndex + 1}/${matchIds.size}"

    fun next(): ChatSearchState =
        if (matchIds.isEmpty()) this
        else copy(currentIndex = (currentIndex + 1).mod(matchIds.size))

    fun previous(): ChatSearchState =
        if (matchIds.isEmpty()) this
        else copy(currentIndex = (currentIndex - 1).mod(matchIds.size))
}

/**
 * The search bar shown in place of the unit strip.
 *
 * v2 puts up/down arrows and a hit count beside the field, and that is the part that
 * matters: on a thread of several thousand messages, finding a match is useless without a
 * way to walk between them, and matches are scattered rather than adjacent.
 */
@Composable
fun ChatSearchBar(
    state: ChatSearchState,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
                .background(ZillitTheme.colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (state.query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textTertiary,
                    )
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyMedium.copy(
                            color = ZillitTheme.colors.textPrimary,
                        ),
                    ),
                    cursorBrush = SolidColor(ZillitTheme.colors.brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.query.isNotBlank()) {
                Text(
                    text = state.positionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }

        // Up goes to older, down to newer — matching how the thread reads, not how a list
        // index counts.
        Icon(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = stringResource(R.string.chat_search_previous),
            tint = if (state.matchIds.isEmpty()) {
                ZillitTheme.colors.textTertiary
            } else {
                ZillitTheme.colors.textSecondary
            },
            modifier = Modifier
                .size(24.dp)
                .clickable(enabled = state.matchIds.isNotEmpty(), onClick = onPrevious),
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(R.string.chat_search_next),
            tint = if (state.matchIds.isEmpty()) {
                ZillitTheme.colors.textTertiary
            } else {
                ZillitTheme.colors.textSecondary
            },
            modifier = Modifier
                .size(24.dp)
                .clickable(enabled = state.matchIds.isNotEmpty(), onClick = onNext),
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.chat_search_close),
            tint = ZillitTheme.colors.textSecondary,
            modifier = Modifier.size(22.dp).clickable(onClick = onClose),
        )
    }
    HorizontalDivider(color = ZillitTheme.colors.divider)
}
