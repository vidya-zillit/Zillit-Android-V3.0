package com.zillit.zillitapp.core.ui.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Light HTML from the server, rendered as styled Compose text.
 *
 * Several backends hand us copy with a little markup in it — notification bodies, welcome
 * text, some tool descriptions — and it is the same handful of tags every time. Shared here
 * rather than per screen so every surface renders that copy identically, and so a tag the
 * server starts sending is handled in one place.
 *
 * Only `<strong>`/`<b>` and `<br>` appear in practice, so this handles those rather than
 * pulling in a parser. Anything unrecognised is **stripped rather than shown**, which is
 * the failure mode that matters: a stray tag is noise, never information.
 *
 * @param boldStyle how emphasised runs are drawn. Defaults to semi-bold; pass a style with
 *   a colour when a screen wants emphasis to also carry a tint.
 */
fun String.fromSimpleHtml(
    boldStyle: SpanStyle = SpanStyle(fontWeight = FontWeight.SemiBold),
): AnnotatedString {
    val withBreaks = replace(BREAK, "\n")

    return buildAnnotatedString {
        var index = 0
        BOLD.findAll(withBreaks).forEach { match ->
            append(withBreaks.substring(index, match.range.first).stripHtmlTags())
            withStyle(boldStyle) { append(match.groupValues[2].stripHtmlTags()) }
            index = match.range.last + 1
        }
        append(withBreaks.substring(index).stripHtmlTags())
    }.trimEnds()
}

/**
 * Removes tags but keeps whitespace.
 *
 * Trimming per segment ate the space in front of a bold run — "under<strong>Edit My
 * Profile</strong>" rendered as "underEdit My Profile". Leading and trailing blank lines
 * are dealt with once, over the finished string, in [trimEnds].
 */
fun String.stripHtmlTags(): String = replace(ANY_TAG, "")

/** Drops leading and trailing whitespace without losing the spans in between. */
fun AnnotatedString.trimEnds(): AnnotatedString {
    val start = indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return AnnotatedString("")
    val end = indexOfLast { !it.isWhitespace() } + 1
    return subSequence(start, end)
}

// Compiled once. These run over every row of a long list, and Regex() inside the function
// would rebuild the pattern on each recomposition.
private val BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val BOLD = Regex(
    "<(strong|b)>(.*?)</\\1>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val ANY_TAG = Regex("<[^>]*>")
