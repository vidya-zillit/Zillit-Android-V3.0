package com.zillit.zillitapp.core.chat.data

import com.zillit.zillitapp.core.database.entity.ChatMessageEntity
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.network.ZillitCrypto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The messages a thread has replaced or deleted.
 *
 * **Not stored in Realm.** History is read-only, viewed occasionally, and would otherwise
 * collide with the live thread's rows — the same message id exists in both, with different
 * content. So it is fetched, mapped and held for the life of the screen, and the live cache
 * stays the single source of truth for what a thread currently is.
 *
 * One repository for every surface, because the endpoint shape is identical: it is the
 * thread's own list endpoint with `deleted` set. Call Sheet's replace flow is what fills it
 * on Home — each new call sheet pushes the previous one here — but Info, Confidential Info,
 * Production Report and the script tools all read the same way.
 */
@Singleton
class ChatHistoryRepository @Inject constructor(
    private val api: ZillitApi,
    private val crypto: ZillitCrypto,
) {

    /**
     * One page of history, newest first.
     *
     * @param before pass 0 for the first page, then the oldest `created` you already hold
     *   to page further back — the same cursor rule as the live thread.
     * @param episode set on the script tools, which partition history by episode. Ignored
     *   elsewhere; v2 sends it only where the project type is television.
     */
    suspend fun page(
        module: ChatModule,
        scopeId: String,
        before: Long = 0,
        page: Int = FIRST_PAGE,
        episode: String? = null,
    ): List<ChatMessageEntity> {
        val cursor = before.takeIf { it > 0 } ?: System.currentTimeMillis()

        val query = buildMap {
            // **Zero-indexed.** v2 starts at `CURRENT_PAGE = 0`; asking for page 1 on the
            // first call requests the *second* page and comes back empty, which reads as
            // "no history" when there is plenty.
            put("page", page.toString())
            put("limit", PAGE_LIMIT.toString())
            // The flag that turns the list endpoint into a history endpoint.
            put("deleted", cursor.toString())
            episode?.takeIf { it.isNotBlank() }?.let { put("episode", it) }
        }

        val result = api.get<ChatListResponse>(
            url = module.historyPage(scopeId, cursor),
            module = ModuleData.WITH_PROJECT_USER_ID,
            query = query,
        )

        return when (result) {
            is ApiResult.Success -> result.data.data.orEmpty().map { it.toDetachedEntity() }
            is ApiResult.Failure -> {
                ZillitLog.w(TAG, "History fetch failed for $scopeId: ${result.error.message}")
                emptyList()
            }
        }
    }

    /**
     * A message shaped like a stored one, but never written.
     *
     * Reusing [ChatMessageEntity] means history renders through the same mapper and the
     * same bubbles as a live thread — which is the whole point of history looking identical
     * to chat. It is deliberately unmanaged: writing it would corrupt the live cache.
     */
    private fun ChatMessageDto.toDetachedEntity(): ChatMessageEntity = ChatMessageEntity().also {
        it.serverId = id.orEmpty()
        it.uniqueId = uniqueId.orEmpty().ifBlank { id.orEmpty() }
        it.senderId = sender.orEmpty()
        it.message = message?.let { text -> crypto.decrypt(text) }.orEmpty()
        it.messageTranslation =
            messageTranslation?.let { text -> crypto.decrypt(text) }.orEmpty()
        it.messageType = messageType?.lowercase() ?: "text"
        it.messageGroup = messageGroup ?: 0
        it.created = created ?: 0
        // Left at 0 deliberately: every row here is deleted by definition, and a non-zero
        // value would make the live thread's "hide deleted" filter drop the whole screen.
        it.deleted = 0
        it.edited = edited ?: 0
        // Reused to carry `archived`: history pages by when a message was superseded, and
        // the entity has no column of its own for it. Nothing renders `updated` on a
        // history row, so borrowing it costs nothing and keeps the cursor honest.
        it.updated = archived ?: updated ?: created ?: 0
        it.status = ChatMessageEntity.STATUS_CONFIRMED

        attachment?.let { att ->
            it.attachmentUrl = att.media
            it.attachmentName = att.name
            it.attachmentSize = att.fileSize
            it.attachmentThumbnail = att.thumbnail
            it.attachmentContentType = att.contentType
            it.attachmentDuration = att.duration ?: 0
            it.attachmentWidth = att.width ?: 0
            it.attachmentHeight = att.height ?: 0
        }

        location?.let { loc ->
            it.locationLatitude = loc.latitude ?: 0.0
            it.locationLongitude = loc.longitude ?: 0.0
            it.locationAddress = loc.address
        }
    }

    private companion object {
        const val TAG = "ChatHistoryRepository"
        /** v2's `CURRENT_PAGE` starts at 0. */
        const val FIRST_PAGE = 0

        /** v2's `PAGE_LIMIT`. */
        const val PAGE_LIMIT = 100
    }
}
