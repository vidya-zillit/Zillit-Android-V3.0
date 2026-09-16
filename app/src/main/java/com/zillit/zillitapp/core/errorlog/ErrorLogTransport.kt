package com.zillit.zillitapp.core.errorlog

import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiHeaders
import com.zillit.zillitapp.core.network.DeviceInfoProvider
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ModuleDataFactory
import com.zillit.zillitapp.core.network.RequestSigner
import com.zillit.zillitapp.core.network.ZillitCrypto
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a batch of envelopes to `POST /location/log`.
 *
 * Its own client path rather than [ZillitApi], for the same reason [SessionApi] has one:
 * `ZillitApi`'s failure funnel is what *calls* the reporter, so a reporter that posted
 * through it would report its own failures forever. This path fails silently into the
 * queue instead.
 *
 * The header is `WITH_PROJECT_USER_ID` — the v2 Android bug this spec calls out hardest is
 * a log header carrying only the device id, which left every one of its 85k events
 * unfindable by user or project.
 */
@Singleton
class ErrorLogTransport @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
    private val moduleDataFactory: ModuleDataFactory,
    private val crypto: ZillitCrypto,
    private val signer: RequestSigner,
    private val deviceInfo: DeviceInfoProvider,
) {

    /**
     * Consecutive failures, and when to stop refusing on their account.
     *
     * The log endpoint can be down while the rest of the app is perfectly healthy — it
     * answered `502 Bad Gateway` six times in the first two seconds of one launch, once per
     * event, because every immediate-send event opens its own request. Each of those is a
     * round trip competing with the calls that actually load the screen, and none of them
     * could ever have succeeded. So after a few in a row the transport stops trying for a
     * while. Nothing is lost: a refusal returns false exactly like a failure, and the queue
     * keeps the rows for the next attempt.
     */
    private val consecutiveFailures = AtomicInteger(0)
    private val silentUntil = AtomicLong(0L)

    /** True only when the server accepted the batch — anything else keeps the rows queued. */
    suspend fun send(events: List<LogEnvelope>): Boolean = runCatching {
        if (System.currentTimeMillis() < silentUntil.get()) return false

        val moduleData = crypto.encrypt(moduleDataFactory.build(ModuleData.WITH_PROJECT_USER_ID))
        if (moduleData.isEmpty()) return false

        val body = json.encodeToString(LogBatchRequest.serializer(), LogBatchRequest(events))

        val response = client.post(ApiEndpoints.ErrorLog.POST) {
            header(ApiHeaders.MODULE_DATA, moduleData)
            header(ApiHeaders.DEVICE_INFO, deviceInfo.asHeader())
            header(ApiHeaders.BODY_HASH, signer.bodyHash(body, moduleData))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        if (response.status.isSuccess()) {
            consecutiveFailures.set(0)
            return true
        }

        // A gateway error (the service behind the host is down) comes as nginx's HTML page,
        // which says nothing the status code does not — one line, not a page of markup.
        val text = response.bodyAsText()
        val detail = if (text.trimStart().startsWith("<")) "(gateway error page)" else text.take(200)
        ZillitLog.w(TAG, "log post → HTTP ${response.status.value} $detail")
        noteFailure()
        false
    }.getOrElse {
        // A transport failure is exactly what the queue is for; nothing to report here.
        noteFailure()
        false
    }

    /** Opens the cooldown once the endpoint has failed [FAILURES_BEFORE_PAUSE] times running. */
    private fun noteFailure() {
        if (consecutiveFailures.incrementAndGet() < FAILURES_BEFORE_PAUSE) return

        consecutiveFailures.set(0)
        silentUntil.set(System.currentTimeMillis() + PAUSE_MS)
        ZillitLog.w(TAG, "log endpoint unhealthy — pausing log posts for ${PAUSE_MS / 1_000}s")
    }

    private companion object {
        const val TAG = "ErrorLogTransport"

        /** Enough to rule out one bad request, few enough to stop a burst. */
        const val FAILURES_BEFORE_PAUSE = 3
        const val PAUSE_MS = 60_000L
    }
}
