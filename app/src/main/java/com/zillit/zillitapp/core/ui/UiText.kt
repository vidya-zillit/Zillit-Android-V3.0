package com.zillit.zillitapp.core.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.core.labels.LocalLabels
import com.zillit.zillitapp.core.labels.resolveWith

/**
 * Text that a non-UI layer can produce without being able to resolve it.
 *
 * The app is multi-language, so no user-facing string may be hardcoded — but ViewModels
 * and repositories have no `Context` and must not resolve strings themselves anyway
 * (a string resolved in a ViewModel is captured in the app's locale at that moment and
 * will not update if the user changes language while the screen is open).
 *
 * So the lower layers emit a [UiText] and only the composable resolves it, at render
 * time, in the current locale.
 *
 * [Dynamic] exists for one narrow case: text the *server* produced, such as a backend
 * validation message. That text is already localized by the backend, so it passes
 * through untranslated. Never use [Dynamic] for text written in the app.
 */
sealed interface UiText {

    data class Resource(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Plural(
        @PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    /** Server-supplied, already-localized text. Not for app-authored copy. */
    data class Dynamic(val value: String) : UiText

    /**
     * A server message KEY, resolved against the label dictionaries at render time.
     *
     * Backend errors arrive as keys (`project_language_validation`), so treating them as
     * display text leaks an identifier into the UI.
     */
    data class ServerMessage(val text: com.zillit.zillitapp.core.labels.ServerText) : UiText

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Resource(id, args.toList())

        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, args.toList())
    }
}

@Composable
@ReadOnlyComposable
fun UiText.resolve(): String = when (this) {
    is UiText.Resource ->
        if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())

    is UiText.Plural -> {
        val resources = LocalContext.current.resources
        if (args.isEmpty()) {
            resources.getQuantityString(id, count)
        } else {
            resources.getQuantityString(id, count, *args.toTypedArray())
        }
    }

    is UiText.Dynamic -> value

    is UiText.ServerMessage -> text.resolveWith(
        labels = LocalLabels.current,
        messages = com.zillit.zillitapp.core.labels.LocalMessages.current,
        identifiers = com.zillit.zillitapp.core.labels.LocalIdentifiers.current,
    )
}
