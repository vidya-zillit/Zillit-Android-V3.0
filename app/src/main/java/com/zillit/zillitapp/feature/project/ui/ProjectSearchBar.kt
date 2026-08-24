package com.zillit.zillitapp.feature.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Inline search field for the project list.
 *
 * Built on [BasicTextField] rather than Material's `TextField` because the latter brings
 * a fixed 56dp container and its own label/indicator chrome, which fights the compact
 * pill this needs to be inside a top bar.
 *
 * Filtering is local — the full project list is already cached in Realm, so there is no
 * reason to hit the network per keystroke, and search keeps working offline.
 */
@Composable
fun ProjectSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Opening search should put the cursor in the field — otherwise every user has to
    // tap twice to start typing.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = ZillitTheme.colors.surface,
                shape = RoundedCornerShape(ZillitTheme.shapes.pill),
            )
            .border(
                width = 1.dp,
                color = ZillitTheme.colors.border,
                shape = RoundedCornerShape(ZillitTheme.shapes.pill),
            )
            .padding(horizontal = ZillitTheme.spacing.md)
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = ZillitTheme.spacing.sm),
        ) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.project_search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textTertiary,
                )
            }

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyMedium.copy(
                        color = ZillitTheme.colors.textPrimary,
                    ),
                ),
                cursorBrush = SolidColor(ZillitTheme.colors.brand),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Results filter as you type, so the Search key just dismisses the
                // keyboard to give the list the full screen.
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }

        // Clears the text first and only closes the bar on a second tap — closing
        // immediately would throw away a query the user was mid-way through editing.
        IconButton(
            onClick = {
                if (query.isNotEmpty()) onQueryChange("") else onClose()
            },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(
                    if (query.isNotEmpty()) {
                        R.string.project_search_clear
                    } else {
                        R.string.project_search_close
                    },
                ),
                tint = ZillitTheme.colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
