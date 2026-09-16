package com.zillit.zillitapp.core.storage

import android.content.Context
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver
import com.amazonaws.mobileconnectors.s3.transferutility.TransferNetworkLossHandler
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState as AwsTransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.regions.Region
import com.amazonaws.services.s3.AmazonS3Client
import com.zillit.zillitapp.core.logging.ZillitLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S3 transfers, exposed as flows of [TransferState].
 *
 * A flow rather than v2's listener-into-a-singleton arrangement: the transfer then ends
 * when the collector goes away, cancellation is structured, and there is no global
 * `currentUploadingItem` that a second upload can overwrite mid-flight.
 *
 * The AWS client is rebuilt whenever the credentials change. v2 caches it in object fields
 * and only reinitialises when a field happens to be empty, so switching to a project in a
 * different region keeps uploading to the previous region's bucket until the app restarts.
 *
 * ### One region is not enough to read with
 * Uploads always go to the project's own region. Downloads do not: a file uploaded while
 * the project sat in another region keeps living there, and so does the avatar of anyone
 * who joined from a different one. The server says so on every media object it returns,
 * naming the `bucket` and `region` that file is actually in, and v2 reads those in
 * preference to the project default. Asking the project's bucket for a key that lives
 * elsewhere returns `NoSuchKey`, which looks exactly like a deleted file and is why one
 * user's photo was missing while everyone else's loaded. So a download names its own
 * bucket and region when it knows them, and a client per region is kept for the purpose.
 */
@Singleton
class S3Client @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialsStore: StorageCredentialsStore,
    private val mediaLocations: MediaLocations,
) {

    private var cachedFor: StorageCredentials? = null
    private var s3: AmazonS3Client? = null
    private var transferUtility: TransferUtility? = null

    /**
     * A client per foreign region, built on demand and kept.
     *
     * Small and bounded — a project's files are spread over a handful of regions at most —
     * and rebuilding one per download would mean an AWS client construction on every
     * avatar in a list.
     */
    private val byRegion = mutableMapOf<String, TransferUtility>()

    private val lock = Any()

    /**
     * @return null when the project has no usable credentials yet. Callers surface that
     *   as a non-retryable failure rather than queueing a transfer that cannot run.
     */
    private suspend fun utilityOrNull(): Pair<TransferUtility, StorageCredentials>? {
        // Waits rather than failing. A chat opening on a cold start asks for thumbnails
        // before the project bootstrap has fetched the region and configuration, and
        // failing there marked every one of them permanently broken — the bubbles stayed
        // empty for the rest of the session even though the keys arrived a second later.
        val creds = if (credentialsStore.current.isComplete) {
            credentialsStore.current
        } else {
            withTimeoutOrNull(CREDENTIALS_TIMEOUT_MS) {
                credentialsStore.credentials.first { it.isComplete }
            }
        }

        if (creds == null || !creds.isComplete) {
            ZillitLog.w(TAG, "S3 credentials unavailable — transfer not attempted")
            return null
        }

        synchronized(lock) {
            // Required before any transfer, and it is the SDK's own instruction: without
            // it every transfer logs an error and none of them resume when the connection
            // comes back. Idempotent, so calling it on each pass costs nothing.
            runCatching { TransferNetworkLossHandler.getInstance(context) }

            if (cachedFor != creds || transferUtility == null) {
                // Region or keys changed: rebuild rather than reuse. See the class doc.
                s3 = AmazonS3Client(
                    BasicAWSCredentials(creds.accessKey, creds.secretKey),
                    Region.getRegion(creds.region),
                )
                transferUtility = TransferUtility.builder()
                    .context(context)
                    .s3Client(s3)
                    .build()
                cachedFor = creds
                // A credentials change invalidates the per-region clients too: they were
                // built with the previous keys.
                byRegion.clear()
                ZillitLog.d(TAG, "S3 client built for region=${creds.region}")
            }
            return transferUtility!! to creds
        }
    }

    /**
     * The transfer client for [region], which may not be the project's.
     *
     * Falls back to the project's own client when the region is blank, unknown to the SDK,
     * or already the project's — the common case, and the one that must stay allocation-free.
     */
    private fun utilityFor(
        region: String?,
        creds: StorageCredentials,
        default: TransferUtility,
    ): TransferUtility {
        val wanted = region?.trim().orEmpty()
        if (wanted.isEmpty() || wanted.equals(creds.region, ignoreCase = true)) return default

        return synchronized(lock) {
            byRegion.getOrPut(wanted) {
                val client = runCatching {
                    AmazonS3Client(
                        BasicAWSCredentials(creds.accessKey, creds.secretKey),
                        Region.getRegion(wanted),
                    )
                }.getOrNull() ?: return@synchronized default

                ZillitLog.d(TAG, "S3 client built for foreign region=$wanted")
                TransferUtility.builder().context(context).s3Client(client).build()
            }
        }
    }

    fun upload(request: UploadRequest): Flow<TransferState> = callbackFlow {
        val file = request.file
        if (!file.exists() || file.length() == 0L) {
            // Checked before queueing: retrying a file that is not on disk can never
            // succeed, so it must not be offered as retryable.
            trySend(TransferState.Failed("File not found: ${request.fileName}", retryable = false))
            close()
            return@callbackFlow
        }

        val (utility, creds) = utilityOrNull() ?: run {
            // Retryable: the usual cause is credentials that have not arrived yet, which
            // the next attempt will find.
            trySend(TransferState.Failed("Storage is not configured yet", retryable = true))
            close()
            return@callbackFlow
        }

        trySend(TransferState.Queued)

        val observer: TransferObserver = utility.upload(
            creds.uploadBucket,
            request.remoteKey,
            file,
        )

        observer.setTransferListener(
            object : TransferListener {
                override fun onStateChanged(id: Int, state: AwsTransferState?) {
                    when (state) {
                        AwsTransferState.COMPLETED -> {
                            ZillitLog.d(TAG, "Uploaded ${request.remoteKey}")
                            trySend(TransferState.Complete(request.remoteKey, request.localPath))
                            close()
                        }

                        AwsTransferState.CANCELED -> {
                            trySend(TransferState.Cancelled)
                            close()
                        }

                        AwsTransferState.FAILED -> {
                            trySend(TransferState.Failed("Upload failed"))
                            close()
                        }

                        else -> Unit
                    }
                }

                override fun onProgressChanged(id: Int, current: Long, total: Long) {
                    if (total <= 0) return
                    trySend(
                        TransferState.InProgress(
                            fraction = (current.toFloat() / total).coerceIn(0f, 1f),
                            bytesTransferred = current,
                            bytesTotal = total,
                        ),
                    )
                }

                override fun onError(id: Int, ex: Exception?) {
                    ZillitLog.w(TAG, "Upload error ${request.remoteKey}: ${ex?.message}")
                    trySend(TransferState.Failed(ex?.localizedMessage ?: "Upload failed"))
                    close()
                }
            },
        )

        awaitClose {
            // Only cancels a transfer still running: a completed one is a no-op, so this
            // is safe on the normal path as well as on collector cancellation.
            runCatching { utility.cancel(observer.id) }
        }
    }

    fun download(request: DownloadRequest): Flow<TransferState> = callbackFlow {
        val (utility, creds) = utilityOrNull() ?: run {
            trySend(TransferState.Failed("Storage is not configured yet", retryable = true))
            close()
            return@callbackFlow
        }

        val destination = File(request.destinationPath)
        destination.parentFile?.mkdirs()

        // A complete local copy is a cache hit — re-downloading an attachment the user
        // already opened is the single most common wasted transfer in a chat app.
        if (destination.exists() && destination.length() > 0) {
            trySend(TransferState.Complete(request.remoteKey, request.destinationPath))
            close()
            return@callbackFlow
        }

        trySend(TransferState.Queued)

        // The file's own home wins over the project's: what the caller passed if it knew,
        // otherwise whatever was recorded for this key when the server named it, otherwise
        // the project default. See [MediaLocations].
        val known = mediaLocations.locationFor(request.remoteKey)
        val bucket = request.bucket?.takeIf { it.isNotBlank() }
            ?: known?.bucket
            ?: creds.effectiveDownloadBucket
        val region = request.region?.takeIf { it.isNotBlank() } ?: known?.region

        val observer = utilityFor(region, creds, utility).download(
            bucket,
            request.remoteKey,
            destination,
        )

        observer.setTransferListener(
            object : TransferListener {
                override fun onStateChanged(id: Int, state: AwsTransferState?) {
                    when (state) {
                        AwsTransferState.COMPLETED -> {
                            trySend(
                                TransferState.Complete(request.remoteKey, request.destinationPath),
                            )
                            close()
                        }

                        AwsTransferState.CANCELED -> {
                            trySend(TransferState.Cancelled)
                            close()
                        }

                        AwsTransferState.FAILED -> {
                            // A partial file would be served as a cache hit next time,
                            // so it has to go.
                            destination.delete()
                            trySend(TransferState.Failed("Download failed"))
                            close()
                        }

                        else -> Unit
                    }
                }

                override fun onProgressChanged(id: Int, current: Long, total: Long) {
                    if (total <= 0) return
                    trySend(
                        TransferState.InProgress(
                            fraction = (current.toFloat() / total).coerceIn(0f, 1f),
                            bytesTransferred = current,
                            bytesTotal = total,
                        ),
                    )
                }

                override fun onError(id: Int, ex: Exception?) {
                    destination.delete()
                    ZillitLog.w(TAG, "Download error ${request.remoteKey}: ${ex?.message}")
                    trySend(TransferState.Failed(ex?.localizedMessage ?: "Download failed"))
                    close()
                }
            },
        )

        awaitClose { runCatching { utility.cancel(observer.id) } }
    }

    private companion object {
        const val TAG = "S3Client"

        /** Long enough for the project bootstrap to finish, short enough to fail visibly. */
        const val CREDENTIALS_TIMEOUT_MS = 15_000L
    }
}
