package com.zillit.zillitapp.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry

/**
 * Reads the folder the move picker chose, if it has answered yet.
 *
 * The picker is a destination of its own, so it cannot perform the move: the selection lives
 * on the screen that opened it. It writes the chosen folder here instead, and the opener acts
 * on it — via the back-stack entry rather than a callback, so the answer survives the process
 * being killed while the picker is up.
 */
@Composable
fun NavBackStackEntry.folderPickerResult(): String? {
    val picked by savedStateHandle
        .getStateFlow<String?>(EmailFolderPicker.RESULT_FOLDER, null)
        .collectAsStateWithLifecycle()
    return picked
}

/** Clears the answer once acted on, so it is not replayed on the next recomposition. */
fun NavBackStackEntry.clearFolderPickerResult() {
    savedStateHandle[EmailFolderPicker.RESULT_FOLDER] = null
}
