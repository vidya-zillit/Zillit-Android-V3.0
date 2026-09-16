package com.zillit.zillitapp.feature.cnc.data

import com.zillit.zillitapp.core.badge.BadgeKey
import com.zillit.zillitapp.core.badge.BadgeManager
import com.zillit.zillitapp.core.badge.BadgeSection
import com.zillit.zillitapp.core.badge.BadgeSource
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.presence.PresenceRepository
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.socket.SocketConnectionState
import com.zillit.zillitapp.core.socket.SocketManager
import com.zillit.zillitapp.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps local storage current from the socket, for as long as a project is open.
 *
 * Every subscription lives here rather than in a view model, because a thread's messages
 * have to land in Realm whether or not that thread is on screen — otherwise the badge is
 * right and the list is stale, which is the single most reported class of chat bug. A view
 * model reads Realm and never touches the socket.
 *
 * Started once by the project bootstrapper and stopped when the project closes.
 */
@Singleton
class CncRealtime @Inject constructor(
    private val socket: CncSocketDataSource,
    private val socketManager: SocketManager,
    private val repository: CncRepository,
    private val presence: PresenceRepository,
    private val typing: TypingController,
    private val cncDirectory: CncDirectory,
    private val badgeManager: BadgeManager,
    private val session: SessionStore,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private var started = false

    /** Idempotent: re-entering a project must not double every listener. */
    fun start() {
        if (started) return
        started = true

        ChatSurface.entries.forEach { surface ->
            observeMessages(surface)
            observeReceipts(surface)
            observeRemovals(surface)
            observeEdits(surface)
            observeReactions(surface)
            observeTyping(surface)
        }

        observeRooms()
        observeBlocks()
        observeReconnect()
        presence.start()
    }

    /**
     * Count an arriving message against its conversation.
     *
     * Skipped for a message this user sent from another device — it is theirs, and a badge
     * for your own message is noise — and for the thread currently on screen, which is
     * being read as it arrives.
     */
    private suspend fun raiseBadge(dto: CncMessageDto) {
        val project = session.activeProject.value ?: return
        if (dto.sender == project.userId) return

        val conversationId = dto.conversationIdFor(project.userId)
        if (conversationId.isBlank() || conversationId == repository.openConversationId) return

        val key = BadgeKey(
            projectId = project.projectId,
            section = BadgeSection.CNC,
            unit = conversationId,
        )
        // REALTIME, the same origin stored notifications use: a socket message and its push
        // are the same event, so they must share a row or the two would double-count each
        // other whenever both arrive.
        badgeManager.set(
            key = key,
            source = BadgeSource.REALTIME,
            count = badgeManager.countUnder(key) + 1,
        )
    }

    // ── Rooms ────────────────────────────────────────────────────────────────

    /**
     * A room was created, changed or removed.
     *
     * All three do the same thing: refetch the room list. The events carry only the room
     * that changed, but membership and admin rights are the server's decision and a
     * partial local patch that disagrees with it is worse than one extra call — which is
     * also how a group you were just added to appears without a manual refresh.
     */
    /**
     * Runs one piece of a resync, and lets the rest continue if it fails.
     *
     * Named so the log says which piece: "resync failed" on a six-step block told you
     * nothing about which step, or that five others had been skipped because of it.
     */
    private suspend fun step(what: String, surface: ChatSurface, block: suspend () -> Unit) {
        runCatching { block() }
            .onFailure { ZillitLog.w(TAG, "resync $what failed for ${surface.key}: ${it.message}") }
    }

    private fun observeRooms() {
        scope.launch {
            ChatSurface.entries.forEach { surface ->
                launch {
                    merge(
                        socket.roomCreated(surface),
                        socket.roomUpdated(surface),
                        socket.roomRemoved(surface),
                    ).collect {
                        runCatching { cncDirectory.refreshRooms() }
                            .onFailure { error -> ZillitLog.w(TAG, "room refresh: ${error.message}") }
                    }
                }
            }
        }
    }

    /** Someone blocked or unblocked this user, from any device. */
    private fun observeBlocks() {
        scope.launch {
            merge(socket.blocked(), socket.unblocked()).collect {
                runCatching { repository.refreshBlocks(ChatSurface.CNC) }
                    .onFailure { error -> ZillitLog.w(TAG, "block refresh: ${error.message}") }
            }
        }
    }

    // ── Messages ─────────────────────────────────────────────────────────────

    // flatMapConcat keeps the chunks in the order they arrived, which matters: a later
    // chunk can carry the edit or the read receipt for a message in an earlier one.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMessages(surface: ChatSurface) {
        scope.launch {
            // Direct, group and the catch-up stream all mean the same thing to storage, so
            // they are merged rather than handled three times over.
            merge(
                socket.incomingMessages(surface, isGroup = false),
                socket.incomingMessages(surface, isGroup = true),
                socket.missedMessages().flatMapConcat { it.detail.asFlow() }
                    .map { CncMessageEnvelope(success = true, detail = it) },
            ).collect { envelope ->
                val dto = envelope.detail ?: return@collect
                repository.applyIncoming(surface, dto)

                // Two things v2 does here that v3 was not doing at all.
                //
                // First, tell the sender it arrived — read if their thread is open,
                // delivered otherwise. Without this no message ever leaves one tick.
                repository.acknowledge(surface, dto)

                // Second, raise the badge. Counts were only ever coming down: the tree is
                // fed by push notifications, so a message that arrives over the socket
                // while the app is open registered nowhere.
                raiseBadge(dto)
            }
        }
    }

    private fun observeReceipts(surface: ChatSurface) {
        scope.launch {
            merge(
                socket.readReceipts(surface, isGroup = false),
                socket.readReceipts(surface, isGroup = true),
            ).collect { envelope ->
                envelope.detail?.let { repository.applyReadReceipt(surface, it) }
            }
        }
    }

    private fun observeRemovals(surface: ChatSurface) {
        scope.launch {
            merge(
                socket.deletions(surface, isGroup = false),
                socket.deletions(surface, isGroup = true),
            ).collect { envelope ->
                repository.applyDeletion(surface, envelope.serverIds)
            }
        }
        scope.launch {
            merge(
                socket.expiries(surface, isGroup = false),
                socket.expiries(surface, isGroup = true),
            ).collect { envelope ->
                repository.applyExpiry(surface, listOfNotNull(envelope.detail?.id))
            }
        }
    }

    private fun observeEdits(surface: ChatSurface) {
        scope.launch {
            merge(
                socket.edits(surface, isGroup = false),
                socket.edits(surface, isGroup = true),
            ).collect { envelope ->
                envelope.detail?.let { repository.applyIncoming(surface, it) }
            }
        }
    }

    private fun observeReactions(surface: ChatSurface) {
        scope.launch {
            socket.reactions(surface).collect { envelope ->
                envelope.detail?.let { repository.applyIncoming(surface, it) }
            }
        }
    }

    // ── Typing ───────────────────────────────────────────────────────────────

    private fun observeTyping(surface: ChatSurface) {
        scope.launch {
            merge(
                socket.typing(surface, isGroup = false),
                socket.typing(surface, isGroup = true),
            ).collect { envelope ->
                val event = envelope.detail ?: return@collect
                val sender = event.sender ?: return@collect

                // The echo of this user's own typing, which would otherwise show them
                // typing to themselves on their other devices.
                if (sender == session.activeProject.value?.userId) return@collect

                typing.onRemoteTyping(
                    senderId = sender,
                    // Resolved by the screen, which has the directory. Passing the id keeps
                    // this off the naming path entirely.
                    senderName = sender,
                    isStart = event.status != "end",
                )
            }
        }
    }

    // ── Reconnect ────────────────────────────────────────────────────────────

    /**
     * Catch up after every reconnect.
     *
     * Three things have to happen and all three are easy to forget: ask for what was missed
     * while the socket was down, resend anything queued locally, and tell the server this
     * device is back. v2 does the first two from different places and the third from an
     * activity callback, which is why a long background leaves it half-synced.
     */
    private fun observeReconnect() {
        scope.launch {
            socketManager.connectionState.filterNotNull().collect { state ->
                if (state !is SocketConnectionState.Connected) return@collect

                ZillitLog.d(TAG, "socket up — resyncing")
                presence.markOnline(force = true)

                ChatSurface.entries.forEach { surface ->
                    // Each step guarded on its own, not the sequence as a whole.
                    //
                    // Wrapping all six together meant the first one to throw cancelled the
                    // other five: a catch-up request that failed took the pending flush, the
                    // recent list, the ordering, the block list and the queued read receipts
                    // with it, silently, on every reconnect. These are independent pieces of
                    // catching up and one being unavailable says nothing about the rest.
                    step("missed messages", surface) {
                        socket.requestMissedMessages(repository.newestTimestamp(surface))
                    }
                    step("pending flush", surface) { repository.flushPending(surface) }
                    step("recent list", surface) { repository.refreshRecent(surface) }
                    step("ordering", surface) { repository.syncOrderFromMessages(surface) }
                    // Blocks are server state with no local source of truth, so a
                    // reconnect is the moment to reconcile them.
                    step("blocks", surface) { repository.refreshBlocks(surface) }
                    // Receipts that could not be sent while the socket was down. Until
                    // these land the sender's message sits on one tick.
                    step("receipts", surface) { repository.flushReceipts() }
                }
            }
        }
    }

    private companion object {
        const val TAG = "CncRealtime"
    }
}
