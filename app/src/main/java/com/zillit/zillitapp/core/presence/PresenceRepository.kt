package com.zillit.zillitapp.core.presence

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.eventFlow
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.socket.SocketEvents
import com.zillit.zillitapp.core.socket.SocketManager
import com.zillit.zillitapp.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who is online, and telling the server that you are.
 *
 * ### The contract, which is not ours to change
 * Presence is **split across two transports**, and every platform depends on both halves:
 *
 *  - **Writing** goes over the socket. `mark_online` / `mark_offline` carry
 *    `{ project_id, user_id }`, and the backend is what turns those into presence.
 *  - **Reading** comes from Firebase Realtime Database, which the backend mirrors into at
 *    `devices/{deviceId}/{projectId}` with `is_online` and `update_time`.
 *
 * A client that only emitted, or only listened, would look correct on its own screen and
 * be invisible to web and iOS. So both halves live here, together, rather than one in the
 * socket layer and one in a Firebase helper that nobody connects to it.
 *
 * ### Keyed by device, not by user
 * The read path is keyed on the other person's **device id** — one person signed in on a
 * phone and a tablet has two entries. That is why [observe] takes a device id and why a
 * conversation row must carry one.
 */
@Singleton
class PresenceRepository @Inject constructor(
    private val socket: SocketManager,
    private val session: SessionStore,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Mirrors the server's view, so a repeated foreground does not re-emit. */
    private var markedOnline = false

    /**
     * Start reporting this device's presence for as long as the process lives.
     *
     * Foreground and background, not activity resume and pause: v2 emits from an activity
     * callback, so rotating the phone marks the user offline and online again, and every
     * other client sees them blink.
     */
    fun start() {
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.eventFlow.collect { event ->
                when (event) {
                    Lifecycle.Event.ON_START -> markOnline()
                    Lifecycle.Event.ON_STOP -> markOffline()
                    else -> Unit
                }
            }
        }
    }

    /**
     * Say this device is online.
     *
     * @param force emits even when we believe the server already knows — for a reconnect,
     *   where the server has forgotten but this object has not.
     */
    fun markOnline(force: Boolean = false) {
        if (markedOnline && !force) return
        if (emit(SocketEvents.MARK_ONLINE)) markedOnline = true
    }

    fun markOffline() {
        // Set first: a failed emit must not leave us believing we are still online, or the
        // next foreground would skip the re-emit and the user would stay dark.
        markedOnline = false
        emit(SocketEvents.MARK_OFFLINE)
    }

    private fun emit(event: String): Boolean {
        val project = session.activeProject.value ?: return false
        return socket.emit(event, payload(project.projectId, project.userId))
    }

    private fun payload(projectId: String, userId: String): JsonObject = buildJsonObject {
        put("project_id", projectId)
        put("user_id", userId)
    }

    /**
     * Whether the person on [deviceId] is online, and when they last were.
     *
     * Cold: the listener is attached when something collects and detached when it stops, so
     * a list of forty rows holds forty listeners only while that list is on screen. v2
     * keeps a single shared listener and removes the previous one each time, which is why
     * its presence dot only ever worked on the last screen that asked.
     */
    fun observe(deviceId: String, projectId: String): Flow<Presence> = callbackFlow {
        if (deviceId.isBlank() || projectId.isBlank()) {
            trySend(Presence.Unknown)
            awaitClose { }
            return@callbackFlow
        }

        val reference = FirebaseDatabase.getInstance()
            .getReference(DEVICES)
            .child(deviceId)
            .child(projectId)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val online = snapshot.child(IS_ONLINE).getValue(Boolean::class.java) ?: false
                val lastSeen = snapshot.child(UPDATE_TIME).getValue(Long::class.java) ?: 0L
                trySend(Presence(online = online, lastSeen = lastSeen))
            }

            // A read that is refused or interrupted means "we do not know", never "offline":
            // showing someone as away because Firebase rules rejected us is a lie.
            override fun onCancelled(error: DatabaseError) {
                trySend(Presence.Unknown)
            }
        }

        reference.addValueEventListener(listener)
        awaitClose { reference.removeEventListener(listener) }
    }.distinctUntilChanged()

    private companion object {
        const val DEVICES = "devices"
        const val IS_ONLINE = "is_online"
        const val UPDATE_TIME = "update_time"
    }
}

/**
 * Someone's presence.
 *
 * [lastSeen] is epoch millis and 0 when the server has never recorded one — a person who
 * has not opened the project on that device. The UI shows nothing rather than 1 Jan 1970.
 */
data class Presence(
    val online: Boolean,
    val lastSeen: Long,
) {
    val hasLastSeen: Boolean get() = lastSeen > 0

    companion object {
        /** Not yet known: neither dot nor last-seen line, because we have no basis for either. */
        val Unknown = Presence(online = false, lastSeen = 0L)
    }
}
