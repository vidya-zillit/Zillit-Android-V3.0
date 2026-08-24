package com.zillit.zillitapp.core.storage

import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.logging.ZillitLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What a bubble needs to know about its attachment. */
sealed interface DownloadState {
    /** Already on the device — opening is instant. */
    data class Ready(val file: File) : DownloadState

    data object NotDownloaded : DownloadState

    data class InProgress(val fraction: Float) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

/**
 * Fetches attachments once and serves them from [MediaCache] afterwards.
 *
 * Three properties the chat depends on:
 *
 *  - **Cache first.** An attachment already downloaded opens with no request at all, and
 *    works offline. Re-downloading a document someone reopens ten times a day is the most
 *    wasteful thing a chat client can do.
 *  - **One transfer per file.** Two bubbles pointing at the same object — a forwarded call
 *    sheet, say — share a single download instead of racing.
 *  - **Progress per file**, so the bubble can show a ring rather than freezing on tap.
 */
@Singleton
class AttachmentDownloader @Inject constructor(
    private val s3: S3Client,
    private val cache: MediaCache,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    /** Guards the in-flight set so two callers cannot both start the same download. */
    private val lock = Mutex()
    private val inFlight = mutableSetOf<String>()

    fun stateFor(remoteKey: String, fileName: String): DownloadState =
        _states.value[remoteKey]
            ?: cache.cached(remoteKey, fileName)?.let { DownloadState.Ready(it) }
            ?: DownloadState.NotDownloaded

    /**
     * Ensures the file is local.
     *
     * Returns immediately when it already is — that is the common path, and it must not
     * cost a coroutine launch or a state update.
     */
    fun download(remoteKey: String, fileName: String, module: String) {
        cache.cached(remoteKey, fileName)?.let { file ->
            update(remoteKey, DownloadState.Ready(file))
            return
        }

        scope.launch {
            // Checked under the lock so a double tap does not start two transfers.
            val shouldStart = lock.withLock {
                if (remoteKey in inFlight) false else { inFlight += remoteKey; true }
            }
            if (!shouldStart) return@launch

            val target = cache.fileFor(remoteKey, fileName)
            update(remoteKey, DownloadState.InProgress(0f))

            var failure: String? = null

            s3.download(
                DownloadRequest(
                    remoteKey = remoteKey,
                    destinationPath = target.absolutePath,
                    fileName = fileName,
                    module = module,
                ),
            )
                .transformWhile { state ->
                    emit(state)
                    state !is TransferState.Complete &&
                        state !is TransferState.Failed &&
                        state !is TransferState.Cancelled
                }
                .collect { state ->
                    when (state) {
                        is TransferState.InProgress ->
                            update(remoteKey, DownloadState.InProgress(state.fraction))

                        is TransferState.Complete -> update(remoteKey, DownloadState.Ready(target))
                        is TransferState.Failed -> failure = state.reason
                        else -> Unit
                    }
                }

            failure?.let {
                ZillitLog.w(TAG, "Download failed $remoteKey: $it")
                // Cleared rather than recorded as Failed, so the next composition retries.
                // A thumbnail that failed only because credentials were still loading must
                // not stay broken for the rest of the session.
                _states.value = _states.value - remoteKey
            }

            lock.withLock { inFlight -= remoteKey }

            // Trimmed after a download rather than before: the file just fetched is the
            // one most likely to be opened, so it must never be the one evicted.
            cache.trimIfNeeded()
        }
    }

    /**
     * The local file, downloading it first if it is not cached.
     *
     * The suspending twin of [download], for callers that need the file itself rather than
     * a state to render — sharing several attachments at once has to wait for all of them
     * before it can build one chooser Intent.
     *
     * @return null if the transfer failed; the caller shares what it did get rather than
     *   failing the whole batch over one file.
     */
    suspend fun awaitFile(remoteKey: String, fileName: String, module: String): File? {
        cache.cached(remoteKey, fileName)?.let { file ->
            update(remoteKey, DownloadState.Ready(file))
            return file
        }

        val target = cache.fileFor(remoteKey, fileName)
        update(remoteKey, DownloadState.InProgress(0f))

        var failure: String? = null

        s3.download(
            DownloadRequest(
                remoteKey = remoteKey,
                destinationPath = target.absolutePath,
                fileName = fileName,
                module = module,
            ),
        )
            .transformWhile { state ->
                emit(state)
                state !is TransferState.Complete &&
                    state !is TransferState.Failed &&
                    state !is TransferState.Cancelled
            }
            .collect { state ->
                when (state) {
                    is TransferState.InProgress ->
                        update(remoteKey, DownloadState.InProgress(state.fraction))

                    is TransferState.Complete -> update(remoteKey, DownloadState.Ready(target))
                    is TransferState.Failed -> failure = state.reason
                    else -> Unit
                }
            }

        cache.trimIfNeeded()

        return if (failure == null && target.exists() && target.length() > 0) {
            target
        } else {
            ZillitLog.w(TAG, "Download failed $remoteKey: $failure")
            _states.value = _states.value - remoteKey
            null
        }
    }

    private fun update(remoteKey: String, state: DownloadState) {
        _states.value = _states.value + (remoteKey to state)
    }

    private companion object {
        const val TAG = "AttachmentDownload"
    }
}
