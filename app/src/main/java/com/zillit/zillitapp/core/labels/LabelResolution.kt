package com.zillit.zillitapp.core.labels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The server label dictionary, provided once at the root of the tree.
 *
 * Resolution deliberately happens in the composable rather than in a ViewModel or mapper.
 * A label resolved lower down would be frozen at the language in force when the object
 * was built; resolving at render means a language change re-renders every label without
 * re-fetching or rebuilding any state.
 */
val LocalLabels = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** Backend status/error message dictionary — where API `message` keys resolve. */
val LocalMessages = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** Field/section identifier dictionary, used by dynamic forms. */
val LocalIdentifiers = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * Resolves a server label key to display text.
 *
 * Falls back to a humanised form of the key rather than showing it raw: an unresolved
 * `entertainment_industry_label` renders as "Entertainment Industry", which is wrong-ish
 * but readable, instead of leaking an identifier into the UI. Missing keys happen in
 * practice — a first run before the dictionary is fetched, or a key the backend added
 * after the cached dictionary was built.
 */
@Composable
@ReadOnlyComposable
fun String.asLabel(): String = LocalLabels.current.resolveLabel(this)

/** Null-safe variant for optional server fields. */
@Composable
@ReadOnlyComposable
fun String?.asLabelOrEmpty(): String = this?.asLabel().orEmpty()

/**
 * Non-composable resolver, for when several keys are resolved at once.
 *
 * [asLabel] is `@Composable`, so it cannot be called from inside a lambda passed to a
 * plain function such as `map` or `joinToString`. Read [LocalLabels] once in the
 * composable and use this for the rest.
 */
fun Map<String, String>.resolveLabel(key: String): String = this[key] ?: humanise(key)

/**
 * `entertainment_industry_label` → `Entertainment Industry`.
 * Strips the conventional `_label` suffix, then title-cases the remaining words.
 */
private fun humanise(key: String): String = key
    .removeSuffix("_label")
    .split('_')
    .filter { it.isNotBlank() }
    .joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
    }
