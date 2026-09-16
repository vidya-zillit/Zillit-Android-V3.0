package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Transient messages: the one place a screen says "that failed" or "that worked".
 *
 * An **overlay**, not a `Scaffold` slot. Most screens in the app are not Scaffolds, and the
 * ones that are bury theirs several composables deep — threading a host through every
 * screen's parameter list to reach it is exactly the duplication this exists to avoid. Wrap
 * the screen in a `Box`, drop a [MessageBar] in it, and any code in the route can post.
 *
 * ```
 * val messages = rememberMessageBar()
 * Box(Modifier.fillMaxSize()) {
 *     MyScreen(onSave = { messages.post(savedText) })
 *     MessageBar(messages)
 * }
 * ```
 */
@Stable
class MessageBarState internal constructor(
    internal val host: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    /**
     * Shows [message], replacing anything already up.
     *
     * Blank input is dropped rather than shown as an empty bar — a failure with no message
     * attached is common, and an empty grey rectangle tells the user less than nothing.
     */
    fun post(message: String?) {
        if (message.isNullOrBlank()) return
        host.currentSnackbarData?.dismiss()
        scope.launch { host.showSnackbar(message, duration = SnackbarDuration.Short) }
    }
}

@Composable
fun rememberMessageBar(): MessageBarState {
    val scope = rememberCoroutineScope()
    val host = remember { SnackbarHostState() }
    return remember(scope, host) { MessageBarState(host, scope) }
}

/**
 * Posts [message] once whenever it changes to something non-null, then calls [onShown].
 *
 * The pairing matters: a view model that sets an error and never has it cleared will keep
 * re-posting it on every recomposition, so consuming is part of showing it.
 */
@Composable
fun MessageBarState.ShowOnce(message: String?, onShown: () -> Unit) {
    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            post(message)
            onShown()
        }
    }
}

@Composable
fun MessageBar(state: MessageBarState, modifier: Modifier = Modifier) {
    SnackbarHost(
        hostState = state.host,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = ZillitTheme.colors.textPrimary,
            contentColor = ZillitTheme.colors.surface,
            shape = MaterialTheme.shapes.medium,
        )
    }
}

/** Convenience for the common `Box { screen; MessageBar }` shape. */
@Composable
fun MessageBarBox(
    state: MessageBarState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
        MessageBar(state, Modifier.align(Alignment.BottomCenter))
    }
}
