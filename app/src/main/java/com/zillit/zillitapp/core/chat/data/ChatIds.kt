package com.zillit.zillitapp.core.chat.data

/**
 * The `unique_id` every outgoing chat row is keyed on.
 *
 * Matches v2's generator exactly — the timestamp and sender id concatenated, then the
 * characters shuffled. It looks arbitrary, and it is: the value only has to be unique per
 * sender, and the server treats it as an opaque de-duplication key. Changing the shape
 * would be safe for us and unsafe for anything server-side that pattern-matches on it, so
 * it stays as v2 has it.
 *
 * Lives in its own file rather than inside `ChatRepository` because the upload queue mints
 * ids too, and a queue row and its chat row must agree on the id.
 */
object ChatIds {
    fun newUniqueId(senderId: String): String =
        (System.currentTimeMillis().toString() + senderId).toList().shuffled().joinToString("")
}
