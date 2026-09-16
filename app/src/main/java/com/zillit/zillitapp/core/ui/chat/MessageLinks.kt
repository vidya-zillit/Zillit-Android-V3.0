package com.zillit.zillitapp.core.ui.chat

/**
 * Web addresses and phone numbers found in message text.
 *
 * Production chat is full of both — a location's map link, a driver's mobile, a call sheet
 * URL — and v2 makes them tappable for the obvious reason: the alternative is reading a
 * number off the screen and typing it into the dialler while holding the phone.
 *
 * Detection is deliberately conservative. A false positive turns ordinary text into a
 * control that does something surprising when tapped, which is worse than a number the
 * user has to copy: so a phone number needs enough digits to be one, and a web address
 * needs a scheme or a `www.`
 */
data class MessageLink(
    val range: IntRange,
    val text: String,
    /** What to hand the system: `https://…` or `tel:…`. */
    val uri: String,
)

/**
 * A scheme, or `www.`. Anything looser matches "e.g." and every file name with a dot in it.
 */
private val WEB = Regex(
    """(https?://|www\.)[^\s<>"']+""",
    RegexOption.IGNORE_CASE,
)

/**
 * Optional country code, then digits with the usual separators.
 *
 * The digit count is checked afterwards rather than encoded here: a pattern strict enough
 * to count them is unreadable, and the check is one line.
 */
private val PHONE = Regex("""\+?\d[\d\s().\-]{5,}\d""")

/** Below this it is a quantity, a scene number or a year, not a number anyone can call. */
private const val MIN_PHONE_DIGITS = 7

/** Above this it stopped being a phone number and became an id. */
private const val MAX_PHONE_DIGITS = 15

/**
 * Every link in [text], in order and never overlapping.
 *
 * Web addresses are found first and win: a URL with digits in it would otherwise have its
 * tail matched as a phone number, producing two controls over one span.
 */
fun messageLinks(text: String): List<MessageLink> {
    if (text.isBlank()) return emptyList()

    val web = WEB.findAll(text).map { match ->
        // Trailing punctuation belongs to the sentence, not the address.
        val trimmed = match.value.trimEnd('.', ',', ')', ';', ':', '!', '?')
        MessageLink(
            range = match.range.first until (match.range.first + trimmed.length),
            text = trimmed,
            uri = if (trimmed.startsWith("www.", ignoreCase = true)) "https://$trimmed" else trimmed,
        )
    }.toList()

    val phones = PHONE.findAll(text).mapNotNull { match ->
        if (web.any { match.range.first in it.range || it.range.first in match.range }) {
            return@mapNotNull null
        }

        val digits = match.value.count(Char::isDigit)
        if (digits !in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS) return@mapNotNull null

        val trimmed = match.value.trim()
        MessageLink(
            range = match.range.first until (match.range.first + trimmed.length),
            text = trimmed,
            // Spaces and brackets are for reading; the dialler wants neither.
            uri = "tel:" + trimmed.filter { it.isDigit() || it == '+' },
        )
    }.toList()

    return (web + phones).sortedBy { it.range.first }
}
