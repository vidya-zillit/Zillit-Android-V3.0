package com.zillit.zillitapp.feature.project.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.SearchField

/**
 * The project list's search bar.
 *
 * Was its own 140-line `BasicTextField` until the shared [SearchField] grew the one thing
 * that made it different: a trailing button that clears the query first and only dismisses
 * the bar on a second tap. Closing immediately would throw away a query the user was
 * part-way through editing.
 *
 * Filtering is local — the project list is already cached in Realm, so there is no reason to
 * hit the network per keystroke, and search keeps working offline.
 */
@Composable
fun ProjectSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    SearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = stringResource(R.string.project_search_hint),
        // Opening search should put the cursor in the field — otherwise every user has to
        // tap twice to start typing.
        autoFocus = true,
        // Results filter as you type, so the Search key just dismisses the keyboard to give
        // the list the full screen.
        onSearch = { keyboard?.hide() },
        onCloseWhenEmpty = onClose,
        clearDescription = stringResource(
            if (query.isNotEmpty()) R.string.project_search_clear else R.string.project_search_close,
        ),
        modifier = modifier,
    )
}
