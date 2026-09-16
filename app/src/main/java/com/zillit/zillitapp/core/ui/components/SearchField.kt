package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * The app's search input.
 *
 * Written nine separate times before this existed — every screen with a list long enough to
 * search grew its own, and they drifted into two different looks: a pill on a sunken ground
 * in the chat and picker screens, a Material `OutlinedTextField` in the calendar ones. Same
 * control, same job, two appearances on adjacent screens.
 *
 * This is the pill, which is the one that reads as *search* rather than as a form field you
 * are meant to fill in, and which sits correctly inside a sheet or under a top bar.
 *
 * @param onClear shown as a trailing ✕ whenever there is something to clear. Defaults to
 *   emptying the field, which is what every caller wanted.
 * @param autoFocus opens the keyboard on first composition — for a screen whose whole
 *   purpose is the search, not for a field sitting above a list the user may want to read.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.action_search),
    autoFocus: Boolean = false,
    imeAction: ImeAction = ImeAction.Search,
    /** For searching by address — gives the keyboard its "@" and ".com" keys. */
    keyboardType: KeyboardType = KeyboardType.Text,
    onSearch: (() -> Unit)? = null,
    onClear: () -> Unit = { onQueryChange("") },
    /**
     * Shown instead of the ✕ when the field is empty.
     *
     * For a search bar that *replaces* something — the project list's title, say — where the
     * trailing control has two jobs: clear what was typed, then dismiss the bar. Passing
     * this keeps the button present on an empty field rather than having it disappear the
     * moment the last character is deleted.
     */
    onCloseWhenEmpty: (() -> Unit)? = null,
    clearDescription: String = stringResource(R.string.action_close),
) {
    val focusRequester = remember { FocusRequester() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )

        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textTertiary,
                )
            }

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = ZillitTheme.colors.textPrimary,
                ),
                cursorBrush = SolidColor(ZillitTheme.colors.brand),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }

        if (query.isNotEmpty() || onCloseWhenEmpty != null) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = clearDescription,
                tint = ZillitTheme.colors.textTertiary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable {
                        if (query.isNotEmpty()) onClear() else onCloseWhenEmpty?.invoke()
                    },
            )
        }
    }

    LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }
}
