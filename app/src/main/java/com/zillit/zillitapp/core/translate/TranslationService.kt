package com.zillit.zillitapp.core.translate

import android.content.Context
import com.zillit.zillitapp.BuildConfig
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class TranslateRequest(
    @SerialName("model") val model: String,
    @SerialName("text") val text: String,
    @SerialName("lang") val lang: String,
    @SerialName("prompt") val prompt: String,
    @SerialName("max_tokens") val maxTokens: Int,
)

@Serializable
private data class TranslateResponse(
    @SerialName("status") val status: Int? = null,
    /** The translated text. A plain string, not an object. */
    @SerialName("data") val data: String? = null,
)

/**
 * Translation, for every surface that shows a message.
 *
 * **One class, no per-feature copies.** Home, the tool chats, C&C and Email all offer
 * Translate on the same kind of content, and the decision of *what language to translate
 * into* is identical everywhere — so it is answered here rather than at each call site.
 *
 * No OpenAI key ships in the app. Zillit proxies the model behind its own endpoint
 * (`{integrations}/api/v2/chat-gpt/translate`), signed like any other request, so the
 * credential stays server-side. That is why this takes no token parameter.
 */
@Singleton
class TranslationService @Inject constructor(
    private val api: ZillitApi,
    @ApplicationContext private val context: Context,
) {

    /**
     * Whether Translate should be offered at all.
     *
     * v2's `isShowTranslateOption`: only when the device is set to a different language
     * than the project. Offering "translate to English" on an English project is noise, and
     * the crew on a set is usually mixed — that difference is the whole signal.
     */
    fun isAvailable(projectLanguage: String?): Boolean {
        val project = projectLanguage?.takeIf { it.isNotBlank() } ?: return false
        return !deviceLanguage().startsWith(project, ignoreCase = true)
    }

    /**
     * Translates for display.
     *
     * @param isOwnMessage decides the direction, as in v2: **your** message is translated
     *   into the project's language (so the crew can read it), someone else's into your
     *   device language (so you can). One toggle, no language picker.
     *
     * @return the composed body v2 renders — the original, a rule, the word "Translated",
     *   then the translation. Both are kept because a translation is a reading aid, not a
     *   replacement: the original is what was actually said.
     *   Null when nothing usable came back, so the caller can leave the message alone.
     */
    suspend fun translateForDisplay(
        original: String,
        projectLanguage: String?,
        isOwnMessage: Boolean,
    ): String? {
        // Callers compare against ALREADY_IN_LANGUAGE to tell "nothing to do" from "failed".
        if (original.isBlank()) return null

        val target = if (isOwnMessage) {
            projectLanguage?.takeIf { it.isNotBlank() } ?: return null
        } else {
            deviceLanguage()
        }

        val translated = translate(original, target) ?: return null
        // Identical output means it was already in that language. Not a failure, but
        // showing "original / Translated: original" would be nonsense — the caller is
        // told apart from a real error so it can say the right thing.
        if (translated.isBlank() || translated.equals(original, ignoreCase = true)) {
            return ALREADY_IN_LANGUAGE
        }

        return buildString {
            append(original)
            append("\n$SEPARATOR\n")
            append(context.getString(R.string.translated))
            append("\n")
            append(translated)
        }
    }

    /**
     * The raw translation, with no formatting.
     *
     * Exposed for callers that compose their own presentation — a translated document
     * title, say — rather than forcing everything through [translateForDisplay].
     */
    suspend fun translate(text: String, targetLanguage: String): String? {
        val result = api.post<TranslateRequest, TranslateResponse>(
            url = TRANSLATE_URL,
            body = TranslateRequest(
                model = MODEL,
                text = text,
                lang = targetLanguage,
                // The endpoint wants both; v2 sends the message as each.
                prompt = text,
                // v2 sends the character count. That over-allocates for long text, which
                // is harmless, but a two-character message asks for two tokens and comes
                // back empty — hence the floor.
                maxTokens = text.length.coerceAtLeast(MIN_TOKENS),
            ),
            // The same signed variant v2 uses on this call. The endpoint is Zillit's, not
            // OpenAI's, so it takes the ordinary request signature.
            module = ModuleData.WITH_PROJECT_USER_BUCKET_DATA,
        )

        return when (result) {
            is ApiResult.Success -> result.data.data?.replace("\n", "")?.takeIf { it.isNotBlank() }
            is ApiResult.Failure -> {
                ZillitLog.w(TAG, "Translate failed: ${result.error.message}")
                null
            }
        }
    }

    /** The device's language tag, e.g. "en". */
    private fun deviceLanguage(): String =
        Locale.getDefault().language.takeIf { it.isNotBlank() } ?: DEFAULT_LANGUAGE

    companion object {
        /**
         * Returned by [translateForDisplay] when the text is already in the target
         * language. Distinct from null, which means the request failed.
         */
        const val ALREADY_IN_LANGUAGE = ""

        private const val TAG = "TranslationService"

        private val TRANSLATE_URL =
            "${BuildConfig.INTEGRATIONS_BASE_URL}api/v2/chat-gpt/translate"

        /** v2's model. Changing it is a server-side decision, not a client one. */
        private const val MODEL = "gpt-3.5-turbo-instruct"

        private const val SEPARATOR = "--------------"

        /** Below this the model has no room to answer at all. */
        private const val MIN_TOKENS = 32
        private const val DEFAULT_LANGUAGE = "en"
    }
}
