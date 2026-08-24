package com.zillit.zillitapp.core.logging

import android.util.Log
import com.zillit.zillitapp.BuildConfig
import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.ApiLogEntity
import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.network.ApiError
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.Sort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records every API call so failures can be inspected after they happen.
 *
 * Three properties that matter:
 *
 *  - **Never blocks a request.** Writes are dispatched to the application scope, so a slow
 *    Realm write cannot add latency to the call it is describing.
 *  - **Bounded.** Old rows are trimmed past [MAX_ENTRIES]; an unbounded log on a busy
 *    account grows without limit and eventually becomes the app's largest table.
 *  - **Credential-free.** The encrypted `moduledata` and `bodyhash` headers are never
 *    persisted — only the variant name. See [ApiLogEntity].
 *
 * Response bodies are truncated: a document-list response can be megabytes, and storing it
 * whole turns the log into a second copy of the app's data.
 */
@Singleton
class ApiLogger @Inject constructor(
    private val realmProvider: RealmProvider,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val realm get() = realmProvider.realm

    /** Serialises writes so the trim-and-insert pair cannot interleave. */
    private val writeMutex = Mutex()

    /**
     * Records a completed call. Fire-and-forget.
     *
     * @param statusCode 0 when the request never reached the server.
     */
    fun log(
        method: String,
        url: String,
        moduleDataVariant: String,
        requestBody: String?,
        responseBody: String?,
        statusCode: Int,
        durationMs: Long,
        error: ApiError? = null,
        networkType: String? = null,
        /**
         * The decrypted `moduledata`, for the console only.
         *
         * Never persisted — [ApiLogEntity] keeps the variant name and nothing else. It is
         * printed because a 403 from this backend is almost always a header the server
         * read differently than we wrote it, and that is unanswerable without seeing the
         * plaintext next to the response.
         */
        moduleDataPlain: String? = null,
    ) {
        // Console first — immediate, and the only thing available if Realm is down.
        if (BuildConfig.DEBUG) {
            printToConsole(
                method, url, moduleDataVariant, requestBody, responseBody,
                statusCode, durationMs, error, moduleDataPlain,
            )
        }

        scope.launch {
            runCatching {
                writeMutex.withLock {
                    realm.write {
                        copyToRealm(
                            ApiLogEntity().apply {
                                this.id = UUID.randomUUID().toString()
                                this.timestamp = System.currentTimeMillis()
                                this.method = method
                                this.url = url
                                this.moduleDataVariant = moduleDataVariant
                                this.requestBody = requestBody?.truncate()
                                this.responseBody = responseBody?.truncate()
                                this.statusCode = statusCode
                                this.durationMs = durationMs
                                this.errorType = error?.let { it::class.java.simpleName }
                                this.errorMessage = error?.message
                                this.networkType = networkType
                                this.isSuccess = error == null && statusCode in 200..299
                            },
                            UpdatePolicy.ALL,
                        )

                        // Trim inside the same transaction as the insert, so the table
                        // cannot momentarily exceed the cap or lose rows to a race.
                        val all = query<ApiLogEntity>()
                            .sort("timestamp", Sort.DESCENDING)
                            .find()
                        if (all.size > MAX_ENTRIES) {
                            all.drop(MAX_ENTRIES).forEach { delete(it) }
                        }
                    }
                }
            }.onFailure {
                // Logging must never take the app down with it.
                Log.w(TAG, "Failed to persist API log: ${it.message}")
            }
        }
    }

    /**
     * One boxed entry in logcat.
     *
     * Boxed rather than one line per call because a request, its headers and its response
     * are read together when something fails, and interleaved logs from other threads make
     * unboxed lines impossible to pair up.
     */
    private fun printToConsole(
        method: String,
        url: String,
        moduleDataVariant: String,
        requestBody: String?,
        responseBody: String?,
        statusCode: Int,
        durationMs: Long,
        error: ApiError?,
        moduleDataPlain: String?,
    ) {
        val outcome = if (error != null) "FAILED ${error::class.java.simpleName}" else "HTTP $statusCode"

        Log.d(TAG, "┌── $method $url")
        Log.d(TAG, "│   variant=$moduleDataVariant")

        moduleDataPlain?.let {
            Log.d(TAG, "│   MODULEDATA:")
            it.logChunked("│     ")
        }

        if (requestBody != null) {
            Log.d(TAG, "│   REQUEST:")
            requestBody.logChunked("│     ")
        } else {
            Log.d(TAG, "│   REQUEST: (no body)")
        }

        // Failures print at WARN so they survive a filtered logcat.
        val level = if (error == null && statusCode in 200..299) Log.DEBUG else Log.WARN
        Log.println(level, TAG, "│   RESPONSE $outcome in ${durationMs}ms")

        if (responseBody.isNullOrBlank()) {
            Log.println(level, TAG, "│     (empty)")
        } else {
            responseBody.logChunked("│     ", level)
        }

        error?.message?.takeIf { it.isNotBlank() }?.let { Log.w(TAG, "│   ERROR: $it") }

        Log.println(level, TAG, "└──")
    }

    /**
     * Splits long payloads across lines.
     *
     * logcat drops anything past roughly 4 KB in a single entry, which silently truncated
     * exactly the large responses worth reading.
     */
    private fun String.logChunked(prefix: String, priority: Int = Log.DEBUG) {
        var index = 0
        while (index < length) {
            val end = minOf(index + CHUNK_CHARS, length)
            Log.println(priority, TAG, prefix + substring(index, end))
            index = end
        }
    }

    /** Newest first. For a debug log viewer. */
    fun observeLogs(limit: Int = MAX_ENTRIES): Flow<List<ApiLogEntity>> =
        realm.query<ApiLogEntity>()
            .sort("timestamp", Sort.DESCENDING)
            .asFlow()
            .map { change -> change.list.take(limit) }

    /** Failures only — the usual starting point when something is wrong. */
    fun observeFailures(limit: Int = MAX_ENTRIES): Flow<List<ApiLogEntity>> =
        realm.query<ApiLogEntity>("isSuccess == false")
            .sort("timestamp", Sort.DESCENDING)
            .asFlow()
            .map { change -> change.list.take(limit) }

    /** Plain-text dump, for attaching to a bug report. */
    suspend fun exportAsText(limit: Int = 200): String {
        val logs = realm.query<ApiLogEntity>()
            .sort("timestamp", Sort.DESCENDING)
            .find()
            .take(limit)

        return buildString {
            appendLine("Zillit API log — ${logs.size} entries")
            appendLine("Build: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
            appendLine()
            logs.forEach { entry ->
                appendLine("[${entry.timestamp}] ${entry.method} ${entry.url}")
                appendLine("  variant=${entry.moduleDataVariant} status=${entry.statusCode} ${entry.durationMs}ms")
                entry.errorType?.let { appendLine("  error=$it ${entry.errorMessage.orEmpty()}") }
                entry.requestBody?.let { appendLine("  request=$it") }
                entry.responseBody?.let { appendLine("  response=$it") }
                appendLine()
            }
        }
    }

    suspend fun clear() {
        realm.write { delete(query<ApiLogEntity>().find()) }
    }

    private fun String.truncate(): String =
        if (length <= MAX_BODY_CHARS) this else take(MAX_BODY_CHARS) + "…[truncated ${length - MAX_BODY_CHARS}]"

    private companion object {
        const val TAG = "ZillitApi"

        /** Roughly a day of heavy use; enough to diagnose without unbounded growth. */
        const val MAX_ENTRIES = 500

        const val MAX_BODY_CHARS = 4_000

        /** Under logcat's per-entry limit, with room for the prefix. */
        const val CHUNK_CHARS = 3_000
    }
}
