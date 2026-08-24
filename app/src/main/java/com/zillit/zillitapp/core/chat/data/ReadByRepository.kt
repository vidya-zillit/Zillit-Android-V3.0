package com.zillit.zillitapp.core.chat.data

import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** One person's state for a message. */
@Serializable
data class ReadByEntryDto(
    @SerialName("userId") val userId: String? = null,
    /** Epoch millis; absent or 0 when not read. */
    @SerialName("read_time") val readTime: Long? = null,
    /** Epoch millis; 0 means it never reached their device. */
    @SerialName("delivered") val delivered: Long? = null,
)

@Serializable
data class ReadByDataDto(
    @SerialName("message_read_by") val readBy: List<ReadByEntryDto> = emptyList(),
    @SerialName("message_unread_by") val unreadBy: List<ReadByEntryDto> = emptyList(),
)

@Serializable
data class ReadByResponseDto(
    @SerialName("status") val status: Int? = null,
    @SerialName("data") val data: ReadByDataDto? = null,
)

/**
 * Who has read a message, for every chat surface.
 *
 * v2 answers this question in one screen with a twenty-branch `when` over a `Navigation`
 * enum, each branch naming a different constant for the same endpoint shape. Here the
 * module supplies the URL — the same reasoning that made [ChatModule] data rather than a
 * class per chat — so a new surface adds an enum entry, not a branch.
 */
@Singleton
class ReadByRepository @Inject constructor(
    private val api: ZillitApi,
) {

    /**
     * @param commentId set to ask about a reply rather than the message itself. v2 passes
     *   it as a query parameter on the same endpoint.
     */
    suspend fun fetch(
        module: ChatModule,
        messageId: String,
        commentId: String? = null,
    ): ReadByDataDto? {
        val result = api.get<ReadByResponseDto>(
            url = "${module.readBy}/$messageId",
            module = ModuleData.WITH_PROJECT_USER_ID,
            query = commentId?.takeIf { it.isNotBlank() }
                ?.let { mapOf("commentId" to it) }
                .orEmpty(),
        )

        return when (result) {
            is ApiResult.Success -> result.data.data
            is ApiResult.Failure -> {
                ZillitLog.w(TAG, "Read-by fetch failed for $messageId: ${result.error.message}")
                null
            }
        }
    }

    /**
     * Nudges everyone who has not read it yet.
     *
     * Fire and forget, as in v2: the button's job is to send the reminder, and a failure
     * here does not change anything on screen worth reporting.
     */
    suspend fun notifyUnread(
        module: ChatModule,
        scopeId: String,
        messageId: String,
        commentId: String? = null,
    ) {
        val body = buildMap {
            put("messageId", messageId)
            commentId?.takeIf { it.isNotBlank() }?.let { put("commentId", it) }
        }

        val result = api.post<Map<String, String>, ReadByResponseDto>(
            url = module.notify(scopeId),
            body = body,
            // Matches v2's `WITH_PROJECT_USER_BUCKET_DATA` on this call; the server
            // rejects the plainer variant.
            module = ModuleData.WITH_PROJECT_USER_BUCKET_DATA,
            query = body,
        )

        if (result is ApiResult.Failure) {
            ZillitLog.w(TAG, "Notify failed for $messageId: ${result.error.message}")
        }
    }

    private companion object {
        const val TAG = "ReadByRepository"
    }
}
