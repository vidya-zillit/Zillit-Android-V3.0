package com.zillit.zillitapp.core.ui.html

/**
 * Strips the parts of an HTML fragment that can execute.
 *
 * This exists for one situation: the compose editor. Replying quotes the original mail
 * **into a `contenteditable` WebView that has JavaScript enabled** — it has to, that is how
 * the formatting toolbar works — so a script in the quoted body would run with access to
 * the editor's bridge. v2 injects the quoted body raw.
 *
 * Deliberately a blunt instrument. A regex is not an HTML parser and cannot be one, so this
 * removes whole categories rather than trying to be clever about which `onclick` is
 * harmless: script and style-injection elements, every `on*` handler, and `javascript:`
 * targets. Formatting — the thing the user actually wants preserved — is untouched.
 *
 * [HtmlBodyView] does **not** use this: it renders with JavaScript off, where the markup is
 * inert anyway, and stripping it there would silently mangle mail the user wants to read.
 */
fun sanitizeEditableHtml(html: String): String {
    if (html.isBlank()) return html

    return html
        .replace(SCRIPT_ELEMENT, "")
        .replace(DANGEROUS_ELEMENT, "")
        .replace(EVENT_ATTRIBUTE, "")
        .replace(JAVASCRIPT_URL, "\"#\"")
}

/** `<script>…</script>`, including an unclosed one at the end of the fragment. */
private val SCRIPT_ELEMENT = Regex(
    """<script\b[^>]*>[\s\S]*?(?:</script>|$)""",
    RegexOption.IGNORE_CASE,
)

/**
 * Elements that load or execute something of their own.
 *
 * `<style>` is in here because a quoted mail's stylesheet leaks out and restyles the rest of
 * the compose window — the user's own text included.
 */
private val DANGEROUS_ELEMENT = Regex(
    """</?(?:iframe|object|embed|applet|form|link|meta|style|base)\b[^>]*>""",
    RegexOption.IGNORE_CASE,
)

/** `onclick=…`, `onerror=…`, quoted or bare. */
private val EVENT_ATTRIBUTE = Regex(
    """\son[a-z]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)""",
    RegexOption.IGNORE_CASE,
)

private val JAVASCRIPT_URL = Regex(
    """["']\s*javascript:[^"']*["']""",
    RegexOption.IGNORE_CASE,
)
