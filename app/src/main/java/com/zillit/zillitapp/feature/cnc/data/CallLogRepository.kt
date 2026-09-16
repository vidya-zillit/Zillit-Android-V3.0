package com.zillit.zillitapp.feature.cnc.data

import com.zillit.zillitapp.BuildConfig
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.session.SessionStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The call log behind the Calls tab.
 *
 * Read-only for now: **calling itself is deferred**, so nothing here starts a call. The log
 * is a separate service from chat and a separate endpoint from everything else in C&C, which
 * is why it is its own repository rather than another method on `CncRepository`.
 *
 * Not cached in Realm, unlike messages. A call log is read far less often than a thread, it
 * is useless offline — you cannot return a call with no network — and it is paged by
 * timestamp, so a local copy would need its own windowing to say anything a fetch does not.
 */
@Singleton
class CallLogRepository @Inject constructor(
    private val api: ZillitApi,
    private val session: SessionStore,
) {

    /**
     * One page of calls, newest first.
     *
     * @param before page backwards from this moment. Null starts at now.
     * @param missedOnly the Missed filter, which is a server-side query rather than a
     *   filter over the recent list — a missed call can be older than the last page of
     *   recents, so filtering locally would hide it.
     */
    suspend fun page(before: Long? = null, missedOnly: Boolean = false): CallLogPageResult {
        val at = before ?: System.currentTimeMillis()
        val url = buildString {
            append(CALLS)
            append('/').append(at)
            append('/').append(PREVIOUS)
            if (missedOnly) append("?missed=yes")
        }

        val result = api.get<CallLogResponse>(url, ModuleData.WITH_PROJECT_USER_ID)
        if (result !is ApiResult.Success) {
            ZillitLog.w(TAG, "call log fetch failed")
            return CallLogPageResult(failed = true)
        }

        val calls = result.data.data?.calls.orEmpty()

        // Where the next page starts: the oldest row here, since we page backwards.
        val cursor = calls.mapNotNull { it.startTime }.minOrNull()

        // Three ways this is the end, and v2 learned each of them the hard way.
        val hasMore = when {
            // A short page is simply the last one.
            calls.size < PAGE_SIZE -> false
            // No usable timestamp means no cursor to ask with.
            cursor == null -> false
            // The server's boundary is inclusive, so a cursor that did not move would
            // return this same page forever.
            before != null && cursor >= before -> false
            else -> true
        }

        return CallLogPageResult(
            calls = calls,
            nextCursor = cursor,
            hasMore = hasMore,
            // The server's own count, which is what the Missed chip should show — the
            // number in the page loaded so far is not the same question.
            totalRecords = result.data.data?.totalRecords ?: calls.size,
        )
    }

    /**
     * Empty the log.
     *
     * Clears only the list the user is looking at — the Missed tab does not wipe the
     * recents behind it, which is what v2 does and what the two separate paths are for.
     */
    suspend fun clear(missedOnly: Boolean): Boolean {
        val url = "$CALLS/${if (missedOnly) "missed" else "recent"}"
        return api.delete<CallLogResponse>(url, ModuleData.WITH_PROJECT_USER_ID) is ApiResult.Success
    }

    /** Whether a log row is a call this user made rather than received. */
    fun CallLogDto.isOutgoing(): Boolean =
        outgoingCall == true || fromUserId == session.activeProject.value?.userId

    /** The other party — whoever on the row is not this user. */
    fun CallLogDto.otherPartyId(): String {
        val me = session.activeProject.value?.userId
        return listOfNotNull(fromUserId, toUserId).firstOrNull { it != me }
            ?: toUserId.orEmpty()
    }

    private companion object {
        const val TAG = "CallLogRepository"
        val CALLS = "${BuildConfig.CALLING_BASE_URL}/api/v2/call"

        /** The log pages backwards from a timestamp; there is no "next" from now. */
        const val PREVIOUS = "previous"

        /** v2's `API_PAGINATION_LIMIT`. A full page means there is more behind it. */
        const val PAGE_SIZE = 50
    }
}

/**
 * One page, and what the caller needs to ask for the next.
 *
 * [hasMore] is decided here rather than by the caller, because getting it wrong has two
 * distinct failure modes: stopping early truncates the log silently, and never stopping
 * re-requests the same page forever against an inclusive server boundary.
 */
data class CallLogPageResult(
    val calls: List<CallLogDto> = emptyList(),
    val nextCursor: Long? = null,
    val hasMore: Boolean = false,
    /** The server's total, not the number loaded. */
    val totalRecords: Int = 0,
    val failed: Boolean = false,
)

@Serializable
data class CallLogResponse(
    val status: Int? = null,
    val message: String? = null,
    val data: CallLogPage? = null,
)

@Serializable
data class CallLogPage(
    val totalRecords: Int? = null,
    val calls: List<CallLogDto> = emptyList(),
)

/** One row of the call log. */
@Serializable
data class CallLogDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("from_user_id") val fromUserId: String? = null,
    @SerialName("to_user_id") val toUserId: String? = null,
    @SerialName("chat_room_id") val chatRoomId: String? = null,
    @SerialName("chat_room_name") val chatRoomName: String? = null,
    @SerialName("start_time") val startTime: Long? = null,
    @SerialName("call_duration") val callDuration: Long? = null,
    /** "audio" or "video". */
    @SerialName("call_type") val callType: String? = null,
    @SerialName("call_uuid") val callUuid: String? = null,
    val outgoingCall: Boolean? = null,
    val incomingCall: Boolean? = null,
    val missedCall: Boolean? = null,
    @SerialName("is_random_call") val isRandomCall: Boolean? = null,
    val deleted: Long? = null,
    /**
     * Who was on the call.
     *
     * Sent in **two shapes**, sometimes mixed in one response: plain id strings on older
     * rows and objects on newer ones. A strict object model made a single string-shaped row
     * fail the whole decode and the list went blank — v2 hit exactly that, which is why the
     * flexible serializer below exists rather than a plain list.
     */
    @SerialName("call_users")
    val callUsers: List<@Serializable(with = CallLogUserFlexSerializer::class) CallLogUserDto> =
        emptyList(),

    /**
     * Per-person attendance, which [callUsers] does not carry.
     *
     * This is the difference between "five people were invited" and "three joined, one
     * declined and one never picked up" — the whole point of opening a call's details.
     */
    val participants: List<CallParticipantDto> = emptyList(),
) {
    val isGroupCall: Boolean get() = !chatRoomId.isNullOrBlank()
}

/** One person's part in a call. */
@Serializable
data class CallParticipantDto(
    @SerialName("user_id") val userId: String? = null,
    /** `caller`, `left`, `declined`, `incall`, `ringing`, `invited`. */
    val status: String? = null,
    @SerialName("answered_at") val answeredAt: Long? = null,
    @SerialName("invited_by") val invitedBy: String? = null,
    val missed: Boolean? = null,
    /** The server's own copy of the name; the directory is preferred where it has one. */
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("is_guest") val isGuest: Boolean? = null,
    /** Milliseconds actually connected. */
    @SerialName("total_ms") val totalMs: Long? = null,
)

@Serializable
data class CallLogUserDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("current_status") val currentStatus: String? = null,
)

/** Normalises a bare id string into `{user_id: …}` so one old row cannot break the page. */
object CallLogUserFlexSerializer :
    JsonTransformingSerializer<CallLogUserDto>(CallLogUserDto.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive) buildJsonObject { put("user_id", element.content) } else element
}
