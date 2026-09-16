package com.zillit.zillitapp.feature.email.data

import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.EmailEntity
import com.zillit.zillitapp.core.database.entity.EmailFolderEntity
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.NetworkMonitor
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.email.domain.Email
import com.zillit.zillitapp.feature.email.domain.EmailFolder
import com.zillit.zillitapp.feature.email.domain.EmailFolders
import com.zillit.zillitapp.feature.email.domain.EmailThread
import com.zillit.zillitapp.feature.email.domain.MailboxScope
import com.zillit.zillitapp.feature.email.domain.ThreadCounts
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.query
import io.realm.kotlin.ext.toRealmList
import io.realm.kotlin.query.Sort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mail, folders and drafts.
 *
 * **Realm-first.** Nothing here hands network data to the UI: fetches write to Realm and the
 * UI observes Realm, which is what makes a folder render instantly on reopen, survive a
 * failed refresh, and update the moment a socket event lands.
 *
 * Drafts are the exception, and unavoidably so — they have no uid, so the folder sync has
 * nothing to diff, and the service offers no way to enumerate them into a cache. They are
 * fetched live, which is also why they are invisible offline and excluded from search.
 */
@Singleton
class EmailRepository @Inject constructor(
    private val api: EmailApi,
    private val realmProvider: RealmProvider,
    private val session: SessionStore,
    private val mailbox: EmailMailboxContext,
    private val badges: EmailBadges,
    private val network: NetworkMonitor,
) {

    private val realm get() = realmProvider.realm

    private val projectId: String get() = session.activeProject.value?.projectId.orEmpty()

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * A folder's messages, newest first.
     *
     * Read state is **not** resolved here. It lives in the badge tree, which changes without
     * Realm changing, so the caller combines this flow with the badge flow — see
     * `EmailListViewModel`. Resolving it here would produce a list that silently goes stale.
     */
    fun observeEmails(folderName: String): Flow<List<EmailEntity>> =
        realm.query<EmailEntity>(
            "projectId == $0 AND mailboxScope == $1 AND folderName == $2",
            projectId, mailbox.effectiveScope.key, folderName,
        )
            .sort("createdAt", Sort.DESCENDING)
            .asFlow()
            .map { it.list.toList() }

    fun observeFolders(): Flow<List<EmailFolderEntity>> =
        realm.query<EmailFolderEntity>(
            "projectId == $0 AND mailboxScope == $1",
            projectId, mailbox.effectiveScope.key,
        )
            .sort("sortOrder", Sort.ASCENDING)
            .asFlow()
            .map { it.list.toList() }

    /**
     * Every cached message in the mailbox, for cross-folder thread counts.
     *
     * A reply lives in Sent while its original is in Inbox, so a count taken within one
     * folder tells the user a two-message conversation has one message in it.
     */
    fun observeAll(): Flow<List<EmailEntity>> =
        realm.query<EmailEntity>(
            "projectId == $0 AND mailboxScope == $1",
            projectId, mailbox.effectiveScope.key,
        )
            .asFlow()
            .map { it.list.toList() }

    /**
     * Local search.
     *
     * There is no search endpoint, so this can only see what has synced — which is why the
     * screen says "Search your emails" rather than promising more, and why Drafts are
     * excluded from the folder chips.
     *
     * The body is deliberately not searched: on a phone, scanning every cached body is slow
     * enough to read as broken, and it is not what people search by.
     */
    fun search(
        query: String,
        folders: Set<String>,
        fields: Set<SearchableField>,
    ): Flow<List<EmailEntity>> {
        if (query.isBlank() || folders.isEmpty() || fields.isEmpty()) return flowOf(emptyList())

        val term = query.trim()

        // Built with explicit positional placeholders rather than by interpolating the
        // values into the predicate: Realm parses the string, so an interpolated folder
        // name containing a space — "Distributed Mails" — is a syntax error, and a query
        // built that way throws rather than returning nothing.
        val arguments = mutableListOf<Any>(projectId, mailbox.effectiveScope.key)

        val folderClause = folders.joinToString(" OR ") { folder ->
            arguments += folder
            "folderName == $${arguments.lastIndex}"
        }

        val fieldClause = fields.joinToString(" OR ") { field ->
            // "From" means the person, and people are found by name as often as by
            // address — so it tests both columns rather than only the address.
            if (field == SearchableField.FROM) {
                arguments += term
                val nameArg = arguments.lastIndex
                arguments += term
                return@joinToString "(fromName CONTAINS[c] $$nameArg " +
                    "OR fromAddress CONTAINS[c] $${arguments.lastIndex})"
            }
            arguments += term
            // `to`, `cc` and `bcc` are lists, where a bare CONTAINS tests **membership**
            // and would only ever match a whole address typed exactly. `ANY` applies the
            // substring test to each element instead, which is what searching by a name
            // or a domain fragment means.
            val prefix = if (field.isCollection) "ANY " else ""
            "$prefix${field.property} CONTAINS[c] $${arguments.lastIndex}"
        }

        // A live query, not a snapshot: mail arriving while the results are on screen
        // belongs in them. Taking the list once meant a message that synced a second later
        // was simply absent until the user retyped the search.
        return realm.query<EmailEntity>(
            "projectId == $0 AND mailboxScope == $1 AND ($folderClause) AND ($fieldClause)",
            *arguments.toTypedArray(),
        )
            .sort("createdAt", Sort.DESCENDING)
            .asFlow()
            .map { it.list.toList() }
    }

    /** Every message in a thread, across folders. Trash is its own world — see below. */
    fun threadMessages(threadId: String, currentFolder: String): List<EmailEntity> {
        val rows = realm.query<EmailEntity>(
            "projectId == $0 AND mailboxScope == $1 AND threadId == $2",
            projectId, mailbox.effectiveScope.key, threadId,
        ).find()

        // Opening a thread from Trash shows only its deleted messages, and opening it from
        // anywhere else hides them. Mixing the two would make a deleted reply reappear in a
        // conversation the user believes they cleaned up.
        return if (currentFolder.equals(EmailFolders.TRASH, ignoreCase = true)) {
            rows.filter { it.folderName.equals(EmailFolders.TRASH, ignoreCase = true) }
        } else {
            rows.filterNot { it.folderName.equals(EmailFolders.TRASH, ignoreCase = true) }
        }
    }

    /**
     * Groups a folder's rows into conversations, with counts spanning every folder.
     *
     * [currentFolder] decides the Trash boundary, the same one [threadMessages] applies:
     * outside Trash a conversation's count excludes messages the user has already deleted,
     * and inside Trash it counts only those. Without it a row said "(5)" and opening it
     * showed three.
     */
    fun toThreads(
        rows: List<Email>,
        all: List<Email>,
        currentFolder: String = EmailFolders.INBOX,
    ): List<EmailThread> {
        val inTrash = currentFolder.equals(EmailFolders.TRASH, ignoreCase = true)
        val visible = all.filter { email ->
            email.folderName.equals(EmailFolders.TRASH, ignoreCase = true) == inTrash
        }

        val crossFolder = visible
            .distinctBy { it.id }
            .groupBy { it.threadId }
            .mapValues { (_, messages) ->
                ThreadCounts(
                    total = messages.size,
                    unread = messages.count { it.isUnread },
                )
            }

        return rows
            .groupBy { it.threadId }
            .map { (threadId, messages) ->
                EmailThread(
                    threadId = threadId,
                    emails = messages.sortedByDescending { it.createdAt },
                    crossFolder = crossFolder[threadId],
                )
            }
            .sortedByDescending { it.latest.createdAt }
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    /**
     * Refreshes the folder list.
     *
     * Replace rather than merge: a folder deleted on another device has to disappear, and
     * the response is the complete list, so a diff would be more code for the same result.
     */
    suspend fun syncFolders(): ApiResult<Unit> {
        if (!network.isOnline.value) return ApiResult.Success(Unit)

        val scope = mailbox.effectiveScope
        val project = projectId

        return when (val result = api.folders()) {
            is ApiResult.Failure -> result

            is ApiResult.Success -> {
                val live = result.data.filter { it.deleted == 0 }

                realm.write {
                    delete(
                        query<EmailFolderEntity>(
                            "projectId == $0 AND mailboxScope == $1",
                            project, scope.key,
                        ).find(),
                    )
                    // Custom folders are sorted by name rather than left in the order the
                    // server created them: a mailbox with twenty of them is unusable in
                    // creation order, and alphabetical is what v2 shows.
                    val ordered = live.sortedWith(
                        compareBy(
                            { SYSTEM_ORDER.indexOf(it.folderName).takeIf { i -> i >= 0 } ?: SYSTEM_ORDER.size },
                            { it.folderName.lowercase() },
                        ),
                    )
                    ordered.forEachIndexed { index, dto ->
                        copyToRealm(dto.toEntity(project, scope, index))
                    }
                }
                ApiResult.Success(Unit)
            }
        }
    }

    /**
     * Brings one folder's cache in line with the server.
     *
     * The whole algorithm exists because **there is no pagination anywhere in this
     * service** — `get-folder-uids` returns the folder's complete uid list, every time. So
     * the sync diffs that list against what is cached and fetches only the difference:
     *
     *  1. capture the mailbox scope **once**, so a switch mid-sync cannot tag half the rows
     *     with the wrong mailbox;
     *  2. drop local placeholder rows for sends that have since landed;
     *  3. delete rows the server no longer lists, and retire their badges — a rule moved
     *     them, another device deleted them, or they were filed elsewhere, and from here
     *     all three look the same;
     *  4. fetch the missing headers in batches of 50.
     *
     * Bodies are **not** fetched here. Opening a message calls [fetchTrail].
     */
    suspend fun syncFolder(folderName: String): ApiResult<Unit> {
        if (!network.isOnline.value) return ApiResult.Success(Unit)

        // Drafts have no uids, so none of this applies to them.
        if (folderName.equals(EmailFolders.DRAFTS, ignoreCase = true)) {
            return ApiResult.Success(Unit)
        }

        val scope = mailbox.effectiveScope
        val accounts = scope == MailboxScope.SHARED
        val project = projectId

        val serverUids = when (val result = api.folderUids(folderName, accounts)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data.map { it.uid }
        }

        val serverSet = serverUids.toSet()

        // A row with uid 0 is a local placeholder for a send in flight; once the real one
        // arrives from the server, the placeholder is what would show as a duplicate.
        realm.write {
            delete(
                query<EmailEntity>(
                    "projectId == $0 AND mailboxScope == $1 AND folderName == $2 AND uid == 0",
                    project, scope.key, folderName,
                ).find(),
            )
        }

        val local = realm.query<EmailEntity>(
            "projectId == $0 AND mailboxScope == $1 AND folderName == $2",
            project, scope.key, folderName,
        ).find()

        val localUids = local.map { it.uid }.toSet()

        val departed = local.filter { it.uid !in serverSet }.map { it.uid to it.id }
        if (departed.isNotEmpty()) {
            realm.write {
                departed.forEach { (_, rowId) ->
                    query<EmailEntity>("id == $0", rowId).first().find()?.let { delete(it) }
                }
            }
            // The mail is gone, so its unread badge has to go with it — otherwise the
            // folder shows a count for something the user can no longer open.
            departed.forEach { (uid, _) -> badges.markRead(uid) }
        }

        val missing = serverUids.filterNot { it in localUids }
        if (missing.isEmpty()) return ApiResult.Success(Unit)

        missing.chunked(INDEX_BATCH).forEach { batch ->
            when (val result = api.emailIndex(batch, folderName, accounts)) {
                is ApiResult.Failure -> {
                    // One failed batch is not a failed sync: the rest of the folder is
                    // still worth having, and the next sync retries what is missing.
                    ZillitLog.w(TAG, "index batch failed for $folderName: ${result.error.message}")
                }

                is ApiResult.Success -> realm.write {
                    result.data.emails.forEach { dto ->
                        copyToRealm(dto.toEntity(project, scope), UpdatePolicy.ALL)
                    }
                }
            }
        }

        return ApiResult.Success(Unit)
    }

    /**
     * Full message bodies, cached on the way through.
     *
     * The only place a body reaches Realm, which is what makes a message that has been
     * opened once readable offline afterwards.
     */
    /**
     * The cached bodies for a trail, in the order they were written.
     *
     * Only messages whose body has actually been downloaded: an index row carries a subject
     * and a sender and nothing to read, and rendering one as a message would look like a
     * mail that had lost its contents.
     */
    fun cachedTrail(folderName: String, messageIds: List<String>): List<Email> {
        if (messageIds.isEmpty()) return emptyList()

        return realm.query<EmailEntity>(
            "projectId == $0 AND mailboxScope == $1 AND folderName == $2 AND messageId IN $3 AND bodyLoaded == true",
            projectId,
            mailbox.effectiveScope.name,
            folderName,
            messageIds,
        ).find().map { it.toDomain() }
    }

    suspend fun fetchTrail(folderName: String, messageIds: List<String>): ApiResult<List<Email>> {
        if (messageIds.isEmpty()) return ApiResult.Success(emptyList())

        val scope = mailbox.effectiveScope
        val project = projectId

        return when (val result = api.trail(folderName, messageIds)) {
            is ApiResult.Failure -> result

            is ApiResult.Success -> {
                // Best effort: a cache write that fails must never fail the open.
                runCatching {
                    realm.write {
                        result.data.forEach { dto ->
                            copyToRealm(
                                dto.toEntity(project, scope, bodyLoaded = true),
                                UpdatePolicy.ALL,
                            )
                        }
                    }
                }.onFailure { ZillitLog.w(TAG, "could not cache trail: ${it.message}") }

                ApiResult.Success(result.data.map { it.toDomain() })
            }
        }
    }

    /**
     * An attachment's bytes, base64 as the service returns them.
     *
     * The whole file arrives inside the JSON — there is no streaming and no presigned URL —
     * so this is only worth calling for something the user asked for, or for an inline
     * image the body cannot render without.
     */
    suspend fun attachmentBase64(
        folderName: String,
        messageId: String,
        attachmentId: String,
    ): ApiResult<String> = when (val result = api.attachment(attachmentId, messageId, folderName)) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> ApiResult.Success(result.data.base64)
    }

    /**
     * Inline images, as `data:` URIs keyed by content id.
     *
     * Fetched through the mail service rather than from S3 so the body's `WebView` needs no
     * file access and no network permission of its own — an attacker-authored body then has
     * nothing to reach for. Failures are skipped: a broken image is honest, and one
     * unfetchable logo must not stop the message rendering.
     */
    suspend fun inlineImages(email: Email): Map<String, String> {
        // An image referenced by `cid:` is matched on its content id, but some senders
        // reference the part id instead — so both are indexed, which is v2's rule too.
        val inline = email.attachments.filter { it.isInline && it.contentId.isNotBlank() }
        if (inline.isEmpty()) return emptyMap()

        val resolved = mutableMapOf<String, String>()

        inline.forEach { attachment ->
            val base64 = (
                attachmentBase64(
                    folderName = email.folderName,
                    messageId = email.id,
                    attachmentId = attachment.attachmentId,
                ) as? ApiResult.Success
                )?.data?.takeIf { it.isNotBlank() } ?: return@forEach

            val mime = attachment.contentType.ifBlank { "image/png" }
            val uri = "data:$mime;base64,$base64"

            resolved[attachment.contentId.trim('<', '>')] = uri
            attachment.id.takeIf { it.isNotBlank() }?.let { resolved[it] = uri }
            attachment.attachmentId.takeIf { it.isNotBlank() }?.let { resolved[it] = uri }
        }

        return resolved
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Moves messages.
     *
     * Rows are **deleted and re-fetched** rather than edited in place, because the row's
     * primary key embeds its folder. v2 rewrites `folderName` and leaves the key alone, so
     * the next sync inserts a second row for the same message.
     */
    suspend fun move(
        messageIds: List<String>,
        source: String,
        target: String,
    ): ApiResult<Unit> {
        val result = api.move(messageIds, source, target)
        if (result is ApiResult.Failure) return result

        val scope = mailbox.effectiveScope
        val project = projectId

        realm.write {
            messageIds.forEach { messageId ->
                query<EmailEntity>(
                    "id == $0",
                    EmailEntity.primaryKey(project, scope.key, source, messageId),
                ).first().find()?.let { delete(it) }
            }
        }

        // The destination's cache is now short a message; the source's badge, if any,
        // belongs to a mail that is no longer there.
        syncFolder(target)
        return ApiResult.Success(Unit)
    }

    suspend fun delete(folderName: String, messageIds: List<String>): ApiResult<Unit> {
        val result = api.delete(messageIds)
        if (result is ApiResult.Failure) return result

        val scope = mailbox.effectiveScope
        val project = projectId

        realm.write {
            messageIds.forEach { messageId ->
                query<EmailEntity>(
                    "id == $0",
                    EmailEntity.primaryKey(project, scope.key, folderName, messageId),
                ).first().find()?.let { delete(it) }
            }
        }
        return ApiResult.Success(Unit)
    }

    suspend fun emptyTrash(): ApiResult<Unit> {
        val result = api.emptyTrash()
        if (result is ApiResult.Failure) return result

        val scope = mailbox.effectiveScope
        val project = projectId

        realm.write {
            delete(
                query<EmailEntity>(
                    "projectId == $0 AND mailboxScope == $1 AND folderName == $2",
                    project, scope.key, EmailFolders.TRASH,
                ).find(),
            )
        }
        badges.markFolderRead(project, EmailFolders.TRASH)
        return ApiResult.Success(Unit)
    }

    /**
     * Marks a message read.
     *
     * **Badges, not a flag.** For every folder but Sent, Trash and Junk there is no read
     * flag on the wire and no endpoint to set one — an email is unread because a badge row
     * names it, so reading it means retiring that row. The stored flag is written too, for
     * the three folders where it is what counts.
     */
    suspend fun markRead(email: Email) {
        val project = projectId
        badges.markRead(email.uid)

        val scope = mailbox.effectiveScope
        realm.write {
            query<EmailEntity>(
                "id == $0",
                EmailEntity.primaryKey(project, scope.key, email.folderName, email.id),
            ).first().find()?.read = true
        }
    }

    /** Applied when another device reads something — the socket sends only the uid. */
    suspend fun markReadByUid(uid: Int) {
        val project = projectId
        val scope = mailbox.effectiveScope

        val row = realm.query<EmailEntity>(
            "projectId == $0 AND mailboxScope == $1 AND uid == $2",
            project, scope.key, uid,
        ).first().find() ?: return

        badges.markRead(uid)
        realm.write {
            findLatest(row)?.read = true
        }
    }

    // ── Folders ──────────────────────────────────────────────────────────────

    suspend fun createFolder(name: String): ApiResult<EmailFolder> =
        when (val result = api.createFolder(name)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                // The server may qualify the name — asking for `Vendors` can return
                // `INBOX.Vendors` — and everything afterwards has to use what it returned.
                syncFolders()
                ApiResult.Success(result.data.toDomainFolder())
            }
        }

    suspend fun renameFolder(from: String, to: String): ApiResult<EmailFolder> =
        when (val result = api.renameFolder(from, to)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                syncFolders()
                ApiResult.Success(result.data.toDomainFolder())
            }
        }

    suspend fun deleteFolder(name: String): ApiResult<Unit> {
        val result = api.deleteFolder(name)
        if (result is ApiResult.Failure) return result

        val scope = mailbox.effectiveScope
        val project = projectId

        realm.write {
            delete(
                query<EmailEntity>(
                    "projectId == $0 AND mailboxScope == $1 AND folderName == $2",
                    project, scope.key, name,
                ).find(),
            )
            query<EmailFolderEntity>(
                "id == $0",
                EmailFolderEntity.primaryKey(project, scope.key, name),
            ).first().find()?.let { delete(it) }
        }
        return ApiResult.Success(Unit)
    }

    // ── Drafts (API-only) ────────────────────────────────────────────────────

    /**
     * Every draft: the server's, plus any this device wrote that the server has not
     * confirmed.
     *
     * Merged rather than replaced, which matters more than it sounds. `email-draft` pages
     * by a `before` timestamp, so a draft the app has *just* saved can fall outside the
     * page the very next refresh asks for and come back missing — reopening a draft and
     * closing it made the row disappear, with the draft still perfectly intact on the
     * server. The local mirror is what the list actually shows, so a draft cannot vanish
     * because of a cursor.
     *
     * Offline the mirror is all there is; the request would be certain to fail.
     */
    suspend fun drafts(): ApiResult<List<Email>> {
        if (!network.isOnline.value) return ApiResult.Success(localDrafts())

        // Noted before the request: a draft saved locally while it was in flight is newer
        // than the answer and must survive it, however the server chose to paginate.
        val fetchStartedAt = System.currentTimeMillis()

        val server = when (val result = api.drafts()) {
            is ApiResult.Failure -> {
                // Show what is held rather than an empty folder, and report the failure
                // only when there is nothing to show instead.
                val cached = localDrafts()
                return if (cached.isEmpty()) result else ApiResult.Success(cached)
            }

            is ApiResult.Success -> result.data.drafts.map { it.toDomain() }
        }

        mirrorServerDrafts(server, fetchStartedAt)
        return ApiResult.Success(localDrafts())
    }

    /**
     * Creates the draft, under [SaveDraftRequest.uniqueId] as its idempotency key.
     *
     * @return the server id, which every later save updates in place.
     */
    suspend fun saveDraft(request: SaveDraftRequest): ApiResult<String> =
        when (val result = api.saveDraft(request)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(result.data.id)
        }

    suspend fun updateDraft(draftId: String, request: SaveDraftRequest): ApiResult<Unit> =
        api.updateDraft(draftId, request)

    /**
     * Deletes drafts by server id, or by idempotency key for ones that never reached the
     * server — those exist only here, so they are dropped locally and nothing is sent.
     */
    suspend fun deleteDrafts(draftIds: List<String>): ApiResult<Unit> {
        if (draftIds.isEmpty()) return ApiResult.Success(Unit)

        val localOnly = localDrafts()
            .filter { it.pending && it.draftUniqueId in draftIds }
            .map { it.draftUniqueId }

        localOnly.forEach { deleteLocalDraft(it) }

        val serverIds = draftIds - localOnly.toSet()
        if (serverIds.isEmpty()) return ApiResult.Success(Unit)

        return when (val result = api.deleteDrafts(serverIds)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                serverIds.forEach { deleteLocalDraft(it) }
                ApiResult.Success(Unit)
            }
        }
    }

    // ── The local draft mirror ───────────────────────────────────────────────

    /**
     * Writes this compose session's draft to Realm, keyed by its idempotency key.
     *
     * Called **before** the first create, so the key outlives a lost response or a killed
     * process, and again after every accepted save so the copy matches the server.
     * [serverId] is null until a create has succeeded, which is what marks the row pending.
     */
    suspend fun saveLocalDraft(uniqueId: String, serverId: String?, request: SaveDraftRequest) {
        if (uniqueId.isBlank()) return

        val project = projectId
        val scope = mailbox.effectiveScope.key
        val key = EmailEntity.draftKey(project, scope, uniqueId)

        realm.write {
            // A mirror row inserted under the server id — the list synced between the
            // create being accepted and this row learning its id — would now be a duplicate.
            if (!serverId.isNullOrBlank()) {
                delete(
                    query<EmailEntity>(
                        "projectId == $0 AND mailboxScope == $1 AND folderName == $2 " +
                            "AND messageId == $3 AND id != $4",
                        project, scope, EmailFolders.DRAFTS, serverId, key,
                    ).find(),
                )
            }

            val row = query<EmailEntity>("id == $0", key).first().find()
                ?: copyToRealm(EmailEntity().apply { id = key })

            row.projectId = project
            row.mailboxScope = scope
            row.folderName = EmailFolders.DRAFTS
            row.messageId = serverId.orEmpty()
            row.draftUniqueId = uniqueId
            // Drafts are never threaded; keyed by their own key they stay out of the
            // cross-folder thread counts, which group every row by threadId.
            row.threadId = uniqueId
            row.pending = serverId.isNullOrBlank()
            row.read = true
            row.subject = request.subject
            row.fromAddress = mailbox.activeAddress.orEmpty()
            row.fromName = mailbox.activeName.orEmpty()
            row.to = request.to.map { it.emailAddress }.toRealmList()
            row.cc = request.cc.map { it.emailAddress }.toRealmList()
            row.bcc = request.bcc.map { it.emailAddress }.toRealmList()
            row.body = request.body
            row.text = request.body.replace(HTML_TAG, " ").trim()
            row.isHtml = true
            row.bodyLoaded = true
            row.attachments = (request.attachments + request.inlineAttachments)
                .map { it.toEntity() }
                .toRealmList()
            row.attachmentCount = request.attachments.size
            // Sorts alongside the server's drafts, which carry their own last-updated time.
            row.createdAt = System.currentTimeMillis()
        }
    }

    /** The draft stored under a server id or an idempotency key; null when there is none. */
    fun localDraft(idOrKey: String): Email? {
        if (idOrKey.isBlank()) return null

        return realm.query<EmailEntity>(
            "projectId == $0 AND mailboxScope == $1 AND folderName == $2 " +
                "AND (messageId == $3 OR draftUniqueId == $3)",
            projectId, mailbox.effectiveScope.key, EmailFolders.DRAFTS, idOrKey,
        ).first().find()?.toDomain()
    }

    suspend fun deleteLocalDraft(idOrKey: String) {
        if (idOrKey.isBlank()) return

        realm.write {
            delete(
                query<EmailEntity>(
                    "folderName == $0 AND (messageId == $1 OR draftUniqueId == $1)",
                    EmailFolders.DRAFTS, idOrKey,
                ).find(),
            )
        }
    }

    /** Every stored draft for the open project and mailbox, newest first. */
    private fun localDrafts(): List<Email> =
        realm.query<EmailEntity>(
            "projectId == $0 AND mailboxScope == $1 AND folderName == $2",
            projectId, mailbox.effectiveScope.key, EmailFolders.DRAFTS,
        ).sort("createdAt", Sort.DESCENDING).find().map { it.toDomain() }

    /**
     * Makes the mirror agree with the server list.
     *
     * Drafts the server no longer lists are dropped — sent, or deleted from another device.
     * Rows that never earned a server id are **not** the server's to remove: they stay
     * until their create succeeds or the user deletes them.
     */
    private suspend fun mirrorServerDrafts(serverDrafts: List<Email>, fetchStartedAt: Long) {
        val project = projectId
        val scope = mailbox.effectiveScope.key
        val serverIds = serverDrafts.map { it.id }.toSet()

        realm.write {
            query<EmailEntity>(
                "projectId == $0 AND mailboxScope == $1 AND folderName == $2 AND pending == false",
                project, scope, EmailFolders.DRAFTS,
            ).find()
                // `createdAt` on a locally saved draft is when this device wrote it. A row
                // written after the request went out cannot have been in the answer, so its
                // absence says nothing — deleting it would throw away the newer copy.
                .filter { it.messageId !in serverIds && it.createdAt < fetchStartedAt }
                .forEach { delete(it) }

            serverDrafts.forEach { draft ->
                // Under its own key when the server reports one, so a row this device
                // created keeps its identity; under the server id for drafts made
                // elsewhere, or before keys existed.
                val existing = query<EmailEntity>(
                    "projectId == $0 AND mailboxScope == $1 AND folderName == $2 AND messageId == $3",
                    project, scope, EmailFolders.DRAFTS, draft.id,
                ).first().find()

                val row = existing ?: copyToRealm(
                    EmailEntity().apply {
                        id = EmailEntity.draftKey(
                            project,
                            scope,
                            draft.draftUniqueId.ifBlank { draft.id },
                        )
                    },
                )

                row.projectId = project
                row.mailboxScope = scope
                row.folderName = EmailFolders.DRAFTS
                row.messageId = draft.id
                if (draft.draftUniqueId.isNotBlank()) row.draftUniqueId = draft.draftUniqueId
                row.threadId = draft.draftUniqueId.ifBlank { draft.id }
                row.pending = false
                row.read = true
                row.subject = draft.subject
                row.fromAddress = draft.from.address
                row.fromName = draft.from.name
                row.to = draft.to.map { it.encoded }.toRealmList()
                row.cc = draft.cc.map { it.encoded }.toRealmList()
                row.bcc = draft.bcc.map { it.encoded }.toRealmList()
                row.body = draft.body
                row.text = draft.text
                row.isHtml = draft.isHtml
                row.bodyLoaded = true
                row.attachments = draft.attachments.map { it.toEntity() }.toRealmList()
                row.attachmentCount = draft.attachments.count { !it.isInline }
                row.createdAt = draft.createdAt
            }
        }
    }

    /** Clears the mailbox's cache — on sign-out, or when the project closes. */
    suspend fun clearProject(project: String) {
        realm.write {
            delete(query<EmailEntity>("projectId == $0", project).find())
            delete(query<EmailFolderEntity>("projectId == $0", project).find())
        }
    }

    private fun FolderDto.toDomainFolder(): EmailFolder = EmailFolder(
        folderId = folderId,
        folderName = folderName,
        systemDefined = systemDefined || folderName in EmailFolders.SYSTEM,
    )

    private companion object {
        const val TAG = "EmailRepository"

        /** v2's batch size. The service has no documented cap; this is what it is tuned for. */
        const val INDEX_BATCH = 50


        val SYSTEM_ORDER = listOf(
            EmailFolders.INBOX,
            EmailFolders.SENT,
            EmailFolders.DRAFTS,
            EmailFolders.TRASH,
            EmailFolders.SPAM,
            EmailFolders.JUNK,
        )
    }
}

/**
 * A message field local search can match on.
 *
 * The body is deliberately absent: scanning every cached body on a phone is slow enough to
 * read as broken, and v2 does not search it either.
 */
enum class SearchableField(val property: String, val isCollection: Boolean = false) {
    SUBJECT("subject"),
    /** Matches the address; the display name is searched through the sender name too. */
    FROM("fromAddress"),
    TO("to", isCollection = true),
    CC("cc", isCollection = true),
    BCC("bcc", isCollection = true),
}

