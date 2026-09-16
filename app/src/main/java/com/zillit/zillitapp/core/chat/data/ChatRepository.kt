package com.zillit.zillitapp.core.chat.data

import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.ChatMessageEntity
import com.zillit.zillitapp.core.database.entity.ChatReplyEntity
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.network.ZillitCrypto
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.storage.UploadQueue
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.query
import io.realm.kotlin.ext.toRealmList
import io.realm.kotlin.query.Sort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Which direction a page request walks from the cursor. */
enum class ChatPage { PREVIOUS, NEXT }

/**
 * One repository for **every** chat surface.
 *
 * v2 had eight near-identical chat implementations because each module had its own base
 * URL; here the URL is data ([ChatModule]) and this class is written once. Adding Catering
 * or Casting later is an enum entry, not another repository.
 *
 * ### Realm-first
 * Nothing here returns network data to the UI. Fetches and socket events **write to
 * Realm**, and the UI observes Realm. That is what makes a thread render instantly on
 * reopen, survive a failed refresh, and update the moment an event lands — and it is why
 * an optimistic row and its server confirmation are the same row, keyed by `unique_id`.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val api: ZillitApi,
    private val realmProvider: RealmProvider,
    private val crypto: ZillitCrypto,
    private val uploadQueue: UploadQueue,
) {

    private val realm get() = realmProvider.realm

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // ---------------------------------------------------------------- reads

    /**
     * The thread, oldest first.
     *
     * Deleted rows are excluded rather than removed, because a delete arriving over the
     * socket must not race a fetch that would restore the row.
     */
    fun observeMessages(
        module: ChatModule,
        projectId: String,
        scopeId: String,
    ): Flow<List<ChatMessageEntity>> =
        realm.query<ChatMessageEntity>(
            "module == $0 AND projectId == $1 AND scopeId == $2 AND deleted == 0",
            module.key, projectId, scopeId,
        )
            .sort("created", Sort.ASCENDING)
            .asFlow()
            .map { it.list.toList() }

    /**
     * The `created` stamp to draw the unread divider above.
     *
     * Derived from the badge count rather than a stored "last read" value: the badge is
     * the number the server already agrees on, so counting back that many messages puts
     * the divider exactly where the other clients put it. When the count covers the whole
     * cached thread the watermark sits just before the first message, so the divider lands
     * at the top instead of vanishing.
     */
    fun lastReadWatermark(
        module: ChatModule,
        projectId: String,
        scopeId: String,
        unreadCount: Int,
    ): Long {
        val all = realm.query<ChatMessageEntity>(
            "module == $0 AND projectId == $1 AND scopeId == $2 AND deleted == 0",
            module.key, projectId, scopeId,
        ).sort("created", Sort.ASCENDING).find()

        return if (unreadCount >= all.size) {
            all.firstOrNull()?.let { it.created - 1 } ?: 0
        } else {
            all[all.size - unreadCount - 1].created
        }
    }

    /** Whether anything is cached for this scope, including deleted rows. */
    fun hasCachedMessages(module: ChatModule, projectId: String, scopeId: String): Boolean =
        realm.query<ChatMessageEntity>(
            "module == $0 AND projectId == $1 AND scopeId == $2",
            module.key, projectId, scopeId,
        ).count().find() > 0

    /** Whether the thread has any *live* message — what Call Sheet's replace prompt asks. */
    fun hasAnyMessage(module: ChatModule, projectId: String, scopeId: String): Boolean =
        realm.query<ChatMessageEntity>(
            "module == $0 AND projectId == $1 AND scopeId == $2 AND deleted == 0",
            module.key, projectId, scopeId,
        ).count().find() > 0

    /**
     * The pagination cursor, taken from `updated` on **API-sourced rows only**.
     *
     * Socket rows and optimistic rows are excluded deliberately: their `updated` is this
     * device's clock, and letting one become the cursor would ask the server for a window
     * that never existed and silently skip messages.
     *
     * NEXT rewinds a second because the endpoint is exclusive; without it the newest
     * message is fetched again on every poll.
     */
    private fun cursorFor(
        module: ChatModule,
        projectId: String,
        scopeId: String,
        page: ChatPage,
    ): Long {
        val confirmed = realm.query<ChatMessageEntity>(
            "module == $0 AND projectId == $1 AND scopeId == $2 AND isFromApiCall == true",
            module.key, projectId, scopeId,
        ).sort("updated", Sort.ASCENDING).find()

        if (confirmed.isEmpty()) return 0

        return when (page) {
            ChatPage.PREVIOUS -> confirmed.first().updated
            ChatPage.NEXT -> confirmed.last().updated - 1000
        }
    }

    // --------------------------------------------------------------- writes

    /**
     * Fetches one page and stores it.
     *
     * A cold thread asks for everything before *now*; afterwards the cursor walks from the
     * rows already held.
     */
    suspend fun fetchPage(
        module: ChatModule,
        projectId: String,
        scopeId: String,
        page: ChatPage,
    ): ApiResult<Int> {
        val cursor = cursorFor(module, projectId, scopeId, page)
        val url = if (cursor == 0L) {
            module.previousPage(scopeId, System.currentTimeMillis())
        } else {
            when (page) {
                ChatPage.PREVIOUS -> module.previousPage(scopeId, cursor)
                ChatPage.NEXT -> module.nextPage(scopeId, cursor)
            }
        }

        return when (val result = api.get<ChatListResponse>(url, ModuleData.WITH_PROJECT_USER_ID)) {
            is ApiResult.Failure -> ApiResult.Failure(result.error)
            is ApiResult.Success -> {
                val messages = result.data.data.orEmpty()
                store(module, projectId, scopeId, messages, fromApi = true)
                ZillitLog.d(TAG, "Fetched ${messages.size} ${page.name} for $scopeId")
                ApiResult.Success(messages.size)
            }
        }
    }

    /** Socket-delivered messages. Not `fromApi` — they must not move the cursor. */
    suspend fun applyRealtimeMessage(
        module: ChatModule,
        projectId: String,
        messages: List<ChatMessageDto>,
    ) {
        messages.groupBy { it.unitId.orEmpty() }.forEach { (scopeId, group) ->
            if (scopeId.isNotBlank()) store(module, projectId, scopeId, group, fromApi = false)
        }
    }

    /**
     * Someone else deleted messages.
     *
     * Stamped rather than removed, so a fetch that was already in flight cannot resurrect
     * them — [observeMessages] filters on `deleted == 0`.
     */
    suspend fun applyRealtimeDelete(
        module: ChatModule,
        projectId: String,
        scopeId: String,
        serverIds: List<String>,
        deletedAt: Long,
    ) {
        realm.write {
            serverIds.forEach { serverId ->
                query<ChatMessageEntity>(
                    "module == $0 AND projectId == $1 AND scopeId == $2 AND serverId == $3",
                    module.key, projectId, scopeId, serverId,
                ).find().forEach { it.deleted = deletedAt }
            }
        }
    }

    /**
     * Posts a message.
     *
     * The body is encrypted here: the server stores ciphertext and every client decrypts
     * on read, so plaintext must never leave this method. `message_translation` carries the
     * same ciphertext — v2 sends both fields and the backend expects them to match.
     *
     * On success the returned row is stored with `fromApi = false`: it is one message, not
     * a page, so it must not become the pagination cursor.
     */
    suspend fun sendMessage(
        module: ChatModule,
        project: SessionStore.ActiveProject,
        scopeId: String,
        senderId: String,
        text: String,
        messageType: String,
        attachment: ChatAttachmentDto? = null,
        location: ChatLocationDto? = null,
        replacePreviousChats: Boolean? = null,
        isDistributeAutomatic: Boolean? = null,
        moduleName: String? = null,
        uniqueId: String = newUniqueId(senderId),
    ): ApiResult<Unit> {
        val projectId = project.projectId
        val now = System.currentTimeMillis()
        val body = if (text.isBlank()) "" else crypto.encrypt(text)

        val request = SendMessageRequest(
            unitId = scopeId,
            uniqueId = uniqueId,
            message = body,
            messageTranslation = body,
            messageType = messageType,
            messageGroup = now,
            attachment = attachment,
            location = location,
            replacePreviousChats = replacePreviousChats,
            isDistributeAutomatic = isDistributeAutomatic,
            moduleName = moduleName,
        )

        return when (
            val result = api.post<SendMessageRequest, ChatSingleResponse>(
                url = module.send,
                body = request,
                module = ModuleData.WITH_PROJECT_USER_ID,
                projectOverride = project,
            )
        ) {
            is ApiResult.Success -> {
                result.data.data?.let { store(module, projectId, scopeId, listOf(it), fromApi = false) }
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> {
                // The optimistic row stays on screen, marked failed, so the text is not
                // lost and Retry has something to act on.
                markFailed(module, projectId, scopeId, uniqueId)
                ApiResult.Failure(result.error)
            }
        }
    }

    /**
     * Queues a text message.
     *
     * Goes through [UploadQueue] rather than posting directly so that text and attachments
     * share one retry, one ordering and one offline story. The Realm row is written first
     * so the bubble appears on the same frame the user hits send.
     */
    suspend fun enqueueText(
        module: ChatModule,
        project: SessionStore.ActiveProject,
        scopeId: String,
        senderId: String,
        text: String,
        replyToServerId: String? = null,
    ): String {
        val uniqueId = newUniqueId(senderId)
        val now = System.currentTimeMillis()

        // A reply is not a message in the thread — it is posted onto its parent, so no
        // optimistic row is created for it here.
        if (replyToServerId.isNullOrBlank()) {
            realm.write {
                copyToRealm(
                    ChatMessageEntity().apply {
                        id = ChatMessageEntity.key(module.key, project.projectId, scopeId, uniqueId)
                        this.module = module.key
                        projectId = project.projectId
                        this.scopeId = scopeId
                        this.uniqueId = uniqueId
                        this.senderId = senderId
                        message = text
                        messageType = "text"
                        status = ChatMessageEntity.STATUS_UPLOADING
                        created = now
                        updated = now
                    },
                    UpdatePolicy.ALL,
                )
            }
        }

        uploadQueue.enqueueTextMessage(
            id = uniqueId,
            projectId = project.projectId,
            userId = senderId,
            module = module.key,
            scopeId = scopeId,
            text = text,
            replyToServerId = replyToServerId.orEmpty(),
        )
        return uniqueId
    }

    /**
     * The optimistic row for an attachment, written before the upload starts.
     *
     * Carries the **local** path so the bubble can show the picked image immediately;
     * [applyDto] swaps in the remote URL once the server confirms.
     */
    suspend fun createPendingAttachmentRow(
        module: ChatModule,
        projectId: String,
        scopeId: String,
        uniqueId: String,
        senderId: String,
        caption: String,
        messageType: String,
        localPath: String,
        fileName: String,
        sizeBytes: Long,
        durationMs: Long,
        messageGroup: Long,
        /**
         * The pin, when the attachment is a map snapshot.
         *
         * Stored on the optimistic row so the sender's own bubble can open a maps app
         * straight away — the coordinates otherwise only arrive with the server's echo,
         * and a pin you cannot open until then reads as broken.
         */
        location: com.zillit.zillitapp.core.storage.PendingLocation? = null,
    ) {
        val now = System.currentTimeMillis()
        realm.write {
            copyToRealm(
                ChatMessageEntity().apply {
                    id = ChatMessageEntity.key(module.key, projectId, scopeId, uniqueId)
                    this.module = module.key
                    this.projectId = projectId
                    this.scopeId = scopeId
                    this.uniqueId = uniqueId
                    this.senderId = senderId
                    message = caption
                    this.messageType = messageType
                    status = ChatMessageEntity.STATUS_UPLOADING
                    uploadProgress = 0f
                    localFilePath = localPath
                    attachmentName = fileName
                    attachmentSize = sizeBytes.toString()
                    attachmentDuration = durationMs
                    this.messageGroup = messageGroup.takeIf { it != 0L } ?: now
                    created = now
                    updated = now
                    location?.let {
                        locationLatitude = it.latitude
                        locationLongitude = it.longitude
                        locationAddress = it.address
                    }
                },
                UpdatePolicy.ALL,
            )
        }
    }

    /** Drives the progress ring on the bubble. */
    suspend fun updateUploadProgress(
        module: ChatModule,
        projectId: String,
        scopeId: String,
        uniqueId: String,
        fraction: Float,
    ) {
        realm.write {
            query<ChatMessageEntity>(
                "id == $0", ChatMessageEntity.key(module.key, projectId, scopeId, uniqueId),
            ).first().find()?.apply {
                status = ChatMessageEntity.STATUS_UPLOADING
                uploadProgress = fraction
            }
        }
    }

    /** Puts a failed row back in the queue and re-drives the worker. */
    suspend fun retry(
        module: ChatModule,
        project: SessionStore.ActiveProject,
        scopeId: String,
        uniqueId: String,
    ) {
        realm.write {
            query<ChatMessageEntity>(
                "id == $0", ChatMessageEntity.key(module.key, project.projectId, scopeId, uniqueId),
            ).first().find()?.status = ChatMessageEntity.STATUS_QUEUED
        }
        uploadQueue.retry(uniqueId)
    }

    /**
     * Deletes messages by server id.
     *
     * Local rows are stamped only after the server accepts. Removing them first would make
     * a failed delete look like it worked until the next refresh brought them back.
     */
    suspend fun deleteMessages(
        module: ChatModule,
        project: SessionStore.ActiveProject,
        scopeId: String,
        serverIds: List<String>,
    ): ApiResult<Unit> {
        if (serverIds.isEmpty()) return ApiResult.Success(Unit)

        return when (
            val result = api.put<DeleteMessagesRequest, DeleteResponse>(
                url = module.delete,
                body = DeleteMessagesRequest(chatIds = serverIds),
                module = ModuleData.WITH_PROJECT_USER_ID,
            )
        ) {
            is ApiResult.Failure -> ApiResult.Failure(result.error)
            is ApiResult.Success -> {
                // The server's own stamp where it gives one, so this device agrees with
                // every other client rather than using its own clock.
                applyRealtimeDelete(
                    module = module,
                    projectId = project.projectId,
                    scopeId = scopeId,
                    serverIds = result.data.data?.chatIds?.takeIf { it.isNotEmpty() } ?: serverIds,
                    deletedAt = result.data.data?.deleted ?: System.currentTimeMillis(),
                )
                ApiResult.Success(Unit)
            }
        }
    }

    /** Posts a reply onto a message. The response carries the parent with its comments. */
    suspend fun addReply(
        module: ChatModule,
        project: SessionStore.ActiveProject,
        scopeId: String,
        chatServerId: String,
        text: String,
    ): ApiResult<Unit> {
        val body = if (text.isBlank()) "" else crypto.encrypt(text)

        return when (
            val result = api.post<ReplyRequest, ChatSingleResponse>(
                url = module.addReply(chatServerId),
                body = ReplyRequest(message = body, messageTranslation = body),
                module = ModuleData.WITH_PROJECT_USER_ID,
                projectOverride = project,
            )
        ) {
            is ApiResult.Failure -> ApiResult.Failure(result.error)
            is ApiResult.Success -> {
                result.data.data?.let {
                    store(module, project.projectId, scopeId, listOf(it), fromApi = false)
                }
                ApiResult.Success(Unit)
            }
        }
    }

    /** Edits a message body. `{send}/{chatServerId}` — the same route with an id appended. */
    suspend fun editMessage(
        module: ChatModule,
        project: SessionStore.ActiveProject,
        scopeId: String,
        chatServerId: String,
        text: String,
    ): ApiResult<Unit> {
        val body = if (text.isBlank()) "" else crypto.encrypt(text)

        return when (
            val result = api.put<ReplyRequest, ChatSingleResponse>(
                url = "${module.send}/$chatServerId",
                body = ReplyRequest(message = body, messageTranslation = body),
                module = ModuleData.WITH_PROJECT_USER_ID,
            )
        ) {
            is ApiResult.Failure -> ApiResult.Failure(result.error)
            is ApiResult.Success -> {
                result.data.data?.let {
                    store(module, project.projectId, scopeId, listOf(it), fromApi = false)
                }
                ApiResult.Success(Unit)
            }
        }
    }

    suspend fun editReply(
        module: ChatModule,
        project: SessionStore.ActiveProject,
        scopeId: String,
        chatServerId: String,
        commentId: String,
        text: String,
    ): ApiResult<Unit> {
        val body = if (text.isBlank()) "" else crypto.encrypt(text)

        return when (
            val result = api.put<ReplyRequest, ChatSingleResponse>(
                url = module.editReply(chatServerId, commentId),
                body = ReplyRequest(message = body, messageTranslation = body),
                module = ModuleData.WITH_PROJECT_USER_ID,
            )
        ) {
            is ApiResult.Failure -> ApiResult.Failure(result.error)
            is ApiResult.Success -> {
                result.data.data?.let {
                    store(module, project.projectId, scopeId, listOf(it), fromApi = false)
                }
                ApiResult.Success(Unit)
            }
        }
    }

    suspend fun deleteReply(
        module: ChatModule,
        project: SessionStore.ActiveProject,
        scopeId: String,
        chatServerId: String,
        commentId: String,
    ): ApiResult<Unit> =
        when (
            val result = api.delete<ChatSingleResponse>(
                url = module.deleteReply(chatServerId, commentId),
                module = ModuleData.WITH_PROJECT_USER_ID,
            )
        ) {
            is ApiResult.Failure -> ApiResult.Failure(result.error)
            is ApiResult.Success -> {
                // The delete response does not return the parent, so the reply is removed
                // locally rather than waiting for a refetch to notice it is gone.
                removeReplyLocally(module, project.projectId, scopeId, chatServerId, commentId)
                ApiResult.Success(Unit)
            }
        }

    private suspend fun removeReplyLocally(
        module: ChatModule,
        projectId: String,
        scopeId: String,
        chatServerId: String,
        commentId: String,
    ) {
        realm.write {
            val parent = query<ChatMessageEntity>(
                "module == $0 AND projectId == $1 AND scopeId == $2 AND serverId == $3",
                module.key, projectId, scopeId, chatServerId,
            ).first().find() ?: return@write

            parent.replies.removeAll { it.commentId == commentId }
        }
    }

    /**
     * Stores a translation over the original.
     *
     * The translated text takes the display field and the original moves aside, with
     * `isTranslated` set so [applyDto] will not overwrite it on the next refetch — the
     * server has no idea this device translated anything.
     */
    suspend fun applyTranslation(
        module: ChatModule,
        projectId: String,
        scopeId: String,
        uniqueId: String,
        original: String,
        translated: String,
        commentId: String? = null,
    ) {
        realm.write {
            val row = query<ChatMessageEntity>(
                "id == $0", ChatMessageEntity.key(module.key, projectId, scopeId, uniqueId),
            ).first().find() ?: return@write

            if (commentId == null) {
                row.message = translated
                row.messageTranslation = original
                row.isTranslated = true
            } else {
                row.replies.firstOrNull { it.commentId == commentId }?.apply {
                    message = translated
                    messageTranslation = original
                }
            }
        }
    }

    /**
     * The watermarked object key for a Call Sheet document.
     *
     * Fetched per open rather than cached: the stamp carries the reader's identity, so a
     * shared key would defeat the point of watermarking.
     */
    suspend fun watermarkedKey(module: ChatModule, chatServerId: String): String? =
        when (
            val result = api.get<ChatSingleAttachmentResponse>(
                url = module.watermark(chatServerId),
                module = ModuleData.WITH_PROJECT_USER_ID,
            )
        ) {
            is ApiResult.Success -> result.data.data?.media
            is ApiResult.Failure -> {
                ZillitLog.w(TAG, "Watermark fetch failed for $chatServerId: ${result.error.message}")
                null
            }
        }

    /** Drops a project's cached chat. For leaving a project or signing out. */
    suspend fun clearProject(projectId: String) {
        realm.write { delete(query<ChatMessageEntity>("projectId == $0", projectId).find()) }
    }

    // -------------------------------------------------------------- storage

    private suspend fun markFailed(
        module: ChatModule,
        projectId: String,
        scopeId: String,
        uniqueId: String,
    ) {
        realm.write {
            query<ChatMessageEntity>(
                "id == $0", ChatMessageEntity.key(module.key, projectId, scopeId, uniqueId),
            ).first().find()?.status = ChatMessageEntity.STATUS_FAILED
        }
    }

    /**
     * Upserts messages.
     *
     * @param fromApi true only for the list endpoints. Socket and POST rows must not set
     *   it, because it is what anchors the pagination cursor — see [cursorFor].
     */
    private suspend fun store(
        module: ChatModule,
        projectId: String,
        scopeId: String,
        messages: List<ChatMessageDto>,
        fromApi: Boolean,
    ) {
        realm.write {
            messages.forEach { dto ->
                // A message with neither id is unusable: it can't be keyed, replied to or
                // deleted. Dropping it beats storing a row nothing can address.
                val uniqueId = dto.uniqueId?.takeIf { it.isNotBlank() }
                    ?: dto.id?.takeIf { it.isNotBlank() }
                    ?: return@forEach

                val key = ChatMessageEntity.key(module.key, projectId, scopeId, uniqueId)
                val existing = query<ChatMessageEntity>("id == $0", key).first().find()

                // Never let an older payload overwrite a newer one. The socket event and
                // the reconcile fetch describe the same message and can arrive in either
                // order; without this the fetch can undo an edit the socket just applied.
                //
                // Freshness is the newest of every stamp, not `updated` alone: an **edit
                // does not bump `updated`** — it sets `edited` and leaves `updated` at the
                // creation time. Comparing `updated` only therefore rejected an edit that
                // arrived after anything which had bumped it (a reply, a read receipt), and
                // the message silently kept its old text.
                val incomingUpdated = dto.freshness()
                if (existing != null && incomingUpdated < existing.freshness()) {
                    // Still promote the API flag: the row is confirmed even if not newer.
                    if (fromApi) existing.isFromApiCall = true
                    return@forEach
                }

                val entity = existing ?: ChatMessageEntity().apply {
                    this.id = key
                    this.module = module.key
                    this.projectId = projectId
                    this.scopeId = scopeId
                    this.uniqueId = uniqueId
                }

                entity.applyDto(dto, fromApi)

                if (existing == null) copyToRealm(entity, UpdatePolicy.ALL)
            }
        }
    }

    private fun ChatMessageEntity.applyDto(dto: ChatMessageDto, fromApi: Boolean) {
        serverId = dto.id.orEmpty().ifBlank { serverId }
        senderId = dto.sender.orEmpty().ifBlank { senderId }
        // Decrypted here, once — not on every bind as v2 does.
        //
        // A locally translated row keeps its composed body: the server has no idea this
        // device translated anything, so letting a routine refetch overwrite it would make
        // the translation vanish the next time the thread reconciles.
        if (!isTranslated) {
            message = dto.message?.let { crypto.decrypt(it) }.orEmpty()
            messageTranslation = dto.messageTranslation?.let { crypto.decrypt(it) }.orEmpty()
        }
        messageType = dto.messageType?.lowercase() ?: messageType
        messageGroup = dto.messageGroup ?: messageGroup
        created = dto.created ?: created
        updated = dto.updated ?: dto.created ?: updated
        deleted = dto.deleted ?: deleted
        edited = dto.edited ?: edited
        // Anything the server has echoed back is confirmed, whichever channel carried it.
        status = ChatMessageEntity.STATUS_CONFIRMED
        uploadProgress = 0f
        if (fromApi) isFromApiCall = true

        dto.attachment?.let { att ->
            attachmentUrl = att.media
            attachmentName = att.name
            attachmentSize = att.fileSize
            attachmentThumbnail = att.thumbnail
            attachmentContentType = att.contentType
            attachmentDuration = att.duration ?: 0
            attachmentWidth = att.width ?: 0
            attachmentHeight = att.height ?: 0
        }

        dto.location?.let { loc ->
            locationLatitude = loc.latitude ?: 0.0
            locationLongitude = loc.longitude ?: 0.0
            locationAddress = loc.address
        }

        dto.comments?.let { comments ->
            // Replaced wholesale rather than merged: the server sends the full comment
            // list, and merging would strand a reply that was deleted elsewhere.
            replies = comments
                .filter { (it.deleted ?: 0) == 0L }
                .map { comment ->
                    ChatReplyEntity().apply {
                        commentId = comment.id.orEmpty()
                        senderId = comment.sender.orEmpty()
                        message = comment.message?.let { crypto.decrypt(it) }.orEmpty()
                        messageTranslation =
                            comment.messageTranslation?.let { crypto.decrypt(it) }.orEmpty()
                        messageType = comment.messageType?.lowercase() ?: "text"
                        created = comment.created ?: 0
                        updated = comment.updated ?: 0
                        deleted = comment.deleted ?: 0
                        edited = comment.edited ?: 0
                        attachmentUrl = comment.attachment?.media
                        attachmentName = comment.attachment?.name
                        attachmentSize = comment.attachment?.fileSize
                        attachmentThumbnail = comment.attachment?.thumbnail
                    }
                }
                .toRealmList()

            ZillitLog.d(
                TAG,
                "applyDto ${dto.id}: ${comments.size} comment(s) in payload → ${replies.size} live reply(ies)",
            )
        }

        rawJson = json.encodeToString(ChatMessageDto.serializer(), dto)
    }

    /**
     * The newest moment this version of the message is known to represent.
     *
     * Every stamp counts because the backend bumps different ones for different actions:
     * `updated` for a reply, `edited` for an edit, `deleted` for a delete. Taking the max
     * gives one comparable number regardless of which action produced the payload.
     */
    private fun ChatMessageDto.freshness(): Long =
        maxOf(updated ?: 0, edited ?: 0, deleted ?: 0, created ?: 0)

    private fun ChatMessageEntity.freshness(): Long =
        maxOf(updated, edited, deleted, created)

    private fun newUniqueId(senderId: String): String = ChatIds.newUniqueId(senderId)

    private companion object {
        const val TAG = "ChatRepository"
    }
}
