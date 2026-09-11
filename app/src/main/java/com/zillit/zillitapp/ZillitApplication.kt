package com.zillit.zillitapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.directory.UserLookup
import com.zillit.zillitapp.core.network.NetworkMonitor
import com.zillit.zillitapp.core.session.CurrentUserStore
import com.zillit.zillitapp.core.storage.StorageCredentialsStore
import com.zillit.zillitapp.core.storage.UploadQueue
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Kept deliberately thin. v2's `ZillitApplication` ran Realm setup, Firebase, remote
 * config, the calendar sync engine and more in `onCreate`, in an order later code
 * depended on — so startup cost grew with every feature and the ordering was load-bearing
 * but undocumented. Here the only things that start eagerly are connectivity observation,
 * the user-lookup handle and the cached profile; everything else is created lazily by Hilt
 * when something first injects it.
 */
@HiltAndroidApp
class ZillitApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var projectDirectory: ProjectDirectory

    @Inject
    lateinit var currentUserStore: CurrentUserStore

    @Inject
    lateinit var uploadQueue: UploadQueue

    @Inject
    lateinit var storageCredentials: StorageCredentialsStore

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var errorLogReporter: com.zillit.zillitapp.core.errorlog.ErrorLogReporter

    /**
     * WorkManager needs Hilt's factory to build [com.zillit.zillitapp.core.storage.UploadWorker],
     * which injects the S3 client and the chat repository. Provided here rather than via
     * the default initialiser, which cannot see the Hilt graph.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        // Registered for the process lifetime: the socket's recovery logic subscribes to
        // this, so it has to be live before any screen asks for a connection.
        networkMonitor.start()

        // Crash reporting into the shared error log. The event is queued synchronously —
        // the process is dying — and ships with the next batch after relaunch; the
        // previous handler still runs so the system crash dialog and ANR pipeline behave
        // exactly as before.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { errorLogReporter.reportCrash(throwable) }
            previousHandler?.uncaughtException(thread, throwable)
        }

        // Anything queued before the last death — crashes especially — ships now.
        errorLogReporter.flushPending()

        // Makes `"<user_id>".userDetails()` work anywhere. Installed here rather than
        // injected at each call site because the call sites are extension functions on
        // String, which cannot take a constructor parameter.
        UserLookup.install(projectDirectory) { currentUserStore.userId }

        // Restore the cached profile before the first screen asks whether the user is an
        // admin. Cheap — one preferences read — and it means a cold start does not have to
        // wait on the network to decide what navigation to show.
        applicationScope.launch { currentUserStore.restore() }

        // Storage credentials come back from preferences before the network has said
        // anything. Without this a cold start reports "storage is not configured" for
        // every thumbnail the first screen asks for, even though the keys are on disk.
        applicationScope.launch { storageCredentials.restore() }

        // Uploads interrupted by a kill or a reboot resume here, per project, without the
        // user having to reopen the screen that started them.
        applicationScope.launch { uploadQueue.resumeAll() }
    }
}
