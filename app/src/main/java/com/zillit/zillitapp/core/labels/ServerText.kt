package com.zillit.zillitapp.core.labels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A `{search}` → `replacer` substitution, as the backend sends it.
 *
 * Every Zillit response that carries text can also carry these: the text itself is a
 * translatable template ("Vidya Pixel{message_says}") and the elements fill its slots.
 */
@Serializable
data class MessageElement(
    @SerialName("search") val search: String? = null,
    @SerialName("replacer") val replacer: String? = null,
)

/**
 * Server text as it actually arrives: a label **key** plus optional substitutions.
 *
 * The backend never returns finished sentences — `message` is always a dictionary key,
 * and anything variable inside it comes separately in `messageElements`. Rendering the
 * key alone leaks an identifier ("project_language_validation"); rendering the resolved
 * template alone leaves visible placeholders. Both halves are needed, so they travel
 * together.
 */
data class ServerText(
    val key: String,
    val elements: List<MessageElement> = emptyList(),
)

/**
 * The one place server text becomes display text.
 *
 * Resolves [key] across all three dictionaries, then applies [elements]. Use this for
 * *any* server-supplied string — API messages, errors, notification bodies, tool labels.
 * It is the v3 equivalent of v2's `getDataFromLabelKey()`, with the element substitution
 * folded in so the two can no longer be done separately and inconsistently.
 *
 * Resolution order is labels → messages → identifiers, because a key occasionally exists
 * in more than one and the content dictionary is the most specific.
 */
fun ServerText.resolveWith(
    labels: Map<String, String>,
    messages: Map<String, String> = emptyMap(),
    identifiers: Map<String, String> = emptyMap(),
): String {
    // Delegates rather than repeating the order: the lookup chain is the same question
    // wherever it is asked, and two copies of it is how a key resolves differently in a
    // notification than it does on screen.
    return ServerDictionaries(labels, messages, identifiers).resolve(key, elements)
}

/** Convenience for a bare key with no substitutions. */
fun String.asServerText(elements: List<MessageElement> = emptyList()): ServerText =
    ServerText(key = this, elements = elements)

/**
 * Fills `{search}` placeholders.
 *
 * Braces are tolerated on either side: some templates embed `{message_says}` while some
 * elements send the bare token, and accepting both avoids a class of "half-substituted
 * sentence" bugs that are invisible until a specific notification type arrives.
 */
fun String.applyElements(elements: List<MessageElement>): String {
    if (elements.isEmpty()) return this
    var result = this
    elements.forEach { element ->
        val search = element.search?.takeIf { it.isNotBlank() } ?: return@forEach
        val replacer = element.replacer.orEmpty()
        result = result.replace("{$search}", replacer).replace(search, replacer)
    }
    return result
}

/** Composable form — resolves against the dictionaries provided to the tree. */
@Composable
@ReadOnlyComposable
fun ServerText.resolve(): String = resolveWith(
    labels = LocalLabels.current,
    messages = LocalMessages.current,
    identifiers = LocalIdentifiers.current,
)
