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

    /** True only when the server accepted the batch — anything else keeps the rows queued. */
    suspend fun send(events: List<LogEnvelope>): Boolean = runCatching {
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

        if (!response.status.isSuccess()) {
            ZillitLog.w(TAG, "log post → HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
        }
        response.status.isSuccess()
    }.getOrElse {
        // A transport failure is exactly what the queue is for; nothing to report here.
        false
    }

    private companion object {
        const val TAG = "ErrorLogTransport"
    }
}
