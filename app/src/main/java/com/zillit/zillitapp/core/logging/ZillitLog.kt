package com.zillit.zillitapp.core.logging

import android.util.Log
import com.zillit.zillitapp.BuildConfig

/**
 * Console logging for the non-HTTP subsystems.
 *
 * API calls go through [ApiLogger], which also persists them. Firebase and the socket
 * needed the same visibility but not the same storage, so they share this instead: one
 * tag each, so you can isolate a subsystem in Logcat with a single filter rather than
 * sifting one merged stream.
 *
 * Everything is gated on `BuildConfig.DEBUG` — socket payloads and push payloads carry
 * user data, and Logcat is readable off a cabled device.
 */
object ZillitLog {

    const val TAG_FIREBASE = "ZillitFcm"
    const val TAG_SOCKET = "ZillitSocket"

    /** Badge reads, writes and recomputes — filter on this to follow a count end to end. */
    const val TAG_BADGE = "ZillitBadge"

    /** General debug logging for a named subsystem. */
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, message, throwable)
    }

    /** Push / Firebase lifecycle: token, message received, silent vs tray, routing. */
    fun firebase(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG_FIREBASE, message)
    }

    fun firebaseWarn(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(TAG_FIREBASE, message, throwable)
    }

    /** Socket lifecycle: connect, disconnect, reconnect attempts, emits, inbound events. */
    fun badge(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG_BADGE, message)
    }

    fun socket(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG_SOCKET, message)
    }

    fun socketWarn(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(TAG_SOCKET, message, throwable)
    }

    /**
     * Logs a payload, chunked.
     *
     * Logcat silently truncates a single line past roughly 4 KB — precisely when the
     * payload is most worth reading.
     */
    fun payload(tag: String, label: String, body: String?) {
        if (!BuildConfig.DEBUG) return
        if (body.isNullOrBlank()) {
            Log.d(tag, "$label: (empty)")
            return
        }
        Log.d(tag, "$label:")
        var index = 0
        while (index < body.length) {
            val end = minOf(index + CHUNK_CHARS, body.length)
            Log.d(tag, "  " + body.substring(index, end))
            index = end
        }
    }

    private const val CHUNK_CHARS = 3_000
}
