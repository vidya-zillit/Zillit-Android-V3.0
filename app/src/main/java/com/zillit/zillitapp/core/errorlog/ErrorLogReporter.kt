package com.zillit.zillitapp.core.errorlog

import android.os.Build
import com.zillit.zillitapp.BuildConfig
import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.PendingLogEntity
import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.NetworkMonitor
import com.zillit.zillitapp.core.session.SessionStore
import io.realm.kotlin.ext.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-side failure reporting — the schema-v1 pipeline.
 *
 * The delivery rule, exactly as asked: a failure is sent **immediately**; if the log call
 * itself fails, the event is stored locally; once **ten** are waiting they are shipped as
 * one batch, and rows are deleted only after the server accepts them. So a dead network
 * loses nothing, and a healthy one reports in real time.
 *
 * Everything here answers a debugging question the old data could not: v2's Android events
 * were unfindable (no user or project on any of 85k rows — its log header carried only the
 * device id), carried no status, no method and no error field, and hid the device info as
 * JSON inside a string. The envelope in [LogEventData] is the fix, and the header this
 * ships under `WITH_PROJECT_USER_ID` so every row is stamped with who and where.
 *
 * ### What gets logged
 * Failures, not noise (the spec's rule 8): failed API calls, socket drops and rejected
 * handshakes, crashes, and the lifecycle transitions that give them a timeline. Never
 * successful requests.
 *
 * ### What must never happen
 * The reporter reporting itself. Its own endpoint is excluded at the [reportApiFailure]
 * gate, so a dead log service queues quietly instead of recursing.
 */
@Singleton
class ErrorLogReporter @Inject constructor(
    private val transport: ErrorLogTransport,
    private val realmProvider: RealmProvider,
    private val session: SessionStore,
    private val networkMonitor: NetworkMonitor,
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val realm get() = realmProvider.realm

    /** Serialises flushes so two triggers cannot ship the same rows twice. */
    private val flushMutex = Mutex()

    // ── The four entry points ───────────────────────────────────────────────

    /** A failed API call. [request]/[response] are truncated to the spec's 2 KB here. */
    fun reportApiFailure(
        url: String,
        method: String,
        status: Int?,
        errorMessage: String,
        request: String? = null,
        response: String? = null,
    ) {
        // The reporter must never report itself — a dead log service would recurse.
        if (url.startsWith(ApiEndpoints.ErrorLog.POST)) return

        submit(
            build(
                category = LogCategory.API,
                // "POST /v2/home/chat" — the spec's name shape: method + path, no host.
                name = "$method ${url.substringAfter("api", missingDelimiterValue = url)}",
                error = LogError(message = errorMessage.take(MAX_ERROR_LENGTH), code = status?.toString()),
                api = LogApi(
                    url = url,
                    method = method,
                    status = status,
                    request = request?.take(MAX_BODY_LENGTH),
                    response = response?.take(MAX_BODY_LENGTH),
                ),
            ),
        )
    }

    /** A socket failure: rejected handshake, drop, missing ack. */
    fun reportSocketEvent(name: String, errorMessage: String? = null, context: JsonObject? = null) {
        submit(
            build(
                category = LogCategory.SOCKET,
                name = name,
                error = errorMessage?.let { LogError(message = it.take(MAX_ERROR_LENGTH)) },
                context = context,
            ),
        )
    }

    /** Foreground, background, login, token refresh — the timeline between failures. */
    fun reportLifecycle(name: String, context: JsonObject? = null) {
        submit(build(category = LogCategory.LIFECYCLE, name = name, context = context))
    }

    /**
     * An uncaught exception.
     *
     * Queued synchronously — the process is about to die, so there is no "send now" and no
     * coroutine that will live long enough. It ships with the next batch after relaunch.
     */
    fun reportCrash(throwable: Throwable) {
        val event = build(
            category = LogCategory.CRASH,
            name = "unhandled_exception",
            error = LogError(
                message = (throwable.message ?: throwable::class.java.name).take(MAX_ERROR_LENGTH),
                code = throwable::class.java.simpleName,
            ),
            context = kotlinx.serialization.json.buildJsonObject {
                put(
                    "stack",
                    kotlinx.serialization.json.JsonPrimitive(
                        throwable.stackTraceToString().take(MAX_BODY_LENGTH),
                    ),
                )
            },
        )

        runCatching {
            realm.writeBlocking {
                copyToRealm(
                    PendingLogEntity().apply {
                        uniqueId = event.uniqueId
                        envelopeJson = json.encodeToString(LogEnvelope.serializer(), event)
                        queuedAt = System.currentTimeMillis()
                    },
                )
            }
        }
    }

    // ── Delivery ────────────────────────────────────────────────────────────

    /** Immediate send; on failure the event joins the queue, which ships in tens. */
    private fun submit(event: LogEnvelope) {
        scope.launch {
            val sent = networkMonitor.isOnline.value && transport.send(listOf(event))
            if (!sent) enqueue(event)
            flushIfReady()
        }
    }

    private suspend fun enqueue(event: LogEnvelope) {
        runCatching {
            realm.write {
                copyToRealm(
                    PendingLogEntity().apply {
                        uniqueId = event.uniqueId
                        envelopeJson = json.encodeToString(LogEnvelope.serializer(), event)
                        queuedAt = System.currentTimeMillis()
                    },
                )
            }
        }.onFailure { ZillitLog.w(TAG, "could not queue log event: ${it.message}") }
    }

    /**
     * Ships the queue once it holds [BATCH_THRESHOLD] events (or on demand from
     * [flushPending]). Rows are deleted **only after** the server accepts the batch —
     * a failed upload leaves them exactly where they were.
     */
    private suspend fun flushIfReady(force: Boolean = false) = flushMutex.withLock {
        val pending = realm.query<PendingLogEntity>().sort("queuedAt").find()
        if (pending.isEmpty()) return@withLock
        if (!force && pending.size < BATCH_THRESHOLD) return@withLock
        if (!networkMonitor.isOnline.value) return@withLock

        // The spec caps a call at ~50 events; ship in slices so a long-offline backlog
        // cannot produce one oversized request.
        pending.chunked(MAX_BATCH_SIZE).forEach { slice ->
            val envelopes = slice.mapNotNull { row ->
                runCatching {
                    json.decodeFromString(LogEnvelope.serializer(), row.envelopeJson)
                }.getOrNull()
            }
            if (envelopes.isEmpty() || !transport.send(envelopes)) return@withLock

            val ids = slice.map { it.uniqueId }
            realm.write {
                ids.forEach { id ->
                    query<PendingLogEntity>("uniqueId == $0", id).first().find()
                        ?.let { delete(it) }
                }
            }
            ZillitLog.d(TAG, "uploaded ${ids.size} queued log event(s)")
        }
    }

    /** Called at app start and on foreground, so crash logs and backlogs ship. */
    fun flushPending() {
        scope.launch { flushIfReady(force = true) }
    }

    // ── The envelope ────────────────────────────────────────────────────────

    private fun build(
        category: LogCategory,
        name: String,
        error: LogError? = null,
        api: LogApi? = null,
        context: JsonObject? = null,
    ): LogEnvelope = LogEnvelope(
        // Fresh per event, never derived from the payload: a repeated hash is how 10k
        // events were silently overwritten server-side.
        uniqueId = UUID.randomUUID().toString(),
        data = LogEventData(
            appVersion = BuildConfig.VERSION_NAME,
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            deviceModel = deviceModel(),
            installId = session.deviceId,
            network = networkKind(),
            eventTime = System.currentTimeMillis(),
            category = category.wire,
            name = name,
            error = error,
            api = api,
            context = context,
        ),
    )

    private fun deviceModel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
    }

    /** The spec's exact lowercase set. */
    private fun networkKind(): String = when (networkMonitor.transportKind()) {
        NetworkMonitor.Transport.WIFI -> "wifi"
        NetworkMonitor.Transport.CELLULAR -> "cellular"
        NetworkMonitor.Transport.NONE -> "none"
        NetworkMonitor.Transport.OTHER -> "unknown"
    }

    private companion object {
        const val TAG = "ErrorLogReporter"

        /** Queue depth that triggers an automatic batch upload. */
        const val BATCH_THRESHOLD = 10

        /** The spec's per-call ceiling. */
        const val MAX_BATCH_SIZE = 50

        /** The spec's request/response truncation. */
        const val MAX_BODY_LENGTH = 2_048
        const val MAX_ERROR_LENGTH = 512
    }
}
