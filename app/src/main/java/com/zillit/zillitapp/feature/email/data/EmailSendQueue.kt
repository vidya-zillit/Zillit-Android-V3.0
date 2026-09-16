package com.zillit.zillitapp.feature.email.data

import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.PendingEmailEntity
import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.NetworkMonitor
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.email.domain.MailboxScope
import io.realm.kotlin.ext.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A mail waiting to go out, as the list needs to draw it. */
data class PendingEmail(
    val id: String,
    val subject: String,
    val recipients: String,
    val createdAt: Long,
    val failed: Boolean,
)

/**
 * The outbox.
 *
 * Sending writes here first and the composer closes immediately; delivery happens
 * afterwards and survives the screen, the process and the network. That is what v2 does too
 * — its composer never calls the send endpoint — and it is the difference between "no
 * signal" costing a retry and costing the whole message.
 *
 * ### Ordering
 * Delivery is serialised by [flushLock]. Two flushes racing — one from a reconnect, one from
 * the compose screen — would otherwise both pick up the same row and send it twice.
 */
@Singleton
class EmailSendQueue @Inject constructor(
    private val realmProvider: RealmProvider,
    private val api: EmailApi,
    private val repository: EmailRepository,
    private val session: SessionStore,
    private val network: NetworkMonitor,
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val realm get() = realmProvider.realm
    private val flushLock = Mutex()

    private val projectId: String get() = session.activeProject.value?.projectId.orEmpty()

    init {
        // A reconnect is the moment every queued mail becomes deliverable, so it is what
        // drives delivery rather than a timer that is usually wrong in both directions.
        scope.launch {
            network.isOnline.filter { it }.collect { flush() }
        }
    }

    /** What is still waiting, for the optimistic rows in Sent. */
    fun observePending(projectId: String, scope: MailboxScope): Flow<List<PendingEmail>> =
        realm.query<PendingEmailEntity>(
            "projectId == $0 AND mailboxScope == $1",
            projectId,
            scope.name,
        )
            .asFlow()
            .map { change ->
                change.list
                    .sortedByDescending { it.createdAt }
                    .map {
                        PendingEmail(
                            id = it.id,
                            subject = it.subject,
                            recipients = it.recipients,
                            createdAt = it.createdAt,
                            failed = it.lastError != null,
                        )
                    }
            }

    /**
     * Accepts a mail for delivery and starts trying.
     *
     * Returns as soon as the row is written, so the composer closes on the tap rather than
     * on the round trip.
     */
    suspend fun enqueue(
        request: SendEmailRequest,
        scope: MailboxScope,
        subject: String,
        recipients: String,
    ) {
        val project = projectId

        realm.write {
            copyToRealm(
                PendingEmailEntity().apply {
                    id = UUID.randomUUID().toString()
                    projectId = project
                    mailboxScope = scope.name
                    payloadJson = json.encodeToString(SendEmailRequest.serializer(), request)
                    this.subject = subject
                    this.recipients = recipients
                    createdAt = System.currentTimeMillis()
                },
            )
        }

        flush()
    }

    /**
     * Delivers everything queued, oldest first.
     *
     * Offline is not a failure: the rows stay exactly as they are and the reconnect above
     * starts this again. Only a real refusal from the server is recorded, so the row can be
     * shown as failed rather than silently retried forever.
     */
    fun flush() {
        scope.launch { deliver() }
    }

    private suspend fun deliver() = flushLock.withLock {
        if (!network.isOnline.value) return@withLock

        val queued = realm.query<PendingEmailEntity>("projectId == $0", projectId)
            .find()
            .sortedBy { it.createdAt }
            .map {
                Queued(
                    id = it.id,
                    scope = MailboxScope.entries.firstOrNull { s -> s.name == it.mailboxScope }
                        ?: MailboxScope.PERSONAL,
                    payload = it.payloadJson,
                )
            }

        queued.forEach { row ->
            val request = runCatching {
                json.decodeFromString(SendEmailRequest.serializer(), row.payload)
            }.getOrNull()

            // A payload that cannot be read will never send. Keeping it would mean retrying
            // it on every reconnect for the life of the install.
            if (request == null) {
                remove(row.id)
                return@forEach
            }

            when (val result = api.send(request, useAccountsMailbox = row.scope == MailboxScope.SHARED)) {
                is ApiResult.Success -> {
                    remove(row.id)
                    // Sent now holds a message the cache does not, and the user is usually
                    // looking straight at it.
                    repository.syncFolder(com.zillit.zillitapp.feature.email.domain.EmailFolders.SENT)
                }

                is ApiResult.Failure -> {
                    if (network.isOnline.value) record(row.id, result.error.message) else return@withLock
                }
            }
        }
    }

    /** Drops a queued mail — what cancelling an unsent message means. */
    suspend fun cancel(id: String) {
        remove(id)
    }

    private suspend fun remove(id: String) {
        realm.write {
            query<PendingEmailEntity>("id == $0", id).first().find()?.let { delete(it) }
        }
    }

    private suspend fun record(id: String, message: String) {
        ZillitLog.w(TAG, "queued mail $id refused: $message")
        realm.write {
            query<PendingEmailEntity>("id == $0", id).first().find()?.let {
                it.attempts += 1
                it.lastError = message
            }
        }
    }

    private data class Queued(val id: String, val scope: MailboxScope, val payload: String)

    private companion object {
        const val TAG = "EmailSendQueue"
    }
}
