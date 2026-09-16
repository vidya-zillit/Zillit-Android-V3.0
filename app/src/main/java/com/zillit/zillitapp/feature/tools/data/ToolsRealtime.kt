package com.zillit.zillitapp.feature.tools.data

import com.zillit.zillitapp.core.di.ApplicationScope
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the Tools tab current while the app is open.
 *
 * Three things can change under the user, and they are separate because they cost different
 * amounts to reload: the tool list, the group list, and this user's section order.
 *
 * ### Why there is no refresh on resume
 * v2 re-fetches all three every time the tab becomes visible, because it tears its socket
 * listeners down when the tab is backgrounded and would otherwise miss anything that
 * happened meanwhile. v3 holds one socket for the life of the process, so the events arrive
 * whether the tab is showing or not and the belt-and-braces reload is not needed.
 */
@Singleton
class ToolsRealtime @Inject constructor(
    private val socketManager: SocketManager,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private var started = false

    /** The tool list changed — enabled, disabled, regrouped, or an access right moved. */
    private val _toolsChanged = signal()
    val toolsChanged: SharedFlow<Unit> = _toolsChanged.asSharedFlow()

    /** A group was created, renamed or deleted. */
    private val _groupsChanged = signal()
    val groupsChanged: SharedFlow<Unit> = _groupsChanged.asSharedFlow()

    /** This user reordered their sections somewhere else. */
    private val _orderChanged = signal()
    val orderChanged: SharedFlow<Unit> = _orderChanged.asSharedFlow()

    fun start() {
        if (started) return
        started = true

        // Debounced across all of them: an admin switching six tools off emits six events,
        // and each would otherwise be its own full re-fetch of the same list.
        merge(
            socketManager.rawEvents(SocketEvents.PROJECT_TOOLS_UPDATE),
            socketManager.rawEvents(SocketEvents.ACCESS_VIEW),
            socketManager.rawEvents(SocketEvents.ACCESS_POST),
            socketManager.rawEvents(SocketEvents.ACCESS_DOWNLOAD),
        )
            .debounce(BURST_WINDOW_MS)
            .onEach { _toolsChanged.tryEmit(Unit) }
            .launchIn(scope)

        merge(
            socketManager.rawEvents(SocketEvents.TOOL_GROUP_CREATE),
            socketManager.rawEvents(SocketEvents.TOOL_GROUP_UPDATE),
            socketManager.rawEvents(SocketEvents.TOOL_GROUP_DELETE),
        )
            .debounce(BURST_WINDOW_MS)
            .onEach { _groupsChanged.tryEmit(Unit) }
            .launchIn(scope)

        socketManager.rawEvents(SocketEvents.TOOL_GROUP_ORDER_UPDATE)
            .onEach { _orderChanged.tryEmit(Unit) }
            .launchIn(scope)
    }

    private fun signal() = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private companion object {
        /** Long enough to collapse a burst, short enough to feel immediate. */
        const val BURST_WINDOW_MS = 400L
    }
}
