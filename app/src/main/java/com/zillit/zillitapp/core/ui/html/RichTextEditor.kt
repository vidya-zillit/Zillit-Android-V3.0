package com.zillit.zillitapp.core.ui.html

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import org.json.JSONObject

/**
 * A handle on a [RichTextEditor] — what the toolbar and the view model talk to.
 *
 * Held by the caller ([rememberRichTextEditorState]) rather than by the composable, because
 * the two things that drive an editor live outside it: the toolbar is a sibling, and "give
 * me the HTML, I am sending this" comes from a view model. A state object keeps both from
 * having to reach for a `WebView` reference.
 */
@Stable
class RichTextEditorState internal constructor(
    initialHtml: String,
) {
    internal var webView: WebView? = null

    /**
     * Content waiting for the page to be ready for it.
     *
     * Null when there is nothing waiting — blank is deliberately **not** queued, because
     * applying it would clear content that arrived in the meantime.
     */
    internal var pendingHtml: String? =
        sanitizeEditableHtml(initialHtml).takeIf { it.isNotBlank() }

    /**
     * Whether the page has finished loading and its script is callable.
     *
     * Tracked separately from `webView != null`, which becomes true the instant the view is
     * constructed — long before `loadDataWithBaseURL` has parsed anything. Calling
     * `zillitSetContent` in that window does nothing at all and reports no error, which is
     * how a signature fetched over the network ends up silently dropped: it arrives after
     * the view exists but before the page is ready.
     */
    internal var pageReady: Boolean = false

    /**
     * The current body, as HTML.
     *
     * Pushed up from the page on every edit, so it is always current without anyone having
     * to ask. That matters for drafts: v2 asks the editor for its content on a timer and,
     * on close, does it inside `runBlocking` on the main thread.
     */
    var html: String by mutableStateOf(initialHtml)
        internal set

    /** Whether the body is empty once tags and `&nbsp;` are discounted. */
    val isEmpty: Boolean
        get() = html.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .isBlank()

    /** Which of bold/italic/… apply where the cursor is, so the toolbar can light up. */
    var activeFormats: Set<RichTextFormat> by mutableStateOf(emptySet())
        internal set

    /**
     * Replaces the whole body.
     *
     * One-shot things only — loading a draft, injecting a signature. Calling this on every
     * keystroke would move the caret to the start of the document each time.
     */
    fun setHtml(value: String) {
        val clean = sanitizeEditableHtml(value)
        html = value

        val target = webView
        if (pageReady && target != null) {
            target.evaluateJavascript("zillitSetContent(${JSONObject.quote(clean)});", null)
        } else {
            // Queued rather than dropped: whatever arrives before the page is ready is
            // applied by `onPageFinished`.
            pendingHtml = clean
        }
    }

    /** Puts HTML at the caret — an inline image, a quoted block. */
    fun insertHtml(value: String) {
        val clean = sanitizeEditableHtml(value)
        webView?.evaluateJavascript("zillitInsertHtml(${JSONObject.quote(clean)});", null)
    }

    fun toggle(format: RichTextFormat) {
        exec(format.command)
    }

    fun setTextColor(color: Color) {
        val hex = "#%06X".format(0xFFFFFF and color.toArgb())
        webView?.evaluateJavascript("zillitExec('foreColor', ${JSONObject.quote(hex)});", null)
    }

    /**
     * Nudges the font size by one step.
     *
     * `execCommand('fontSize')` only accepts HTML's 1–7 scale, so v2's "±2 points, clamped
     * 8–36" is done with an explicit span style instead — which is also what makes the
     * result survive a round-trip through the wire as real CSS.
     */
    fun changeFontSize(deltaPt: Int) {
        webView?.evaluateJavascript("zillitChangeFontSize($deltaPt);", null)
    }

    fun clearFormatting() {
        exec("removeFormat")
    }

    fun focus() {
        webView?.evaluateJavascript("zillitFocus();", null)
    }

    private fun exec(command: String) {
        webView?.evaluateJavascript("zillitExec(${JSONObject.quote(command)}, null);", null)
    }
}

/** The formatting the toolbar offers. Exactly v2's set — no link, align or indent. */
enum class RichTextFormat(internal val command: String) {
    BOLD("bold"),
    ITALIC("italic"),
    UNDERLINE("underline"),
    STRIKETHROUGH("strikeThrough"),
    BULLET_LIST("insertUnorderedList"),
    NUMBERED_LIST("insertOrderedList"),
}

@Composable
fun rememberRichTextEditorState(initialHtml: String = ""): RichTextEditorState =
    remember { RichTextEditorState(initialHtml) }

/**
 * A `contenteditable` body, the way both the mail composer and the signature editor need it.
 *
 * Android has no rich-text input — `EditText` spans do not survive a round trip to HTML, and
 * Compose's `AnnotatedString` has no editor. A WebView is what v2 uses and what every mail
 * client on the platform uses, so this keeps the approach and fixes what was wrong with it:
 *
 *  - **quoted HTML is sanitized** before it reaches the page ([sanitizeEditableHtml]), which
 *    matters because JavaScript is necessarily on in here;
 *  - **content is pushed, not polled** — the page reports every edit, rather than the app
 *    asking for it on a timer and once more inside `runBlocking` at close;
 *  - **the caret's formatting comes back**, so the toolbar can show what is active. v2's
 *    toolbar buttons look identical whether or not bold is on.
 *
 * The signature editor uses the same component with no toolbar, instead of v2's second bare
 * `contenteditable` WebView with no formatting at all.
 */
@Composable
fun RichTextEditor(
    state: RichTextEditorState,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    val colors = ZillitTheme.colors
    val document = remember(colors.isLight, placeholder) {
        editorDocument(
            textColor = colors.textPrimary.toArgb(),
            hintColor = colors.textTertiary.toArgb(),
            linkColor = colors.brand.toArgb(),
            placeholder = placeholder,
        )
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                @SuppressLint("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(AndroidColor.TRANSPARENT)

                addJavascriptInterface(EditorBridge(state), BRIDGE_NAME)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        state.pageReady = true

                        // Whatever was queued while the page was loading — a signature, a
                        // draft, a quoted reply — goes in now. A null pending means there
                        // is nothing to apply, which is not the same as applying blank.
                        state.pendingHtml?.let { pending ->
                            view?.evaluateJavascript(
                                "zillitSetContent(${JSONObject.quote(pending)});",
                                null,
                            )
                            state.pendingHtml = null
                        }
                    }

                    // Nothing in the editor navigates. A tapped link in quoted text would
                    // otherwise replace the composer with a web page and lose the draft.
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                    ): Boolean = true
                }

                state.webView = this
                loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            if (state.webView === view) {
                state.webView = null
                // The next view starts with an unloaded page, so anything set before it
                // finishes must queue again rather than be fired at a dead script.
                state.pageReady = false
            }
            view.removeJavascriptInterface(BRIDGE_NAME)
            view.destroy()
        },
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * The page's way of talking back.
 *
 * `addJavascriptInterface` is the classic Android footgun — it exposes this object's
 * annotated methods to *any* script in the page. That is safe here for two reasons and only
 * two: the page is a local document this file wrote, and every fragment that reaches it has
 * been through [sanitizeEditableHtml]. Neither may quietly stop being true.
 */
private class EditorBridge(private val state: RichTextEditorState) {

    @JavascriptInterface
    fun onContentChanged(html: String) {
        state.html = html
    }

    @JavascriptInterface
    fun onSelectionChanged(formats: String) {
        state.activeFormats = formats.split(',')
            .mapNotNull { name -> RichTextFormat.entries.firstOrNull { it.command == name } }
            .toSet()
    }
}

private fun editorDocument(
    textColor: Int,
    hintColor: Int,
    linkColor: Int,
    placeholder: String,
): String {
    fun Int.css(): String = "#%06X".format(0xFFFFFF and this)

    return """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  html, body { margin: 0; padding: 0; background: transparent; height: 100%; }
  #editor {
    min-height: 100%;
    padding: 12px 16px;
    outline: none;
    color: ${textColor.css()};
    font-family: -apple-system, Roboto, sans-serif;
    font-size: 15px; line-height: 1.5;
    word-wrap: break-word; overflow-wrap: anywhere;
    -webkit-user-select: text; user-select: text;
  }
  #editor a { color: ${linkColor.css()}; }
  #editor img { max-width: 100% !important; height: auto !important; }
  #editor blockquote {
    margin: 0 0 0 8px; padding-left: 10px;
    border-left: 2px solid ${textColor.css()}40;
  }
  /* The hint is CSS, not a placeholder node: a real node would be selectable, and the
     user would end up typing inside it or deleting half of it. */
  #editor:empty:before {
    content: ${JSONObject.quote(placeholder)};
    color: ${hintColor.css()};
    pointer-events: none;
  }
</style>
</head>
<body>
<div id="editor" contenteditable="true"></div>
<script>
  var editor = document.getElementById('editor');
  var FORMATS = ['bold','italic','underline','strikeThrough',
                 'insertUnorderedList','insertOrderedList'];

  function report() { $BRIDGE_NAME.onContentChanged(editor.innerHTML); }

  function reportSelection() {
    var active = [];
    for (var i = 0; i < FORMATS.length; i++) {
      try { if (document.queryCommandState(FORMATS[i])) active.push(FORMATS[i]); } catch (e) {}
    }
    $BRIDGE_NAME.onSelectionChanged(active.join(','));
  }

  function zillitExec(command, value) {
    editor.focus();
    document.execCommand(command, false, value);
    report(); reportSelection();
  }

  function zillitSetContent(html) { editor.innerHTML = html; report(); }

  function zillitInsertHtml(html) {
    editor.focus();
    document.execCommand('insertHTML', false, html);
    report();
  }

  function zillitFocus() { editor.focus(); }

  /* HTML's fontSize command only understands 1-7, so the point size is applied as a style
     on the selection — and on the whole block when nothing is selected, which is what
     "make what I type next bigger" has to mean with no selection to hang a span on. */
  function zillitChangeFontSize(delta) {
    var selection = window.getSelection();
    var target = editor;
    if (selection && selection.rangeCount > 0 && !selection.isCollapsed) {
      document.execCommand('fontSize', false, '7');
      var marked = editor.querySelectorAll('font[size="7"]');
      for (var i = 0; i < marked.length; i++) {
        var span = document.createElement('span');
        span.style.fontSize = clamp(currentSize(marked[i]) + delta) + 'px';
        span.innerHTML = marked[i].innerHTML;
        marked[i].parentNode.replaceChild(span, marked[i]);
      }
      report(); return;
    }
    target.style.fontSize = clamp(currentSize(target) + delta) + 'px';
    report();
  }

  function currentSize(node) {
    return parseInt(window.getComputedStyle(node).fontSize, 10) || 15;
  }

  function clamp(size) { return Math.max(8, Math.min(36, size)); }

  editor.addEventListener('input', report);
  document.addEventListener('selectionchange', reportSelection);
</script>
</body>
</html>
""".trimIndent().replace("${'$'}BRIDGE_NAME", BRIDGE_NAME)
}

private const val BRIDGE_NAME = "ZillitEditor"
