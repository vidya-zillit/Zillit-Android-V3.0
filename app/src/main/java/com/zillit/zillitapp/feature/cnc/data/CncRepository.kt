package com.zillit.zillitapp.feature.cnc.data

import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.CncConversationEntity
import com.zillit.zillitapp.core.database.entity.CncMessageEntity
import com.zillit.zillitapp.core.database.entity.PendingReceiptEntity
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.network.ZillitCrypto
import com.zillit.zillitapp.core.session.SessionStore
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.Sort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place a socket chat's data lives — C&C and Budget both.
 *
 * ### Realm first, socket instant
 * Every read comes from Realm and every write lands in Realm before it goes anywhere, so a
 * thread opens instantly, works offline, and survives the process being killed. The socket
 * is what keeps Realm current; it is never what the screen reads from.
 *
 * ### Why sending is three steps
 * A socket chat has no POST that returns the stored object. So a send is: write an
 * optimistic row, emit, then fold the acknowledgement back onto that same row. The row is
 * keyed on a `unique_id` this device generates, which is what makes the third step an
 * update rather than a duplicate. If the ack never comes the row stays pending and the
 * queue retries it — the message is never lost and never sent twice.
 *
 * ### Plain text
 * Bodies are sent and stored in plain text. v2's send path does the same and its decrypt
 * function is commented out top to bottom; only its edit path still encrypts, which is why
 * an edited message reaches every client there as ciphertext. Section 7 of the audit.
 */
@Singleton
class CncRepository @Inject constructor(
    private val api: ZillitApi,
    private val socket: CncSocketDataSource,
    private val realmProvider: RealmProvider,
    private val session: SessionStore,
    private val crypto: ZillitCrypto,
) {

    private val realm get() = realmProvider.realm

    private val currentUserId: String get() = session.activeProject.value?.userId.orEmpty()
    private val currentProjectId: String get() = session.activeProject.value?.projectId.orEmpty()

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * One thread, oldest first.
     *
     * Deleted and expired rows are filtered rather than removed, so a delete arriving over
     * the socket cannot race a history fetch that would put the row back.
     */
    fun observeThread(
        surface: ChatSurface,
        conversationId: String,
    ): Flow<List<CncMessageEntity>> =
        realm.query<CncMessageEntity>(
            "surface == $0 AND projectId == $1 AND conversationId == $2 AND deleted == 0 AND expired == 0",
            surface.key,
            currentProjectId,
            conversationId,
        )
            .sort("created", Sort.ASCENDING)
            .asFlow()
            .map { it.list }

    /** Every conversation on this surface. Ordering is [RecentChatOrder]'s job, not this one's. */
    fun observeConversations(surface: ChatSurface): Flow<List<CncConversationEntity>> =
        realm.query<CncConversationEntity>(
            "surface == $0 AND projectId == $1",
            surface.key,
            currentProjectId,
        )
            .asFlow()
            .map { it.list }

    /** One conversation row, for the thread header. */
    /**
     * One conversation as it stands now.
     *
     * The observing variant is what a screen wants; this is for the places that need the
     * membership once, to build a request out of it.
     */
    fun conversationOnce(surface: ChatSurface, conversationId: String): CncConversationEntity? =
        realm.query<CncConversationEntity>(
            "surface == $0 AND projectId == $1 AND conversationId == $2",
            surface.key,
            currentProjectId,
            conversationId,
        ).first().find()

    fun observeConversation(
        surface: ChatSurface,
        conversationId: String,
    ): kotlinx.coroutines.flow.Flow<CncConversationEntity?> =
        realm.query<CncConversationEntity>(
            "id == $0",
            CncConversationEntity.key(surface.key, currentProjectId, conversationId),
        ).asFlow().map { it.list.firstOrNull() }

    /** Look a message up by the id it carries in the feed, which is its `unique_id`. */
    fun messageByUniqueId(surface: ChatSurface, uniqueId: String): CncMessageEntity? =
        realm.query<CncMessageEntity>(
            "id == $0",
            CncMessageEntity.key(surface.key, currentProjectId, uniqueId),
        ).first().find()

    /**
     * Who has read one group message.
     *
     * The names, where [readCounts] gives only the totals — a count answers "has everyone
     * seen this", the list answers "who has not".
     */
    suspend fun readers(surface: ChatSurface, serverId: String): ReadByResult {
        // One message per request, named in the path. The room is not part of it: the
        // message id already identifies the room it is in.
        val url = "${surface.groupReadBy}/$serverId"

        return when (val result = api.get<ReadByResponse>(url, ModuleData.WITH_PROJECT_USER_ID)) {
            is ApiResult.Failure -> ReadByResult()
            is ApiResult.Success -> ReadByResult(
                read = result.data.data?.readBy.orEmpty(),
                // The endpoint answers both halves, and the second is the useful one: the
                // question behind opening this is usually who has *not* seen it.
                unread = result.data.data?.unreadBy.orEmpty(),
            )
        }
    }

    /**
     * How many pictures and how many files a conversation holds.
     *
     * Counted in storage rather than off a loaded feed: the details screen is reachable
     * without the thread having been opened, and it would otherwise say zero of everything
     * on a conversation full of attachments.
     */
    fun sharedCounts(surface: ChatSurface, conversationId: String): SharedCounts {
        val messages = realm.query<CncMessageEntity>(
            "surface == $0 AND projectId == $1 AND conversationId == $2 AND deleted == 0",
            surface.key,
            currentProjectId,
            conversationId,
        ).find()

        return SharedCounts(
            media = messages.count { it.messageType in MEDIA_TYPES },
            files = messages.count { it.messageType in FILE_TYPES },
        )
    }

    /** Look a message up by the server's id — what a reply and an edit address. */
    fun messageByServerId(surface: ChatSurface, serverId: String): CncMessageEntity? =
        realm.query<CncMessageEntity>(
            "surface == $0 AND projectId == $1 AND serverId == $2",
            surface.key,
            currentProjectId,
            serverId,
        ).first().find()

    /** The newest message this device knows about, for the reconnect resync. */
    suspend fun newestTimestamp(surface: ChatSurface): Long =
        realm.query<CncMessageEntity>("surface == $0 AND projectId == $1", surface.key, currentProjectId)
            .sort("created", Sort.DESCENDING)
            .first()
            .find()
            ?.created ?: 0L

    // ── Sending ──────────────────────────────────────────────────────────────

    /**
     * Send text, or a reply to [replyTo].
     *
     * Returns as soon as the optimistic row is written — the caller does not wait for the
     * network, which is what makes the message appear the instant it is typed.
     */
    suspend fun sendText(
        surface: ChatSurface,
        conversationId: String,
        isGroup: Boolean,
        body: String,
        /**
         * Who is named in [body], as `id to name`.
         *
         * The body already carries `@<userId>`; this is what lets a client that has never
         * seen the person render a name instead of an id. v2 sends the same pair list.
         */
        mentions: List<Pair<String, String>> = emptyList(),
        replyTo: CncMessageEntity? = null,
        receiverDeviceId: String? = null,
        departmentId: String? = null,
        budgetDocumentId: String? = null,
    ): String {
        val uniqueId = UUID.randomUUID().toString()

        // Built from the stored row, which holds readable text — the outgoing payload
        // encrypts its own copy, and the local row keeps this one.
        val replySource = replyTo?.let {
            CncReplyDto(
                messageId = it.serverId,
                sender = it.senderId,
                message = it.message,
                messageType = it.messageType,
                created = it.created,
            )
        }

        val dto = newOutgoingMessage(
            surface = surface,
            uniqueId = uniqueId,
            projectId = currentProjectId,
            senderId = currentUserId,
            conversationId = conversationId,
            isGroup = isGroup,
            body = body,
            receiverDeviceId = receiverDeviceId,
            senderDeviceId = session.deviceId,
            departmentId = departmentId,
            budgetDocumentId = budgetDocumentId,
            reply = replySource,
            messageElements = mentions.map { (id, name) ->
                CncMessageElementDto(search = id, replacer = name)
            },
            crypto = crypto,
        )

        realm.write {
            copyToRealm(
                optimisticRow(
                    dto = dto,
                    surface = surface,
                    projectId = currentProjectId,
                    conversationId = conversationId,
                    isGroup = isGroup,
                    plainBody = body,
                    replySource = replySource,
                ),
            )
        }
        touchConversation(surface, conversationId, dto.created ?: 0, body)

        deliver(surface, dto, conversationId, isGroup)
        return uniqueId
    }

    /**
     * Share a pin.
     *
     * The same three steps as text — the location rides on the payload and the address
     * doubles as the body, so a client that draws no map still shows where. Both v2 and v3
     * send `lat`/`long`, not `latitude`/`longitude`; the asymmetry is the server's.
     */
    suspend fun sendLocation(
        surface: ChatSurface,
        conversationId: String,
        isGroup: Boolean,
        latitude: Double,
        longitude: Double,
        address: String,
        receiverDeviceId: String? = null,
        departmentId: String? = null,
        budgetDocumentId: String? = null,
    ): String {
        val uniqueId = UUID.randomUUID().toString()

        val dto = newOutgoingMessage(
            surface = surface,
            uniqueId = uniqueId,
            projectId = currentProjectId,
            senderId = currentUserId,
            conversationId = conversationId,
            isGroup = isGroup,
            body = address,
            messageType = MESSAGE_TYPE_LOCATION,
            receiverDeviceId = receiverDeviceId,
            senderDeviceId = session.deviceId,
            departmentId = departmentId,
            budgetDocumentId = budgetDocumentId,
            location = CncLocationDto(
                latitude = latitude,
                longitude = longitude,
                address = address,
            ),
            crypto = crypto,
        )

        realm.write {
            copyToRealm(
                optimisticRow(
                    dto = dto,
                    surface = surface,
                    projectId = currentProjectId,
                    conversationId = conversationId,
                    isGroup = isGroup,
                    plainBody = address,
                ),
            )
        }
        touchConversation(surface, conversationId, dto.created ?: 0, address)

        deliver(surface, dto, conversationId, isGroup)
        return uniqueId
    }

    /**
     * Share a contact card.
     *
     * Sent as text, because no contact message type exists on the wire. v2 offers the tile
     * and its contact-reading code is commented out end to end, so nothing was ever
     * defined; name and number as a message is understood by every client today.
     */
    suspend fun sendContact(
        surface: ChatSurface,
        conversationId: String,
        isGroup: Boolean,
        name: String,
        phone: String,
        receiverDeviceId: String? = null,
    ): String = sendText(
        surface = surface,
        conversationId = conversationId,
        isGroup = isGroup,
        body = listOf(name, phone).filter { it.isNotBlank() }.joinToString(separator = "\n"),
        receiverDeviceId = receiverDeviceId,
    )

    /**
     * Emit a pending message and fold the acknowledgement back onto its row.
     *
     * Also the retry path: it is safe to call for a row that may already have been sent,
     * because the data source's in-flight window collapses concurrent attempts into one.
     */
    suspend fun deliver(
        surface: ChatSurface,
        dto: CncMessageDto,
        conversationId: String,
        isGroup: Boolean,
    ) {
        val confirmed = socket.sendMessage(surface, dto, isGroup)
        if (confirmed == null) {
            ZillitLog.d(TAG, "send not acknowledged, staying pending: ${dto.uniqueId}")
            return
        }

        realm.write {
            val row = query<CncMessageEntity>(
                "id == $0",
                CncMessageEntity.key(surface.key, currentProjectId, dto.uniqueId.orEmpty()),
            ).first().find() ?: return@write

            row.applyFrom(
                dto = confirmed,
                surface = surface,
                projectId = currentProjectId,
                conversationId = conversationId,
                isGroup = isGroup,
                currentUserId = currentUserId,
                crypto = crypto,
                // The echo knows nothing about this device's file paths or progress.
                preserveLocal = true,
            )
            if (row.status < CncMessageEntity.STATUS_SENT) {
                row.status = CncMessageEntity.STATUS_SENT
            }
        }
        socket.clearInFlight(dto.uniqueId)
    }

    /**
     * Finish an attachment send once its bytes are on storage.
     *
     * Called by the upload worker, not by a screen. The optimistic row already exists and is
     * showing the local file with a progress bar; this replaces it with what the server
     * stored. The `unique_id` is the queue row's own id, which is what makes this an update
     * rather than a second message appearing beside the first.
     */
    suspend fun deliverAttachment(send: CncAttachmentSend): Boolean {
        val surface = ChatSurface.entries.firstOrNull { it.key == send.surfaceKey }
            ?: return false

        val attachment = CncAttachmentDto(
            media = send.media,
            name = send.fileName,
            thumbnail = send.thumbnail.takeIf { it.isNotBlank() },
            contentType = send.mimeType,
            contentSubtype = send.fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() },
            fileSize = send.fileSize,
            duration = send.durationMs.takeIf { it > 0 },
            width = send.width.toLong().takeIf { it > 0 },
            height = send.height.toLong().takeIf { it > 0 },
            // Encrypted by newOutgoingMessage along with the body, so the caption travels
            // like any other message text.
            caption = send.caption.takeIf { it.isNotBlank() },
        )

        val dto = newOutgoingMessage(
            surface = surface,
            uniqueId = send.uniqueId,
            projectId = send.projectId,
            senderId = send.senderId,
            conversationId = send.conversationId,
            isGroup = send.isGroup,
            // The caption is the message body for a file, which is how v2 sends it too.
            body = send.caption,
            messageType = send.messageType,
            receiverDeviceId = send.receiverDeviceId.takeIf { it.isNotBlank() },
            senderDeviceId = session.deviceId,
            messageGroup = send.messageGroup,
            attachments = listOf(attachment),
            crypto = crypto,
        )

        val confirmed = socket.sendMessage(surface, dto, send.isGroup) ?: return false

        realm.write {
            val row = query<CncMessageEntity>(
                "id == $0",
                CncMessageEntity.key(surface.key, send.projectId, send.uniqueId),
            ).first().find() ?: return@write

            row.applyFrom(
                dto = confirmed,
                surface = surface,
                projectId = send.projectId,
                conversationId = send.conversationId,
                isGroup = send.isGroup,
                currentUserId = send.senderId,
                crypto = crypto,
                // Keep the local file path: the bubble is rendering from it, and swapping to
                // a remote key that is not cached yet would blank the picture the user is
                // already looking at.
                preserveLocal = true,
            )
            row.uploadProgress = 1f
            if (row.status < CncMessageEntity.STATUS_SENT) {
                row.status = CncMessageEntity.STATUS_SENT
            }
        }
        socket.clearInFlight(send.uniqueId)
        return true
    }

    /**
     * Put an attachment in the thread the instant it is picked.
     *
     * Written before the upload starts, so a photo appears with a progress bar rather than
     * after it finishes. The queue row and this row share an id, which is how the two halves
     * find each other later.
     */
    suspend fun addPendingAttachment(
        surface: ChatSurface,
        uniqueId: String,
        conversationId: String,
        isGroup: Boolean,
        localPath: String,
        fileName: String,
        caption: String,
        messageType: String,
        sizeBytes: Long,
        durationMs: Long,
        messageGroup: Long,
    ) {
        val dto = newOutgoingMessage(
            surface = surface,
            uniqueId = uniqueId,
            projectId = currentProjectId,
            senderId = currentUserId,
            conversationId = conversationId,
            isGroup = isGroup,
            body = caption,
            messageType = messageType,
            senderDeviceId = session.deviceId,
            messageGroup = messageGroup,
            attachments = listOf(
                CncAttachmentDto(
                    name = fileName,
                    fileSize = sizeBytes.toString(),
                    duration = durationMs.takeIf { it > 0 },
                ),
            ),
            crypto = crypto,
        )

        realm.write {
            copyToRealm(
                optimisticRow(
                    dto = dto,
                    surface = surface,
                    projectId = currentProjectId,
                    conversationId = conversationId,
                    isGroup = isGroup,
                    plainBody = caption,
                    plainCaptions = listOf(caption),
                    localPaths = listOf(localPath),
                ),
            )
        }
        touchConversation(surface, conversationId, dto.created ?: 0, caption)
    }

    /** Resend everything still pending on this surface. */
    suspend fun flushPending(surface: ChatSurface) {
        // Snapshot before sending: `deliver` writes to Realm, and iterating a live result
        // while it changes underneath is how a flush skips rows.
        val pending = realm.query<CncMessageEntity>(
            "surface == $0 AND projectId == $1 AND status == $2",
            surface.key,
            currentProjectId,
            CncMessageEntity.STATUS_PENDING,
        ).sort("created", Sort.ASCENDING).find().map { row ->
            PendingSend(
                dto = row.toOutgoing(surface),
                conversationId = row.conversationId,
                isGroup = row.isGroup,
            )
        }

        // Oldest first and one at a time: these are the messages someone typed while
        // offline, and they have to arrive in the order they were written.
        pending.forEach { deliver(surface, it.dto, it.conversationId, it.isGroup) }
    }

    /**
     * Send one stalled message again.
     *
     * Named rather than flushing everything: a retry is a deliberate act on the bubble the
     * user tapped, and resending the other nine pending rows with it is not what the tap
     * asked for.
     */
    suspend fun retryPending(surface: ChatSurface, uniqueId: String) {
        val row = messageByUniqueId(surface, uniqueId) ?: return
        if (!row.isPending) return
        deliver(surface, row.toOutgoing(surface), row.conversationId, row.isGroup)
    }

    /**
     * Drop a message that will never be sent.
     *
     * Hard-deleted rather than marked: the server has never seen it, so there is nothing to
     * tell anyone about, and leaving it as a tombstone would put "Message deleted" in the
     * thread for a message nobody else ever received.
     */
    suspend fun discardPending(surface: ChatSurface, uniqueId: String) {
        realm.write {
            query<CncMessageEntity>(
                "surface == $0 AND projectId == $1 AND uniqueId == $2 AND status == $3",
                surface.key,
                currentProjectId,
                uniqueId,
                CncMessageEntity.STATUS_PENDING,
            ).first().find()?.let(::delete)
        }
    }

    suspend fun edit(surface: ChatSurface, message: CncMessageEntity, body: String): Boolean {
        if (message.serverId.isBlank()) return false
        val ok = socket.editMessage(
            surface = surface,
            serverId = message.serverId,
            // Encrypted like a send. v2 encrypts here too — it is the one path where its
            // encryption survived — so an edit that went out in the clear would be the
            // odd one out on every other client.
            message = crypto.encryptOrBlank(body),
            // The two travel together, here as on a send: an edit that cleared the
            // translated copy would leave other clients showing the text from before it.
            translation = crypto.encryptOrBlank(body),
            isGroup = message.isGroup,
        )
        if (ok) {
            realm.write {
                findLatest(message)?.apply {
                    this.message = body
                    edited = System.currentTimeMillis()
                }
            }
        }
        return ok
    }

    suspend fun delete(surface: ChatSurface, messages: List<CncMessageEntity>): Boolean {
        val serverIds = messages.mapNotNull { it.serverId.takeIf(String::isNotBlank) }
        if (serverIds.isEmpty()) return false

        val ok = socket.deleteMessages(surface, serverIds, messages.first().isGroup)
        if (ok) applyDeletion(surface, serverIds)
        return ok
    }

    suspend fun react(surface: ChatSurface, message: CncMessageEntity, reaction: String) {
        if (message.serverId.isBlank()) return
        socket.react(surface, message.serverId, reaction)?.let { applyIncoming(surface, it) }
    }

    /**
     * Tell the server this thread has been read up to its newest message.
     *
     * Only the newest needs naming — the event is a watermark, not a per-message receipt.
     */
    fun markThreadRead(
        surface: ChatSurface,
        conversationId: String,
        isGroup: Boolean,
    ) {
        val newest = realm.query<CncMessageEntity>(
            "surface == $0 AND projectId == $1 AND conversationId == $2 AND serverId != ''",
            surface.key,
            currentProjectId,
            conversationId,
        ).sort("created", Sort.DESCENDING).first().find() ?: return

        socket.markRead(
            surface = surface,
            serverId = newest.serverId,
            status = CncMessageEntity.STATUS_READ,
            conversationId = conversationId,
            isGroup = isGroup,
            departmentId = newest.departmentId.takeIf(String::isNotBlank),
        )
    }

    // ── Receipts ─────────────────────────────────────────────────────────────

    /**
     * Which conversation is on screen, or null.
     *
     * Decides whether an arriving message is acknowledged as **read** or merely
     * **delivered**. Held here rather than in the view model because the acknowledgement is
     * sent by the realtime layer, which runs whether or not a screen exists.
     */
    @Volatile
    var openConversationId: String? = null
        private set

    fun onThreadOpened(conversationId: String) { openConversationId = conversationId }

    fun onThreadClosed(conversationId: String) {
        if (openConversationId == conversationId) openConversationId = null
    }

    /**
     * Tell the sender their message arrived, and whether it was read.
     *
     * **This is what makes two ticks possible at all.** Without it a message never leaves
     * one tick until the recipient happens to open the thread — v2 sends this on every
     * received message and v3 was not sending it anywhere.
     *
     * Read when the thread is open, delivered otherwise. A failed emit is queued rather
     * than dropped: nothing waits for a receipt, which is exactly why a lost one is never
     * noticed and never retried.
     */
    suspend fun acknowledge(surface: ChatSurface, dto: CncMessageDto) {
        val serverId = dto.id?.takeIf { it.isNotBlank() } ?: return

        // Our own message echoed back from another of this user's devices needs no receipt.
        if (dto.sender == currentUserId) return

        val conversationId = dto.conversationIdFor(currentUserId)
        val status = if (conversationId == openConversationId) {
            CncMessageEntity.STATUS_READ
        } else {
            CncMessageEntity.STATUS_DELIVERED
        }

        val sent = socket.markRead(
            surface = surface,
            serverId = serverId,
            status = status,
            conversationId = conversationId,
            isGroup = dto.isGroupMessage,
            departmentId = dto.departmentId,
        )

        if (!sent) {
            queueReceipt(surface, serverId, status, conversationId, dto)
        }
    }

    private suspend fun queueReceipt(
        surface: ChatSurface,
        serverId: String,
        status: Int,
        conversationId: String,
        dto: CncMessageDto,
    ) {
        realm.write {
            val existing = query<PendingReceiptEntity>("messageServerId == $0", serverId)
                .first().find()

            // A read supersedes a queued delivered for the same message; never the reverse.
            if (existing != null && existing.status >= status) return@write

            val row = existing ?: copyToRealm(
                PendingReceiptEntity().apply { messageServerId = serverId },
            )
            row.projectId = currentProjectId
            row.surface = surface.key
            row.status = status
            row.isGroup = dto.isGroupMessage
            row.conversationId = conversationId
            row.chatTool = dto.chatTool.orEmpty()
            row.departmentId = dto.departmentId.orEmpty()
            row.budgetDocumentId = dto.budgetDocumentId.orEmpty()
            row.createdAt = System.currentTimeMillis()
        }
    }

    /** Re-send every queued receipt. Called on reconnect. */
    suspend fun flushReceipts() {
        val pending = realm.query<PendingReceiptEntity>("projectId == $0", currentProjectId)
            .find()
            .map { row ->
                QueuedReceipt(
                    serverId = row.messageServerId,
                    surfaceKey = row.surface,
                    status = row.status,
                    conversationId = row.conversationId,
                    isGroup = row.isGroup,
                    departmentId = row.departmentId,
                )
            }

        pending.forEach { receipt ->
            val surface = ChatSurface.entries.firstOrNull { it.key == receipt.surfaceKey }
                ?: return@forEach

            val sent = socket.markRead(
                surface = surface,
                serverId = receipt.serverId,
                status = receipt.status,
                conversationId = receipt.conversationId,
                isGroup = receipt.isGroup,
                departmentId = receipt.departmentId.takeIf { it.isNotBlank() },
            )

            // Only drop it once the server has it. A still-dead socket leaves the row for
            // the next attempt, which is the whole point of persisting it.
            if (sent) {
                realm.write {
                    query<PendingReceiptEntity>("messageServerId == $0", receipt.serverId)
                        .first().find()?.let(::delete)
                }
            }
        }
    }

    /** Snapshotted out of Realm before sending, so the flush is not iterating live rows. */
    private data class QueuedReceipt(
        val serverId: String,
        val surfaceKey: String,
        val status: Int,
        val conversationId: String,
        val isGroup: Boolean,
        val departmentId: String,
    )

    // ── Applying what arrives ────────────────────────────────────────────────

    /** Write one server message into Realm, whether it came live or from a fetch. */
    suspend fun applyIncoming(
        surface: ChatSurface,
        dto: CncMessageDto,
        fromApi: Boolean = false,
    ) {
        val uniqueId = dto.uniqueId.orEmpty()
        if (uniqueId.isBlank()) {
            ZillitLog.socketWarn("cnc message without unique_id dropped: ${dto.id}")
            return
        }

        val conversationId = dto.conversationIdFor(currentUserId)
        if (conversationId.isBlank()) return

        val isGroup = dto.isGroupMessage
        val key = CncMessageEntity.key(surface.key, currentProjectId, uniqueId)

        realm.write {
            val existing = query<CncMessageEntity>("id == $0", key).first().find()
            val row = existing ?: copyToRealm(CncMessageEntity().apply { id = key })

            row.applyFrom(
                dto = dto,
                surface = surface,
                projectId = currentProjectId,
                conversationId = conversationId,
                isGroup = isGroup,
                currentUserId = currentUserId,
                crypto = crypto,
                preserveLocal = existing != null,
            )
            row.isFromApiCall = row.isFromApiCall || fromApi
        }

        // Decrypted, like the row itself. The wire body is ciphertext — v2 decrypts it in
        // `handleLastMessage` before drawing the row's second line — and handing it over
        // raw put a hex string under every name on the Chat tab. A file with no caption
        // previews as its name, as the mapper does for the thread.
        val preview = crypto.decryptOrBlank(dto.message).ifBlank {
            dto.attachment?.name ?: dto.attachments?.firstOrNull()?.name
        }.orEmpty()
        touchConversation(surface, conversationId, dto.created ?: 0, preview, dto.messageType)
    }

    /**
     * Apply a read watermark: everything at or before it moves to [receipt] status.
     *
     * A watermark, not a single message — the server names the newest read message and
     * everything older is implied. Applying it only to the named row is why a v2 thread
     * shows one blue tick in the middle of a run of grey ones.
     */
    suspend fun applyReadReceipt(surface: ChatSurface, receipt: CncReadReceipt) {
        val status = receipt.status ?: return

        realm.write {
            // The named form. When the server lists the messages it moved, those are the
            // messages that moved — there is no watermark to infer.
            val named = receipt.chatMessageIds.orEmpty()
            if (named.isNotEmpty()) {
                query<CncMessageEntity>(
                    "surface == $0 AND projectId == $1 AND serverId IN $2 AND status < $3",
                    surface.key,
                    currentProjectId,
                    named,
                    status,
                ).find().forEach { it.status = status }
                return@write
            }

            // The watermark form: everything this user sent to that conversation, up to and
            // including the named message, is now at [status].
            val marker = receipt.id?.let { serverId ->
                query<CncMessageEntity>(
                    "surface == $0 AND projectId == $1 AND serverId == $2",
                    surface.key,
                    currentProjectId,
                    serverId,
                ).first().find()
            }

            // The conversation the receipt is about. Preferring the marker keeps a group
            // receipt on its room; falling back to the named parties is what makes a
            // receipt usable when the message it points at was never stored on this device,
            // which is the common case after a reinstall. v2 works from these fields alone.
            val conversationId = marker?.conversationId
                ?: listOfNotNull(receipt.roomId, receipt.receiver, receipt.sender)
                    .firstOrNull { it.isNotBlank() && it != currentUserId }
                ?: return@write

            // Without a marker there is no cut-off, so the receipt applies to everything
            // outstanding — which is what "read up to now" means.
            val until = marker?.created ?: receipt.updated ?: System.currentTimeMillis()

            query<CncMessageEntity>(
                "surface == $0 AND projectId == $1 AND conversationId == $2 AND senderId == $3 AND created <= $4 AND status < $5",
                surface.key,
                currentProjectId,
                conversationId,
                currentUserId,
                until,
                status,
            ).find().forEach { it.status = status }
        }
    }

    /** Soft-delete rows the server says are gone. */
    suspend fun applyDeletion(surface: ChatSurface, serverIds: List<String>) {
        if (serverIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val touched = mutableSetOf<String>()

        realm.write {
            serverIds.forEach { id ->
                val row = query<CncMessageEntity>(
                    "surface == $0 AND projectId == $1 AND serverId == $2",
                    surface.key,
                    currentProjectId,
                    id,
                ).first().find() ?: return@forEach
                row.deleted = now
                touched += row.conversationId
            }
        }

        // The list's preview line still shows the message that was just removed. v2 has the
        // same problem and patches it with `updateDeletedMessage`; here the preview is
        // recomputed from whatever is now newest, which cannot drift the way a patch can.
        touched.forEach { refreshPreview(surface, it) }
    }

    /** Same treatment as a delete: an expired message is simply no longer in the thread. */
    suspend fun applyExpiry(surface: ChatSurface, serverIds: List<String>) {
        if (serverIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val touched = mutableSetOf<String>()

        realm.write {
            serverIds.forEach { id ->
                val row = query<CncMessageEntity>(
                    "surface == $0 AND projectId == $1 AND serverId == $2",
                    surface.key,
                    currentProjectId,
                    id,
                ).first().find() ?: return@forEach
                row.expired = now
                touched += row.conversationId
            }
        }

        touched.forEach { refreshPreview(surface, it) }
    }

    /**
     * Recompute a conversation's preview line from what is still visible.
     *
     * Called after anything disappears. Recomputed rather than patched: the row only ever
     * holds a copy of the newest message, and deriving it again is the one way it cannot
     * end up describing a message nobody can see.
     *
     * An emptied conversation keeps its order key. It has still been used, and dropping it
     * to zero would remove it from the Chat tab entirely.
     */
    private suspend fun refreshPreview(surface: ChatSurface, conversationId: String) {
        realm.write {
            val key = CncConversationEntity.key(surface.key, currentProjectId, conversationId)
            val row = query<CncConversationEntity>("id == $0", key).first().find() ?: return@write

            val newest = query<CncMessageEntity>(
                "surface == $0 AND projectId == $1 AND conversationId == $2 AND deleted == 0 AND expired == 0",
                surface.key,
                currentProjectId,
                conversationId,
            ).sort("created", Sort.DESCENDING).first().find()

            row.lastMessage = newest?.message.orEmpty()
            row.lastMessageType = newest?.messageType.orEmpty()
        }
    }

    // ── History ──────────────────────────────────────────────────────────────

    /**
     * Fetch a page of history over REST.
     *
     * The socket carries what happens while the app is open; everything older comes from
     * here. Rows are marked `isFromApiCall` so a socket event cannot be mistaken for a
     * confirmed page.
     */
    suspend fun loadHistory(
        surface: ChatSurface,
        conversationId: String,
        isGroup: Boolean,
        before: Long? = null,
        /** Budget threads are department-scoped; C&C passes neither. */
        departmentId: String? = null,
        budgetDocumentId: String? = null,
    ): ApiResult<Int> {
        // Path segments, not query parameters. The conversation, the point to page from
        // and the direction are all part of the route:
        //
        //     …/private-chat/messages/{userId}/{timestamp}/previous?tool=…&department_id=…
        //
        // Sending them as a query string matched no route at all and the server answered
        // `route_not_found`, which is why a thread opened empty however much history it had.
        val base = if (isGroup) surface.groupHistory else surface.privateHistory
        val url = buildString {
            append(base)
            append('/').append(conversationId)
            // Where to page from. Now, for the first page: the server walks backwards.
            append('/').append(before ?: System.currentTimeMillis())
            append('/').append(PREVIOUS)
            // The tool depends on the scope, not only the surface: a department budget and
            // the project-level one are different tools to the server.
            append("?tool=").append(surface.chatTool(departmentId))
            append("&department_id=").append(departmentId.orEmpty())
            budgetDocumentId?.takeIf { it.isNotBlank() }
                ?.let { append("&budget_document_id=").append(it) }
        }

        return when (val result = api.get<CncHistoryResponse>(url, ModuleData.WITH_PROJECT_USER_ID)) {
            is ApiResult.Failure -> ApiResult.Failure(result.error)
            is ApiResult.Success -> {
                val messages = result.data.messages
                messages.forEach { applyIncoming(surface, it, fromApi = true) }
                ZillitLog.d(TAG, "history: ${messages.size} for $conversationId")
                ApiResult.Success(messages.size)
            }
        }
    }

    /**
     * Keep fetching until the server runs out.
     *
     * A full page means there is more behind it. v2 loops on the same rule, and without it
     * a thread shows one page and stops — which reads as "the chat did not load" rather
     * than "there is more further back", because nothing on screen says a page boundary
     * was reached.
     *
     * Bounded, because an endpoint that keeps returning full pages would otherwise fetch
     * forever. The cap is generous enough that reaching it means something is wrong.
     */
    suspend fun loadHistoryPages(
        surface: ChatSurface,
        conversationId: String,
        isGroup: Boolean,
        departmentId: String? = null,
        budgetDocumentId: String? = null,
    ): Int {
        var fetched = 0
        var before: Long? = null

        repeat(MAX_HISTORY_PAGES) {
            val result = loadHistory(
                surface = surface,
                conversationId = conversationId,
                isGroup = isGroup,
                before = before,
                departmentId = departmentId,
                budgetDocumentId = budgetDocumentId,
            )

            val count = (result as? ApiResult.Success)?.data ?: return fetched
            fetched += count

            // A short page is the end of the conversation.
            if (count < PAGE_SIZE) return fetched

            // Page back from the oldest message now stored, so the next request asks for
            // what sits behind it rather than repeating this page.
            before = oldestTimestamp(surface, conversationId) ?: return fetched
        }
        return fetched
    }

    /** The oldest message held for a conversation, for paging backwards. */
    private fun oldestTimestamp(surface: ChatSurface, conversationId: String): Long? =
        realm.query<CncMessageEntity>(
            "surface == $0 AND projectId == $1 AND conversationId == $2",
            surface.key,
            currentProjectId,
            conversationId,
        ).sort("created", Sort.ASCENDING).first().find()?.created?.takeIf { it > 0 }

    /**
     * How many people have read each of this user's messages in a group.
     *
     * A separate fetch because the read-until events name a reader and a watermark, not a
     * total — counting them locally would mean holding every receipt for every message and
     * still being wrong for anyone who read before this device was listening.
     *
     * Groups only. A one-to-one has the tick for this, and "Read by 1" beside it would say
     * the same thing twice.
     */
    fun readCounts(surface: ChatSurface, roomId: String): Map<String, Int> =
        realm.query<CncMessageEntity>(
            "surface == $0 AND projectId == $1 AND conversationId == $2 AND senderId == $3 AND readByCount > 0",
            surface.key,
            currentProjectId,
            roomId,
            currentUserId,
        ).find().associate { it.serverId to it.readByCount }

    /**
     * Star or unstar a conversation.
     *
     * Written locally first so the star reacts to the tap, then posted. A failure leaves the
     * local value in place rather than snapping back: the next refresh carries the server's
     * answer, and a star that flips back under the user's finger reads as a bug even when it
     * is correct.
     */
    suspend fun toggleFavourite(surface: ChatSurface, conversationId: String): Boolean {
        val key = CncConversationEntity.key(surface.key, currentProjectId, conversationId)

        var nowFavourite = false
        realm.write {
            val row = query<CncConversationEntity>("id == $0", key).first().find() ?: return@write
            row.favourite = !row.favourite
            nowFavourite = row.favourite
        }

        val url = if (nowFavourite) ChatSurface.FAVOURITE_ADD else ChatSurface.FAVOURITE_REMOVE
        val result = api.post<FavouriteRequest, FavouriteResponse>(
            url = url,
            body = FavouriteRequest(favouriteUserId = conversationId),
            module = ModuleData.WITH_PROJECT_USER_ID,
        )
        return result is ApiResult.Success && result.data.ok
    }

    /**
     * Block or unblock someone.
     *
     * Written locally first so the banner appears at once, then posted. The socket's block
     * event carries the server's view and corrects this if the two disagree.
     */
    suspend fun toggleBlock(
        surface: ChatSurface,
        conversationId: String,
        block: Boolean,
    ): Boolean {
        val key = CncConversationEntity.key(surface.key, currentProjectId, conversationId)
        realm.write {
            query<CncConversationEntity>("id == $0", key).first().find()?.blockedByMe = block
        }

        // PUT, and these exact field names. `from_user_id` is deliberately absent: the
        // server takes the actor from the signed header, and sending a guess for it is how
        // a block gets recorded against the wrong person.
        val result = api.put<BlockRequest, FavouriteResponse>(
            url = ChatSurface.COMMUNICATION_BLOCK,
            body = BlockRequest(toUserId = conversationId, userBlocked = block),
            module = ModuleData.WITH_PROJECT_USER_ID,
        )
        return result is ApiResult.Success && result.data.ok
    }

    /**
     * Load who this user has blocked, and who has blocked them.
     *
     * Without this the block banner is only ever right for a block made on this device in
     * this session: the state lives on the server and nothing else fetches it.
     */
    suspend fun refreshBlocks(surface: ChatSurface): Boolean {
        val me = currentUserId.takeIf { it.isNotBlank() } ?: return false

        val result = api.get<BlockListResponse>(
            url = "${ChatSurface.COMMUNICATION_LIST}/$me",
            module = ModuleData.WITH_PROJECT_USER_ID,
        )
        if (result !is ApiResult.Success) return false

        val entries = result.data.data?.userCommunication.orEmpty()

        realm.write {
            // Clear first: a block lifted on another device shows up as an absence here,
            // never as an entry saying "unblocked".
            query<CncConversationEntity>(
                "surface == $0 AND projectId == $1",
                surface.key,
                currentProjectId,
            ).find().forEach {
                it.blockedByMe = false
                it.blockedMe = false
            }

            entries.forEach { entry ->
                // Each row is directional. The one I made names me as `from`; the one made
                // against me names me as `to`, and the two mean very different things.
                val other = if (entry.fromUserId == me) entry.toUserId else entry.fromUserId
                val key = CncConversationEntity.key(surface.key, currentProjectId, other.orEmpty())
                val row = query<CncConversationEntity>("id == $0", key).first().find()
                    ?: return@forEach

                if (entry.userBlocked != true) return@forEach
                if (entry.fromUserId == me) row.blockedByMe = true else row.blockedMe = true
            }
        }
        return true
    }

    // ── Recent list ──────────────────────────────────────────────────────────

    /**
     * Derive every conversation's order key from the newest message stored for it.
     *
     * **This is v2's real source of truth for the Chat tab**, and the one I was missing:
     * `updateLastTimeStampInUserListFromDatabaseAfterApiCall` walks the list and asks the
     * message store for each conversation's newest timestamp. The server's
     * `sorting_activity` seeds it and a live message advances it, but neither is guaranteed
     * to have arrived — the stored messages always have.
     *
     * Without it the tab can be empty while the messages behind it are on disk, because the
     * membership rule is "has activity", and activity was only ever being written by paths
     * that may not have run.
     */
    suspend fun syncOrderFromMessages(surface: ChatSurface) {
        val newest = realm.query<CncMessageEntity>(
            "surface == $0 AND projectId == $1 AND deleted == 0 AND expired == 0",
            surface.key,
            currentProjectId,
        ).find()
            .groupBy { it.conversationId }
            .mapValues { (_, messages) -> messages.maxOf { it.created } }

        if (newest.isEmpty()) return

        realm.write {
            newest.forEach { (conversationId, at) ->
                val key = CncConversationEntity.key(surface.key, currentProjectId, conversationId)
                val row = query<CncConversationEntity>("id == $0", key).first().find()
                    ?: copyToRealm(
                        CncConversationEntity().apply {
                            id = key
                            this.surface = surface.key
                            projectId = currentProjectId
                            this.conversationId = conversationId
                        },
                    )

                // Only ever forwards. The server may know of activity newer than anything
                // stored here, and a stale local message must not drag the row backwards.
                if (at > row.sortingActivity) row.sortingActivity = at
            }
        }

        ZillitLog.d(TAG, "order synced from ${newest.size} conversation(s)")
    }

    /**
     * Mark which conversations this user actually has.
     *
     * The event answers membership, not order: it returns ids. So this only raises the
     * order key for a conversation that has none yet — a person you have talked to but
     * whose `sorting_activity` has not arrived from the directory. Anything the directory
     * or a local message already dated is left alone, because both are more precise.
     */
    suspend fun refreshRecent(surface: ChatSurface) {
        val detail = socket.recentConversations(surface)
        if (detail == null) {
            // Null means the socket was down or the acknowledgement never came — not that
            // there are no conversations. Logged as its own case, because "empty tab" has
            // two very different causes and they are indistinguishable on screen.
            ZillitLog.w(TAG, "recent list unanswered for ${surface.key}")
            return
        }

        val ids = detail.allUserIds
        ZillitLog.d(TAG, "recent: ${ids.size} people, ${detail.chatRooms.size} rooms")
        if (ids.isEmpty() && detail.chatRooms.isEmpty()) return

        realm.write {
            ids.forEach { conversationId ->
                val key = CncConversationEntity.key(surface.key, currentProjectId, conversationId)
                val row = query<CncConversationEntity>("id == $0", key).first().find()
                    ?: copyToRealm(
                        CncConversationEntity().apply {
                            id = key
                            this.surface = surface.key
                            projectId = currentProjectId
                            this.conversationId = conversationId
                        },
                    )

                // A conversation exists, so it belongs on the Chat tab. Without a real
                // timestamp it sorts to the bottom rather than being filtered out, which
                // is the whole difference between "listed last" and "missing".
                if (row.sortingActivity <= 0L) row.sortingActivity = EXISTS_BUT_UNDATED
            }

            detail.chatRooms.forEach { room ->
                val roomId = room.id ?: return@forEach
                val key = CncConversationEntity.key(surface.key, currentProjectId, roomId)
                val row = query<CncConversationEntity>("id == $0", key).first().find()
                    ?: copyToRealm(
                        CncConversationEntity().apply {
                            id = key
                            this.surface = surface.key
                            projectId = currentProjectId
                            conversationId = roomId
                            isGroup = true
                        },
                    )

                if (row.name.isBlank()) row.name = room.roomName.orEmpty()
                val incoming = room.sortingActivity ?: room.lastMessagePostedOn ?: 0
                if (incoming > row.sortingActivity) row.sortingActivity = incoming
                if (row.sortingActivity <= 0L) row.sortingActivity = EXISTS_BUT_UNDATED
            }
        }
    }

    /**
     * Move a conversation to the top and refresh its preview line.
     *
     * Called on every send and every receive, because the recent list's order is the
     * `created` of the newest message and nothing else.
     */
    private suspend fun touchConversation(
        surface: ChatSurface,
        conversationId: String,
        at: Long,
        preview: String,
        messageType: String? = null,
    ) {
        if (at <= 0) return
        realm.write {
            val key = CncConversationEntity.key(surface.key, currentProjectId, conversationId)
            val entity = query<CncConversationEntity>("id == $0", key).first().find() ?: return@write
            // `>=`, not `>`: the newest message re-applied — a history refetch, a reconnect
            // resync — must be allowed to refresh the preview of the row it already tops,
            // or a preview stored wrong once stays wrong for as long as the row exists.
            if (at >= entity.sortingActivity) {
                entity.sortingActivity = at
                entity.lastMessage = preview
                messageType?.let { entity.lastMessageType = it }
            }
        }
    }

    /** Rebuild the outgoing payload for a row that still has to be sent. */
    private fun CncMessageEntity.toOutgoing(surface: ChatSurface): CncMessageDto =
        newOutgoingMessage(
            surface = surface,
            uniqueId = uniqueId,
            projectId = projectId,
            senderId = senderId,
            conversationId = receiverId,
            isGroup = isGroup,
            body = message,
            messageType = messageType,
            receiverDeviceId = receiverDeviceId.takeIf(String::isNotBlank),
            senderDeviceId = senderDeviceId.takeIf(String::isNotBlank),
            departmentId = departmentId.takeIf(String::isNotBlank),
            budgetDocumentId = budgetDocumentId.takeIf(String::isNotBlank),
            messageGroup = messageGroup,
            createdAt = created,
            crypto = crypto,
        )

    /** One queued message, snapshotted out of Realm before the flush starts. */
    private data class PendingSend(
        val dto: CncMessageDto,
        val conversationId: String,
        val isGroup: Boolean,
    )

    private companion object {
        const val TAG = "CncRepository"

        /**
         * Order key for a conversation we know exists but have no timestamp for.
         *
         * 1, not 0: the Chat tab's membership rule is "has activity or is unread", so 0
         * means "never talked to" and would hide a real conversation entirely. This sorts
         * it to the bottom until a real date arrives.
         */
        const val EXISTS_BUT_UNDATED = 1L

        /** v2's `API_PAGINATION_LIMIT`. A page this size means there is more behind it. */
        const val PAGE_SIZE = 50

        /** What the Media shortcut counts: anything the in-app viewer can show. */
        val MEDIA_TYPES = setOf("image", "video")

        /** What the Files shortcut counts. Audio sits here, as it does in v2's Docs tab. */
        val FILE_TYPES = setOf("document", "audio")

        /**
         * The direction segment on a history request.
         *
         * A thread is always read backwards from a moment: `next` exists for catching up
         * from a stored watermark, which the socket's own catch-up stream already does.
         */
        const val PREVIOUS = "previous"

        /**
         * Upper bound on the paging loop.
         *
         * Generous: 20 pages is 1,000 messages, far more than a thread opens with. It
         * exists so a server that keeps answering with full pages cannot spin forever.
         */
        const val MAX_HISTORY_PAGES = 20

        /** v2's `Constants.LOCATION`. The server matches on this exact string. */
        const val MESSAGE_TYPE_LOCATION = "location"
    }
}

@Serializable
data class FavouriteRequest(
    @SerialName("fav_user_id") val favouriteUserId: String,
)

/**
 * The plain acknowledgement these endpoints return.
 *
 * `status == 1` is success. There is no `success` field; reading one meant every write looked
 * like it had failed, or worse, like it had succeeded because the absent field defaulted.
 */
@Serializable
data class FavouriteResponse(
    val status: Int? = null,
    val message: String? = null,
) {
    val ok: Boolean get() = status == STATUS_OK

    companion object {
        const val STATUS_OK = 1
    }
}

@Serializable
data class BlockRequest(
    @SerialName("to_user_id") val toUserId: String,
    @SerialName("user_blocked") val userBlocked: Boolean,
)

@Serializable
data class BlockListResponse(
    val status: Int? = null,
    val message: String? = null,
    val data: BlockListData? = null,
)

@Serializable
data class BlockListData(
    /** The server's own capitalisation. */
    @SerialName("UserCommunication") val userCommunication: List<BlockEntryDto> = emptyList(),
)

@Serializable
data class BlockEntryDto(
    @SerialName("to_user_id") val toUserId: String? = null,
    @SerialName("from_user_id") val fromUserId: String? = null,
    @SerialName("user_blocked") val userBlocked: Boolean? = null,
)

/**
 * One queued attachment, as the upload worker hands it over.
 *
 * A plain value rather than the queue's own entity: the worker lives in `core/storage` and
 * must not need to know about a feature's Realm rows, and this repository must not need to
 * know about the queue's.
 */
data class CncAttachmentSend(
    val surfaceKey: String,
    val uniqueId: String,
    val projectId: String,
    val senderId: String,
    val conversationId: String,
    val isGroup: Boolean,
    val receiverDeviceId: String,
    val caption: String,
    val messageType: String,
    val messageGroup: Long,
    val media: String,
    val thumbnail: String,
    val fileName: String,
    val fileSize: String,
    val mimeType: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val replyToServerId: String,
)

/** Read-by, in the same envelope as everything else here. */
@Serializable
data class ReadByResponse(
    val status: Int? = null,
    val message: String? = null,
    val data: ReadByData? = null,
)

/** Both halves of the answer: who has read it, and who has it but has not. */
@Serializable
data class ReadByData(
    @SerialName("message_read_by") val readBy: List<ReadByDto> = emptyList(),
    @SerialName("message_unread_by") val unreadBy: List<ReadByDto> = emptyList(),
)

/** What the details screen shows beside Media and Files. */
data class SharedCounts(val media: Int = 0, val files: Int = 0)

/** Who has read a message and who has not, as one answer. */
data class ReadByResult(
    val read: List<ReadByDto> = emptyList(),
    val unread: List<ReadByDto> = emptyList(),
)

/** Note the camel case: it is `userId` here, unlike almost everything else on the wire. */
@Serializable
data class ReadByDto(
    @SerialName("userId") val userId: String? = null,
    @SerialName("read_time") val readTime: Long? = null,
    val delivered: Long? = null,
)

/**
 * What the history endpoints return.
 *
 * `data.chat_records`, not `data`. See the note on `CncRoomsResponse`: the array is always
 * named inside the data object, and decoding it as a bare array throws rather than returning
 * nothing — so the thread simply never fills.
 */
@Serializable
data class CncHistoryResponse(
    val status: Int? = null,
    val message: String? = null,
    val data: CncHistoryData? = null,
) {
    val messages: List<CncMessageDto> get() = data?.chatRecords.orEmpty()
    val total: Long get() = data?.totalRecords ?: 0
}

@Serializable
data class CncHistoryData(
    @SerialName("total_records") val totalRecords: Long? = null,
    @SerialName("chat_records") val chatRecords: List<CncMessageDto> = emptyList(),
)
