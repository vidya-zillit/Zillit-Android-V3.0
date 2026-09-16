package com.zillit.zillitapp.core.ui.html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Makes bare URLs and email addresses in a mail body clickable.
 *
 * A sender who types `zillit.com` into their mail client gets plain text, not an anchor. v2
 * fixes that at render time with a Jsoup traversal; this does the same job without pulling
 * in an HTML parser, by walking the markup tag by tag and only ever rewriting the text
 * **between** tags.
 *
 * Two things are deliberately left alone:
 *
 *  - anything already inside an `<a>`, so an existing link is never nested inside another;
 *  - everything inside a tag, so `src=`, `href=`, `cid:` and `data:` URIs are untouched.
 *
 * This changes only what is displayed. Nothing here is stored or sent.
 */
fun linkifyHtml(html: String): String {
    if (html.isBlank()) return html

    val out = StringBuilder(html.length + LIKELY_GROWTH)
    var anchorDepth = 0

    TAG_OR_TEXT.findAll(html).forEach { match ->
        val token = match.value

        if (token.startsWith("<")) {
            when {
                ANCHOR_OPEN.matches(token) -> anchorDepth++
                ANCHOR_CLOSE.matches(token) -> anchorDepth = (anchorDepth - 1).coerceAtLeast(0)
            }
            out.append(token)
            return@forEach
        }

        // Inside an anchor the text is already a link; leave it exactly as it is.
        out.append(if (anchorDepth > 0) token else linkifyText(token))
    }

    return out.toString()
}

/**
 * Rewrites one run of text.
 *
 * Addresses are matched before URLs: `name@zillit.com` would otherwise be half-swallowed by
 * the `www.`-less host pattern and turned into a broken link.
 */
private fun linkifyText(text: String): String {
    if ('@' !in text && "http" !in text && "www." !in text) return text

    return LINK_CANDIDATE.replace(text) { match ->
        val raw = match.value
        // Sentence punctuation is not part of the address. "See zillit.com." must link
        // the host and leave the full stop behind.
        val trailing = raw.takeLastWhile { it in TRAILING_PUNCTUATION }
        val target = raw.dropLast(trailing.length)

        if (target.isBlank()) {
            raw
        } else {
            val href = when {
                '@' in target && !target.startsWith("http", ignoreCase = true) -> "mailto:$target"
                target.startsWith("www.", ignoreCase = true) -> "https://$target"
                else -> target
            }
            """<a href="$href" target="_blank">$target</a>$trailing"""
        }
    }
}

/** Either a complete tag, or the run of text up to the next one. */
private val TAG_OR_TEXT = Regex("""<[^>]*>|[^<]+""")

private val ANCHOR_OPEN = Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE)
private val ANCHOR_CLOSE = Regex("""</a\s*>""", RegexOption.IGNORE_CASE)

/**
 * A URL or an email address.
 *
 * Bare hosts without a scheme are matched only through the `www.` prefix, which is v2's rule
 * too — matching every `word.word` would turn the end of a sentence into a link.
 */
private val LINK_CANDIDATE = Regex(
    """(?:https?://[^\s<>"']+|www\.[^\s<>"']+|[\w.!#$%&'*+/=?^`{|}~-]+@[\w-]+(?:\.[\w-]+)+)""",
    RegexOption.IGNORE_CASE,
)

private const val TRAILING_PUNCTUATION = ".,;:)]}\"'!?"

/** Rough headroom so the builder does not resize on every anchor it adds. */
private const val LIKELY_GROWTH = 256

/**
 * The plain-text equivalent: the same links, as an annotated string.
 *
 * A plain-text mail has no anchors at all, so every URL and address in one is bare. The
 * annotation carries the target, so the platform opens it — the caller does not have to
 * hit-test the tap itself.
 */
fun linkifyPlainText(text: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (text.isBlank()) return AnnotatedString(text)

    return buildAnnotatedString {
        var cursor = 0

        LINK_CANDIDATE.findAll(text).forEach { match ->
            val raw = match.value
            val trailing = raw.takeLastWhile { it in TRAILING_PUNCTUATION }
            val target = raw.dropLast(trailing.length)
            if (target.isBlank()) return@forEach

            append(text.substring(cursor, match.range.first))

            val href = when {
                '@' in target && !target.startsWith("http", ignoreCase = true) -> "mailto:$target"
                target.startsWith("www.", ignoreCase = true) -> "https://$target"
                else -> target
            }

            withLink(LinkAnnotation.Url(href)) {
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(target)
                }
            }
            append(trailing)
            cursor = match.range.last + 1
        }

        append(text.substring(cursor))
    }
}
