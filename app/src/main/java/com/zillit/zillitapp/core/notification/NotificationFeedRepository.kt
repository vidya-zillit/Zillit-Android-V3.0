package com.zillit.zillitapp.core.notification

import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.socket.EventGate
import com.zillit.zillitapp.core.socket.SocketEvents
import com.zillit.zillitapp.core.socket.SocketManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class NotificationDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("notification_uuid") val uuid: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("sender") val sender: String? = null,
    @SerialName("section") val section: String? = null,
    /** Label key for the headline, e.g. `project_post_granted`. */
    @SerialName("action") val action: String? = null,
    /** Either a label key or an AES-encrypted body — see [NotificationFeedRepository]. */
    @SerialName("message") val message: String? = null,
    @SerialName("path") val path: String? = null,
    @SerialName("created") val created: Long? = null,
    @SerialName("reference_id") val referenceId: String? = null,
    @SerialName("message_read") val messageRead: Boolean? = null,
    /** Carries `encrypted` and the `{{sender}}`-style fills for the templates above. */
    @SerialName("reference_data") val referenceData: ReferenceData? = null,
)

@Serializable
private data class NotificationListResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("data") val data: List<NotificationDto>? = null,
)

/**
 * One row, ready to render — fully resolved.
 *
 * Both strings come out of [NotificationTextBuilder], so the label lookup, the decryption
 * and the `{{sender}}` substitution have already happened. The screen only formats.
 */
data class AppNotification(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val isRead: Boolean,
)

/**
 * The server's notification feed — what the Zillit logo opens.
 *
 * Distinct from [NotificationRepository], which is the **local** store this device builds
 * from FCM and socket traffic and derives badges from. This one is the server's own list:
 * read on demand, paged, and deletable. Keeping them apart matters because deleting from
 * the feed must not silently rewrite the badge ledger.
 *
 * Not cached in Realm: the list is opened, acted on and closed, and the number that has to
 * be always-correct is the badge, which comes from elsewhere.
 */
@Singleton
class NotificationFeedRepository @Inject constructor(
    private val api: ZillitApi,
    private val textBuilder: NotificationTextBuilder,
    private val socket: SocketManager,
    private val session: SessionStore,
) {

    /**
     * One page, newest first.
     *
     * @param before the oldest `created` already held; 0 for the first page. A cursor over
     *   `created`, the same shape the chat list uses.
     */
    suspend fun page(before: Long = 0): List<NotificationDto> {
        val cursor = before.takeIf { it > 0 } ?: System.currentTimeMillis()

        val result = api.get<NotificationListResponse>(
            url = "$LIST$cursor/previous",
            module = ModuleData.WITH_PROJECT_USER_ID,
        )

        return when (result) {
            is ApiResult.Success -> result.data.data.orEmpty()
            is ApiResult.Failure -> {
                ZillitLog.w(TAG, "Notification fetch failed: ${result.error.message}")
                emptyList()
            }
        }
    }

    /**
     * Removes one notification.
     *
     * v2 sends this over the socket when connected and falls back to HTTP otherwise. Only
     * the HTTP call is made here: same server-side effect, it reports success or failure
     * directly, and it works when the socket is down — which is exactly when someone is
     * most likely to be clearing a backlog.
     */
    suspend fun delete(id: String, referenceId: String? = null): Boolean {
        val result = api.delete<Unit>(
            url = "${ApiEndpoints.Notification.MARK_DELETE}$id/${System.currentTimeMillis()}",
            module = ModuleData.WITH_PROJECT_USER_ID,
            query = referenceId?.takeIf { it.isNotBlank() }
                ?.let { mapOf("referenceId" to it) }
                .orEmpty(),
        )
        return result.succeeded(id)
    }

    /**
     * Clears the whole list for this project.
     *
     * Socket-only, unlike the single delete. v2 declares a `delete-all` REST endpoint but
     * never calls it — every clear goes out as `notification:delete:global` — so the HTTP
     * route is unverified against the live server and is not used here either.
     *
     * `segment = global_label` is what makes it project-wide rather than scoped to one
     * tool; the server reads the timestamp as "delete everything up to this moment", so a
     * notification arriving mid-request survives instead of being silently swallowed.
     */
    suspend fun deleteAll(): Boolean {
        val projectId = session.activeProject.value?.projectId
        if (projectId.isNullOrEmpty()) {
            ZillitLog.w(TAG, "Delete all skipped — no active project")
            return false
        }

        val ack = socket.emitWithAck(
            event = SocketEvents.NOTIFICATION_DELETE_GLOBAL,
            payload = buildJsonObject {
                put("project_id", projectId)
                put("segment", GLOBAL_SEGMENT)
                put("timestamp", System.currentTimeMillis())
            },
        )

        // A missing ack means the socket was down or the server never answered — treated as
        // a failure so the caller re-reads rather than leaving an empty list on screen that
        // refills on the next visit.
        val success = ack?.get("success")?.jsonPrimitive?.booleanOrNull == true
        if (!success) ZillitLog.w(TAG, "Delete all not acknowledged: $ack")
        return success
    }

    private fun ApiResult<*>.succeeded(what: String): Boolean = when (this) {
        is ApiResult.Success -> true
        is ApiResult.Failure -> {
            ZillitLog.w(TAG, "Notification delete failed for $what: ${error.message}")
            false
        }
    }

    /**
     * Fires whenever a notification for the **active project** lands over the socket.
     *
     * The feed is server-paged, so there is nothing sensible to merge locally — the
     * screen re-reads its first page, which is what v2 does when
     * `notificationGlobalProvider` emits. Silent notifications are included: they change
     * the list even though they never reach the tray.
     *
     * [BadgeSyncCoordinator] listens to the same events for the badge ledger. Two
     * collectors on one event is fine, and keeping them separate means opening this
     * screen cannot perturb badge accounting.
     */
    fun incoming(): Flow<Unit> = merge(
        socket.observe(
            event = SocketEvents.NOTIFICATION_SAVE,
            deserializer = NotificationPayload.serializer(),
            gate = EventGate.AccountLevel,
        ),
        socket.observe(
            event = SocketEvents.NOTIFICATION_SILENT,
            deserializer = NotificationPayload.serializer(),
            gate = EventGate.AccountLevel,
        ),
    )
        .filter { it.projectId == session.activeProject.value?.projectId }
        // Not this device's own echo. Marking the list read emits a read, and the server
        // syncs that back on the very channels this listens to — so without the filter the
        // screen refreshes itself, marks read again, and loops: a permanent loader over a
        // list that reloads forever.
        .filter { it.referenceData?.self != true }
        .filter { it.referenceData?.selfDeviceId != session.deviceId }
        .map { }

    /**
     * Title and body, resolved.
     *
     * Delegates to [NotificationTextBuilder] — the same code the push tray uses — rather
     * than re-deriving the rules here. A feed row and a push notification describe the
     * same event and must read identically; two implementations would guarantee they
     * eventually don't.
     */
    fun readableText(dto: NotificationDto): Pair<String, String> {
        val incoming = dto.toIncoming()
        return textBuilder.title(incoming).orEmpty() to textBuilder.body(incoming)
    }

    /**
     * The feed row as the text builder wants it.
     *
     * Only the fields that affect text are filled; routing and badge fields are the local
     * store's business, not the feed's.
     */
    private fun NotificationDto.toIncoming() = IncomingNotification(
        uuid = uuid.orEmpty(),
        projectId = projectId.orEmpty(),
        module = com.zillit.zillitapp.core.badge.BadgeModule.NOTIFICATION,
        scopeId = "",
        action = action,
        message = message,
        isMessageEncrypted = referenceData?.encrypted == true,
        messageElements = referenceData?.messageElements.orEmpty(),
        actionElements = referenceData?.actionElements.orEmpty(),
        isSilent = false,
        createdAt = created ?: 0,
    )

    private companion object {
        const val TAG = "NotificationFeed"

        /** `.../project/notifications/{cursor}/previous` — v2's `GET_NOTIFICATION_URL`. */
        val LIST = "${ApiEndpoints.Notification.BASE_URL}project/notifications/"

        /** v2's `Constants.GLOBAL_LABEL` — the whole project, not one tool. */
        const val GLOBAL_SEGMENT = "global_label"
    }
}
