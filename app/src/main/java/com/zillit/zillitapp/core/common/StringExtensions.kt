package com.zillit.zillitapp.core.common

/**
 * First alphabetic character, uppercased.
 *
 * Skips digits, punctuation and whitespace, so a project named "2 States" yields "S" and
 * "  the crew" yields "T" — taking `first()` blindly would produce "2" or a space, which
 * reads as a broken avatar rather than an initial.
 *
 * Returns null when there is no letter at all; callers decide the fallback rather than
 * having a placeholder character baked in here.
 */
fun String.firstAlphabeticOrNull(): Char? =
    firstOrNull { it.isLetter() }?.uppercaseChar()

/**
 * Up to [count] initials, taken from the first alphabetic character of each word.
 *
 * "QA ZL Test001" → "QZ"; "2 States" → "S". Words with no letter are skipped entirely, so
 * a leading number does not consume one of the slots.
 *
 * @param fallback used when the text contains no letters at all.
 */
fun String.initials(count: Int = 2, fallback: String = "?"): String =
    trim()
        .split(Regex("\\s+"))
        .mapNotNull { it.firstAlphabeticOrNull() }
        .take(count)
        .joinToString("")
        .ifEmpty { fallback }

/**
 * Capitalises the first letter, leaving the rest untouched.
 *
 * Server preset lists (languages, countries) arrive lowercase — "english" — and rendering
 * them raw looks like a bug next to the app's own sentence-cased copy. Deliberately does
 * NOT lowercase the remainder, which would wreck acronyms and names like "UK" or "McRae".
 */
fun String.capitalizeFirst(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
