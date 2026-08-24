package com.zillit.zillitapp.core.notification

import com.zillit.zillitapp.core.labels.LabelRepository
import com.zillit.zillitapp.core.labels.MessageElement
import com.zillit.zillitapp.core.labels.ServerText
import com.zillit.zillitapp.core.labels.applyElements
import com.zillit.zillitapp.core.labels.resolveWith
import com.zillit.zillitapp.core.network.ZillitCrypto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a raw notification into the text a user should actually read.
 *
 * Three transformations, all of which the server expects the client to perform — skip any
 * one and the notification shows an identifier or a ciphertext instead of a sentence:
 *
 *  1. **Decrypt.** Chat bodies arrive as AES hex (`0feddd3e5955dd9f…`) using the same
 *     key material as the API envelope. `reference_data.encrypted` flags them.
 *  2. **Resolve labels.** `action` and non-chat `message` values are label *keys*
 *     (`home_new_unit_chat`), resolved through the server dictionary.
 *  3. **Substitute elements.** Resolved text is templated — `"Vidya Pixel{message_says}"` —
 *     and `messageElements`/`actionElements` supply the `{search}` → `replacer` fills.
 */
@Singleton
class NotificationTextBuilder @Inject constructor(
    private val crypto: ZillitCrypto,
    private val labelRepository: LabelRepository,
) {

    /** Notification title, from the `action` label key. */
    fun title(notification: IncomingNotification): String? {
        val action = notification.action?.takeIf { it.isNotBlank() } ?: return null
        return resolve(action).applyElements(notification.actionElements.toMessageElements())
    }

    /** Notification body. */
    fun body(notification: IncomingNotification): String {
        val raw = notification.message?.takeIf { it.isNotBlank() } ?: return ""

        val decoded = if (notification.isMessageEncrypted) {
            // decrypt() returns the input unchanged when it cannot decode, so a key
            // mismatch degrades to the original rather than throwing mid-notification.
            crypto.decrypt(raw)
        } else {
            // Not encrypted: it is a label key such as `dd_template_updated_message`.
            resolve(raw)
        }

        return decoded.applyElements(notification.messageElements.toMessageElements())
    }

    /**
     * Looks a key up across both dictionaries.
     *
     * Notification `action`/`message` keys live in **messages**, not **labels** — checking
     * only labels made every title fall through to the humanised-key fallback, which is
     * why the tray showed "Home New Unit Chat" instead of the real sentence. Labels are
     * still tried first, since some keys legitimately live there.
     */
    private fun resolve(key: String): String = ServerText(key).resolveWith(
        labels = labelRepository.labels.value,
        messages = labelRepository.messages.value,
        identifiers = labelRepository.identifiers.value,
    )

    /** The push payload's own element type maps onto the shared one. */
    private fun List<TextElement>.toMessageElements(): List<MessageElement> =
        map { MessageElement(search = it.search, replacer = it.replacer) }

}
