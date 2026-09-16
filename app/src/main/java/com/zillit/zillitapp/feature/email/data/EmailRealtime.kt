package com.zillit.zillitapp.feature.email.data

import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.socket.SocketEvents
import com.zillit.zillitapp.core.socket.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps mail current while the app is open.
 *
 * The service emits about twenty distinct events, and most of them mean the same thing to a
 * client that caches whole folders: *something in this mailbox changed, re-diff it*. So they
 * collapse into a small number of signals here rather than twenty listeners in twenty
 * screens.
 *
 * ### Three wiring bugs from v2 that are fixed here
 * All three are one-line mistakes in the same block of v2's `BaseSocketListener`, and each
 * fails silently:
 *
 *  1. `contactUpdated` is exposed from `_signatureUpdated`, so **no contact event ever
 *     arrives** and every contact subscriber receives signature traffic instead.
 *  2. `inbound:email:delete:trail` is emitted onto the `email:read` channel, so **a deletion
 *     marks the message read** rather than removing it.
 *  3. `email:readby:update` is emitted onto `_signatureUpdated`, so **read receipts refresh
 *     the signature screen**.
 *
 * Each has its own flow here, named for what it carries.
 */
@Singleton
class EmailRealtime @Inject constructor(
    private val socketManager: SocketManager,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private var started = false

    /**
     * New mail arrived. The inbox and whatever folder is open both need re-diffing.
     *
     * Distinct from [folderChanged] because this one is also what should make a badge
     * appear — everything else is bookkeeping the user did not ask about.
     */
    private val _mailReceived = signal()
    val mailReceived: SharedFlow<Unit> = _mailReceived.asSharedFlow()

    /** Something moved, was deleted, was sent, or the trash was emptied. */
    private val _folderChanged = signal()
    val folderChanged: SharedFlow<Unit> = _folderChanged.asSharedFlow()

    /** The folder list itself changed — created, renamed or deleted. */
    private val _foldersChanged = signal()
    val foldersChanged: SharedFlow<Unit> = _foldersChanged.asSharedFlow()

    /** A message was read elsewhere. Carries the uid. */
    private val _readElsewhere = MutableSharedFlow<Int>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val readElsewhere: SharedFlow<Int> = _readElsewhere.asSharedFlow()

    /** A message was removed from a conversation. Carries its id. */
    private val _trailMessageDeleted = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val trailMessageDeleted: SharedFlow<String> = _trailMessageDeleted.asSharedFlow()

    private val _draftsChanged = signal()
    val draftsChanged: SharedFlow<Unit> = _draftsChanged.asSharedFlow()

    private val _groupsChanged = signal()
    val groupsChanged: SharedFlow<Unit> = _groupsChanged.asSharedFlow()

    private val _signaturesChanged = signal()
    val signaturesChanged: SharedFlow<Unit> = _signaturesChanged.asSharedFlow()

    /** Its own flow, not the signature one. */
    private val _contactsChanged = signal()
    val contactsChanged: SharedFlow<Unit> = _contactsChanged.asSharedFlow()

    /** Its own flow, not the signature one. */
    private val _readReceiptsChanged = signal()
    val readReceiptsChanged: SharedFlow<Unit> = _readReceiptsChanged.asSharedFlow()

    /**
     * Attaches every listener. Safe to call more than once.
     *
     * Started with the project rather than with a screen: a folder count has to be right
     * when the user opens the drawer, not only while they are already looking at mail.
     */
    fun start() {
        if (started) return
        started = true

        raw(SocketEvents.EMAIL_RECEIVED) { _mailReceived.tryEmit(Unit) }

        // Everything that means "this folder is no longer what you have cached". Debounced
        // because a bulk move emits one event per message, and each would otherwise be its
        // own full-folder round trip.
        merge(
            rawFlow(SocketEvents.EMAIL_INBOUND_DELETED),
            rawFlow(SocketEvents.EMAIL_OUTBOUND_DELETED),
            rawFlow(SocketEvents.EMAIL_SENT),
            rawFlow(SocketEvents.EMAIL_MOVE),
            rawFlow(SocketEvents.EMAILS_MOVED),
            rawFlow(SocketEvents.EMAIL_TRASH_EMPTIED),
        )
            .debounce(BURST_WINDOW_MS)
            .onEach { _folderChanged.tryEmit(Unit) }
            .launchIn(scope)

        merge(
            rawFlow(SocketEvents.EMAIL_FOLDER_SAVED),
            rawFlow(SocketEvents.EMAIL_FOLDER_UPDATED),
            rawFlow(SocketEvents.EMAIL_FOLDER_DELETED),
        )
            .debounce(BURST_WINDOW_MS)
            .onEach { _foldersChanged.tryEmit(Unit) }
            .launchIn(scope)

        // `{ data: { uid } }`
        raw(SocketEvents.EMAIL_READ) { payload ->
            payload.stringAt("uid")?.toIntOrNull()?.let { _readElsewhere.tryEmit(it) }
        }

        // `{ data: { id } }` — a deletion, and it goes to the deletion flow.
        raw(SocketEvents.EMAIL_TRAIL_DELETED) { payload ->
            payload.stringAt("id")?.let { _trailMessageDeleted.tryEmit(it) }
        }

        merge(
            rawFlow(SocketEvents.EMAIL_DRAFT_SAVED),
            rawFlow(SocketEvents.EMAIL_DRAFT_UPDATED),
            rawFlow(SocketEvents.EMAIL_DRAFT_DELETED),
        )
            .debounce(BURST_WINDOW_MS)
            .onEach { _draftsChanged.tryEmit(Unit) }
            .launchIn(scope)

        merge(
            rawFlow(SocketEvents.EMAIL_GROUP_SAVED),
            rawFlow(SocketEvents.EMAIL_GROUP_UPDATED),
            rawFlow(SocketEvents.EMAIL_GROUP_DELETED),
        )
            .debounce(BURST_WINDOW_MS)
            .onEach { _groupsChanged.tryEmit(Unit) }
            .launchIn(scope)

        merge(
            rawFlow(SocketEvents.EMAIL_SIGNATURE_CREATED),
            rawFlow(SocketEvents.EMAIL_SIGNATURE_UPDATED),
            rawFlow(SocketEvents.EMAIL_SIGNATURE_DELETED),
        )
            .debounce(BURST_WINDOW_MS)
            .onEach { _signaturesChanged.tryEmit(Unit) }
            .launchIn(scope)

        merge(
            rawFlow(SocketEvents.EMAIL_CONTACT_SAVED),
            rawFlow(SocketEvents.EMAIL_CONTACT_UPDATED),
            rawFlow(SocketEvents.EMAIL_CONTACT_DELETED),
        )
            .debounce(BURST_WINDOW_MS)
            .onEach { _contactsChanged.tryEmit(Unit) }
            .launchIn(scope)

        raw(SocketEvents.EMAIL_READ_BY_UPDATE) { _readReceiptsChanged.tryEmit(Unit) }

        ZillitLog.d(TAG, "email socket listeners attached")
    }

    private fun rawFlow(event: String) = socketManager.rawEvents(event)

    private fun raw(event: String, handle: (JsonObject) -> Unit) {
        scope.launch {
            rawFlow(event).collect { payload ->
                runCatching { handle(payload) }
                    .onFailure { ZillitLog.w(TAG, "$event handler failed: ${it.message}") }
            }
        }
    }

    /**
     * Reads a scalar out of the payload's `data` object, or off the payload itself.
     *
     * The service is not consistent about the nesting, and a value read from the wrong
     * level is silently absent rather than an error — which is how v2 ends up matching a
     * message id against the literal string `"null"`.
     */
    private fun JsonObject.stringAt(key: String): String? {
        val nested = (this["data"] as? JsonObject)?.get(key)
        val direct = this[key]
        return ((nested ?: direct) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun signal() = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private companion object {
        const val TAG = "EmailRealtime"

        /** Long enough to collapse a bulk operation, short enough to feel immediate. */
        const val BURST_WINDOW_MS = 400L
    }
}
