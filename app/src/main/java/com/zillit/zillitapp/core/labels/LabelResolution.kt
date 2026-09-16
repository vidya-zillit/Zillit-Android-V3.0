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
fun String.asLabel(): String = ServerDictionaries(
    labels = LocalLabels.current,
    messages = LocalMessages.current,
    identifiers = LocalIdentifiers.current,
).resolve(this)

/** Null-safe variant for optional server fields. */
@Composable
@ReadOnlyComposable
fun String?.asLabelOrEmpty(): String = this?.asLabel().orEmpty()

/**
 * Resolves only when the text looks like a server key, and leaves prose alone.
 *
 * For the fields that carry either: a conversation's second line is a designation key on a
 * direct chat and a written phrase on a group or an online person, and a call row is a
 * designation key or the words "Group call". Resolving those blindly would push already
 * readable text through the humaniser, which recapitalises it. Server keys are recognisable
 * — lower case, `_`-separated, and by convention suffixed `_label` — so the two cases can
 * be told apart without the caller having to track which is which.
 */
@Composable
@ReadOnlyComposable
fun String.asLabelIfKey(): String = if (looksLikeServerKey()) asLabel() else this

/** Null-safe variant of [asLabelIfKey]. */
@Composable
@ReadOnlyComposable
fun String?.asLabelIfKeyOrEmpty(): String = this?.asLabelIfKey().orEmpty()

/**
 * Whether this reads as a dictionary key rather than something already written for a person.
 *
 * Deliberately strict: a false positive humanises real prose, which is visible, while a
 * false negative shows a key, which is equally visible but only for keys that break the
 * naming convention. Both halves of the convention must hold.
 */
fun String.looksLikeServerKey(): Boolean =
    isNotBlank() &&
        none { it.isWhitespace() } &&
        none { it.isUpperCase() } &&
        (endsWith("_label") || endsWith("_identifier"))

/**
 * Resolves a key **and** fills its `messageElements`.
 *
 * The two halves always travel together — the backend sends a template key and its
 * substitutions in the same payload — so anything rendering server text should use this
 * rather than resolving the key and forgetting the elements, which leaves the placeholder
 * visible in the sentence.
 */
@Composable
@ReadOnlyComposable
fun String.asLabel(elements: List<MessageElement>): String = asLabel().applyElements(elements)

/**
 * The three dictionaries, for a composable that resolves several keys at once.
 *
 * [asLabel] is `@Composable`, so it cannot be called from inside a `map` or a lambda passed
 * to a plain function. Read this once and use [ServerDictionaries.resolve] for the rest.
 */
@Composable
@ReadOnlyComposable
fun rememberDictionaries(): ServerDictionaries = ServerDictionaries(
    labels = LocalLabels.current,
    messages = LocalMessages.current,
    identifiers = LocalIdentifiers.current,
)

/**
 * All three server dictionaries, as one thing to pass around.
 *
 * Exists because resolution is **not** a labels-only lookup: a key can live in any of the
 * three, and the notification path needs messages while a form needs identifiers. Handing a
 * view model one dictionary is how call sites end up resolving against the wrong one and
 * silently falling back to a humanised key.
 */
data class ServerDictionaries(
    val labels: Map<String, String> = emptyMap(),
    val messages: Map<String, String> = emptyMap(),
    val identifiers: Map<String, String> = emptyMap(),
) {
    /**
     * Labels, then messages, then identifiers, then a humanised key.
     *
     * Content is the most specific dictionary, so it wins where a key appears in more
     * than one.
     */
    fun resolve(key: String): String {
        if (key.isBlank()) return ""
        return labels[key] ?: messages[key] ?: identifiers[key] ?: humanise(key)
    }

    /** Resolves and fills the template's slots. */
    fun resolve(key: String, elements: List<MessageElement>): String =
        resolve(key).applyElements(elements)

    /** Resolves, falling back to [fallback] when [key] is blank or resolves to nothing. */
    fun resolveOr(key: String, fallback: String): String =
        resolve(key).ifBlank { resolve(fallback).ifBlank { fallback } }
}

/**
 * Non-composable resolver, for when several keys are resolved at once.
 *
 * [asLabel] is `@Composable`, so it cannot be called from inside a lambda passed to a
 * plain function such as `map` or `joinToString`. Read [LocalLabels] once in the
 * composable and use this for the rest.
 */
fun Map<String, String>.resolveLabel(key: String): String = this[key] ?: humanise(key)

/**
 * Resolves [key], falling back to [fallback] when there is nothing to show.
 *
 * For names that come off the wire and might not: a blank key humanises to a blank string,
 * so a row renders with an icon, its controls, and no name — which looks like a rendering
 * bug rather than like missing data. The fallback is usually the identifier, which at least
 * says which tool it is.
 */
fun Map<String, String>.resolveLabelOr(key: String, fallback: String): String =
    key.takeIf { it.isNotBlank() }
        ?.let { resolveLabel(it) }
        ?.takeIf { it.isNotBlank() }
        ?: resolveLabel(fallback).ifBlank { fallback }

/**
 * Whether a string looks like a server label key rather than a sentence the server wrote.
 *
 * Keys are lower-case, underscore-joined and never contain spaces. Free text fails all
 * three, which is what keeps a real message from being title-cased into nonsense.
 */
fun String.isLabelKey(): Boolean =
    isNotBlank() && none { it.isWhitespace() } && '_' in this && none { it.isUpperCase() }

/**
 * `entertainment_industry_label` → `Entertainment Industry`.
 * Strips the conventional `_label` suffix, then title-cases the remaining words.
 */
fun humanise(key: String): String = key
    .removeSuffix("_label")
    .split('_')
    .filter { it.isNotBlank() }
    .joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
    }
