package com.zillit.zillitapp.feature.cnc.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.database.entity.CncConversationEntity
import com.zillit.zillitapp.core.badge.BadgeAxis
import com.zillit.zillitapp.core.badge.BadgeKey
import com.zillit.zillitapp.core.badge.BadgeManager
import com.zillit.zillitapp.core.badge.BadgeSection
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.cnc.data.ChatSurface
import com.zillit.zillitapp.feature.cnc.data.CncDirectory
import com.zillit.zillitapp.core.common.toShortDateTimeLabel
import com.zillit.zillitapp.core.common.toTimeLabel
import com.zillit.zillitapp.core.notification.BadgeSyncCoordinator
import com.zillit.zillitapp.feature.cnc.data.CallLogDto
import com.zillit.zillitapp.feature.cnc.data.CallLogRepository
import com.zillit.zillitapp.feature.cnc.data.CncRepository
import com.zillit.zillitapp.feature.cnc.data.RecentChatOrder
import com.zillit.zillitapp.feature.cnc.data.RecentScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.ProjectEntity
import io.realm.kotlin.ext.query
import com.zillit.zillitapp.core.directory.ProjectUser
import com.zillit.zillitapp.feature.cnc.data.CallParticipantDto
import com.zillit.zillitapp.core.common.toDurationLabel
import kotlinx.coroutines.flow.asStateFlow
import com.zillit.zillitapp.feature.cnc.data.CncGroupRepository
import com.zillit.zillitapp.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The three C&C lists, from storage.
 *
 * Everything is derived from one Realm query. The tab, the filter and the search box are
 * local state, so switching between them is instant and works offline — v2 refetches on
 * every tab change, which is why its list flickers and occasionally reorders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CncListViewModel @Inject constructor(
    private val repository: CncRepository,
    private val cncDirectory: CncDirectory,
    private val directory: ProjectDirectory,
    private val badgeManager: BadgeManager,
    private val callLogs: CallLogRepository,
    private val badgeSync: BadgeSyncCoordinator,
    private val session: SessionStore,
    private val realmProvider: RealmProvider,
    private val groupRepository: CncGroupRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val surface = ChatSurface.CNC

    /**
     * The conversation open beside the list, on a window wide enough to show both.
     *
     * Only meaningful in the multi-pane layout — on a phone a tap navigates and this stays
     * null. In [SavedStateHandle] because the transition that matters most, folding or
     * unfolding the device, recreates the activity: the conversation being read has to be
     * the same one afterwards.
     */
    val openedConversation: StateFlow<OpenedConversation?> = combine(
        savedState.getStateFlow<String?>(KEY_OPEN_ID, null),
        savedState.getStateFlow<Boolean?>(KEY_OPEN_IS_GROUP, null),
    ) { id, isGroup ->
        id?.takeIf { it.isNotBlank() }?.let { OpenedConversation(it, isGroup ?: false) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun openConversation(id: String, isGroup: Boolean) {
        savedState[KEY_OPEN_ID] = id
        savedState[KEY_OPEN_IS_GROUP] = isGroup
    }

    fun closeConversation() {
        savedState[KEY_OPEN_ID] = null
        savedState[KEY_OPEN_IS_GROUP] = null
    }

    /**
     * The call whose activity is open beside the log.
     *
     * The same idea as [openedConversation] and deliberately separate from it: the two
     * panes belong to different tabs and neither should survive a switch to the other.
     */
    val openedCall: StateFlow<String?> =
        savedState.getStateFlow<String?>(KEY_OPEN_CALL, null)
            .map { it?.takeIf(String::isNotBlank) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun openCallActivity(callId: String) {
        savedState[KEY_OPEN_CALL] = callId
    }

    fun closeCallActivity() {
        savedState[KEY_OPEN_CALL] = null
    }

    private val tab = MutableStateFlow(CncTab.CHAT)
    private val chatFilter = MutableStateFlow(ChatFilter.ALL)
    private val callFilter = MutableStateFlow(CallFilter.RECENT)
    private val query = MutableStateFlow("")

    /**
     * Unread per conversation, straight from the badge tree.
     *
     * Not from the recent-list fetch: that is a snapshot from whenever it last ran, while
     * this moves the moment a message arrives or a thread is opened — including for pushes
     * that never touch the chat socket. The axis is the unit, which for C&C is the room id
     * on a group and the sender on a one-to-one.
     */
    private val unreadByConversation: StateFlow<Map<String, Int>> = session.activeProject
        .flatMapLatest { project ->
            if (project == null) flowOf(emptyMap()) else {
                badgeManager.observeGrouped(
                    prefix = BadgeKey.section(project.projectId, BadgeSection.CNC),
                    by = BadgeAxis.UNIT,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /**
     * The call log, fetched rather than stored.
     *
     * Reloaded when the filter changes because Missed is a server-side query, not a subset
     * of recents — a missed call can be older than the newest page of recents, so filtering
     * locally would hide it.
     */
    private val calls = MutableStateFlow<List<CallRow>>(emptyList())

    /**
     * True until the first refresh has finished.
     *
     * The tabs read this to choose between a spinner and their empty state, and it was
     * hardcoded false — so on a cold start, while the directory merge and the recent-list
     * fetch were still running, all three tabs said there was nothing here. "No
     * conversations yet" and "still loading" are different answers and the list was giving
     * the wrong one.
     */
    private val firstLoad = MutableStateFlow(true)

    /**
     * The raw rows behind every page fetched, keyed by id.
     *
     * The list is built from [CallRow]s, which carry only what a row draws. Opening a call
     * needs the rest — who was on it and how each of them fared — and re-requesting one row
     * the user just tapped would be a round trip to redisplay what is already here.
     */
    private val loadedCallsById = mutableMapOf<String, CallLogDto>()

    private val loadedCalls: Collection<CallLogDto> get() = loadedCallsById.values

    private val callsLoading = MutableStateFlow(false)

    val state: StateFlow<CncUiState> = combine(
        repository.observeConversations(surface),
        tab,
        combine(chatFilter, callFilter, query) { chat, call, text -> Triple(chat, call, text) },
        // Three sources folded into one so the five-argument limit still holds. The
        // loading half is separate from the data half on purpose: a list can be empty and
        // settled, or empty and still arriving, and the screens draw those differently.
        combine(unreadByConversation, calls, firstLoad, callsLoading) { unread, callRows, first, callsBusy ->
            Badges(unread, callRows, first, callsBusy)
        },
    ) { conversations, currentTab, filters, badges ->
        build(
            conversations = conversations,
            tab = currentTab,
            chatFilter = filters.first,
            callFilter = filters.second,
            query = filters.third,
            unread = badges.unread,
            calls = badges.calls,
            loading = badges.firstLoad,
            callsLoading = badges.callsLoading,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CncUiState(),
    )


    init {
        // The bootstrapper already did this when the project opened; doing it again on
        // entry catches a directory that changed while the tab was closed, and costs one
        // cached read when nothing did.
        viewModelScope.launch {
            try {
                cncDirectory.mergePeople()
                // Before the network: whatever is already stored can order the list on its
                // own, so the tab is populated even if the socket has not answered yet.
                repository.syncOrderFromMessages(surface)
                repository.refreshRecent(surface)
            } finally {
                // In a finally, because a refresh that fails still has to stop the spinner:
                // an endless spinner is worse than an empty list, which at least invites a
                // pull to refresh.
                firstLoad.value = false
            }
        }
    }

    fun onTab(value: CncTab) {
        tab.value = value
        if (value == CncTab.CALL) {
            loadCalls()
            markCallsRead()
        }
        // Deliberately cleared: "Sahil" in Contacts and "Sahil" in the call log are
        // different questions, and carrying the text across answers neither.
        query.value = ""
    }

    fun onChatFilter(value: ChatFilter) { chatFilter.value = value }
    fun onQuery(value: String) { query.value = value }

    fun onToggleFavourite(conversationId: String) {
        viewModelScope.launch {
            // The star flips locally first, so a failure has to say so or the row keeps a
            // state the server never accepted.
            if (!repository.toggleFavourite(surface, conversationId)) {
                _messages.emit(R.string.something_went_wrong)
            }
        }
    }

    /** Pull to refresh: the directory, the room list and the recent list together. */
    fun refresh() {
        viewModelScope.launch {
            cncDirectory.refresh()
            repository.syncOrderFromMessages(surface)
            repository.refreshRecent(surface)
        }
    }

    fun onCallFilter(value: CallFilter) {
        callFilter.value = value
        loadCalls()
        if (value == CallFilter.MISSED) markCallsRead()
    }

    /**
     * Clear the missed-call badge.
     *
     * Seeing the missed list **is** acknowledging it, which is what v2 does when the tab
     * opens. Nothing else clears this badge, so without it the count sits there after the
     * user has plainly looked at it.
     */
    private fun markCallsRead() {
        val project = session.activeProject.value ?: return
        val key = BadgeKey(
            projectId = project.projectId,
            section = BadgeSection.CNC,
            tool = CALL_BADGE_TOOL,
        )
        viewModelScope.launch { badgeSync.markRead(key) }
    }

    /** Wipe the log the user is looking at — Missed does not clear recents behind it. */
    fun clearCallLog() {
        viewModelScope.launch {
            val missed = callFilter.value == CallFilter.MISSED
            if (callLogs.clear(missedOnly = missed)) {
                logs.value = logs.value - missed
                cursors.value = cursors.value - missed
                totals.value = totals.value - missed
                calls.value = emptyList()
            }
        }
    }

    /**
     * Fetch the next page of calls.
     *
     * Called when the list nears its end. Guarded against re-entry because a fling fires the
     * near-the-end check several times before the first page lands, and each one would ask
     * for the same page.
     */
    fun loadMoreCalls() {
        val missed = callFilter.value == CallFilter.MISSED
        if (loadingCalls || cursors.value[missed] == null) return
        fetchCalls(missed, more = true)
    }

    /**
     * Load a filter's log.
     *
     * Cached per filter, so switching Recent ↔ Missed shows what was already fetched
     * immediately and only asks the network the first time. v2 keeps both lists for the same
     * reason; refetching on every switch is what makes the tab feel slow.
     */
    private fun loadCalls() {
        val missed = callFilter.value == CallFilter.MISSED

        // Already loaded: show it now and do not go to the network at all.
        logs.value[missed]?.let { cached ->
            calls.value = cached
            return
        }
        fetchCalls(missed, more = false)
    }

    private fun fetchCalls(missed: Boolean, more: Boolean) {
        loadingCalls = true
        callsLoading.value = true

        viewModelScope.launch {
            val page = callLogs.page(
                before = if (more) cursors.value[missed] else null,
                missedOnly = missed,
            )

            if (!page.failed) {
                val existing = if (more) logs.value[missed].orEmpty() else emptyList()
                // Deduplicated on id: the server's page boundary is inclusive, so the row
                // used as the cursor comes back at the head of the next page.
                page.calls.forEach { call -> call.id?.let { loadedCallsById[it] = call } }

                val merged = (existing + page.calls.mapNotNull { it.toRow() })
                    .distinctBy { it.id }

                logs.value = logs.value + (missed to merged)
                cursors.value = cursors.value + (missed to page.nextCursor?.takeIf { page.hasMore })
                totals.value = totals.value + (missed to page.totalRecords)

                if (missed == (callFilter.value == CallFilter.MISSED)) calls.value = merged
            }

            loadingCalls = false
            callsLoading.value = false
        }
    }

    /** Log rows per filter, kept so switching back is instant. */
    private val logs = MutableStateFlow<Map<Boolean, List<CallRow>>>(emptyMap())

    /** Where the next page starts per filter. Null means there is none. */
    private val cursors = MutableStateFlow<Map<Boolean, Long?>>(emptyMap())

    /** The server's own totals, which is what the chips should show. */
    private val totals = MutableStateFlow<Map<Boolean, Int>>(emptyMap())


    /** The four fold into one to stay inside `combine`'s five-source limit. */
    private data class Badges(
        val unread: Map<String, Int>,
        val calls: List<CallRow>,
        val firstLoad: Boolean,
        val callsLoading: Boolean,
    )

    /** Re-entry guard for [loadMoreCalls]. */
    @Volatile
    private var loadingCalls = false

    /** One log row, named from the directory. */
    // ── A group row's long press ─────────────────────────────────────────────

    private val _rowActions = MutableStateFlow<ConversationRowActions?>(null)
    val rowActions: StateFlow<ConversationRowActions?> = _rowActions.asStateFlow()

    /**
     * Offer the actions for one group row.
     *
     * Only a group, and only one somebody may actually act on: a system-defined room is
     * nobody's to leave or delete, and offering a menu that turns out to be empty is worse
     * than no menu.
     */
    fun openRowActions(conversationId: String) {
        val row = repository.conversationOnce(surface, conversationId) ?: return
        if (!row.isGroup || row.isSystemDefined) return

        val me = session.activeProject.value?.userId
        val admins = row.members.count { it.enabled && it.isGroupAdmin }
        val iAmAdmin = row.members.any { it.userId == me && it.isGroupAdmin }

        _rowActions.value = ConversationRowActions(
            conversationId = conversationId,
            name = row.name,
            // Leaving must not strand the group: the last admin cannot walk out of it.
            canLeave = !iAmAdmin || admins > 1,
            canDelete = row.ownedBy == me,
        )
    }

    fun dismissRowActions() { _rowActions.value = null }

    /** A maps URL for the screen to open. */
    private val _locationRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val locationRequests: SharedFlow<String> = _locationRequests.asSharedFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    /**
     * Show where a contact last was.
     *
     * The same action the profile offers, on the row itself — v2 puts it in both places,
     * because finding somebody on set is usually a glance at the contact list rather than a
     * trip into their profile.
     */
    fun openContactLocation(userId: String) {
        val person = directory.usersOnce(session.activeProject.value?.projectId.orEmpty())
            .firstOrNull { it.userId == userId }

        viewModelScope.launch {
            if (person?.showsLocation != true) {
                _messages.emit(R.string.cnc_location_not_shared)
                return@launch
            }
            if (person.lastLatitude == 0.0 && person.lastLongitude == 0.0) {
                _messages.emit(R.string.cnc_no_location_yet)
                return@launch
            }
            _locationRequests.emit(
                "https://maps.google.com/?q=${person.lastLatitude},${person.lastLongitude}",
            )
        }
    }

    fun leaveGroup(conversationId: String) {
        _rowActions.value = null
        viewModelScope.launch {
            if (!groupRepository.leave(conversationId)) {
                _messages.emit(R.string.something_went_wrong)
            }
        }
    }

    fun deleteGroup(conversationId: String) {
        _rowActions.value = null
        viewModelScope.launch {
            if (!groupRepository.delete(conversationId)) {
                _messages.emit(R.string.something_went_wrong)
            }
        }
    }

    // ── One call's details ───────────────────────────────────────────────────

    /** Non-null while the details sheet is open. */
    private val _callDetails = MutableStateFlow<CallDetails?>(null)
    val callDetails: StateFlow<CallDetails?> = _callDetails.asStateFlow()

    /**
     * Open the details for one call.
     *
     * Read out of the pages already fetched rather than re-requested: the row was drawn
     * from that same data a moment ago, and a second fetch could disagree with what the
     * user just tapped.
     */
    fun openCallDetails(rowId: String) {
        val call = loadedCalls.firstOrNull { it.id == rowId } ?: return
        val projectId = session.activeProject.value?.projectId.orEmpty()
        val me = session.activeProject.value?.userId
        val people = directory.usersOnce(projectId).associateBy { it.userId }

        val isGroup = call.isGroupCall || call.participants.size > 2
        val title = if (isGroup) {
            call.chatRoomName.orEmpty().ifBlank { call.adHocTitle(people) }
        } else {
            people[with(callLogs) { call.otherPartyId() }]?.fullName.orEmpty()
        }

        val rows = call.participants
            .mapNotNull { entry ->
                val id = entry.userId ?: return@mapNotNull null
                val person = people[id]
                val name = person?.fullName?.takeIf { it.isNotBlank() }
                    ?: entry.displayName.orEmpty().ifBlank { id }

                CallParticipant(
                    userId = id,
                    name = if (id == me) "$name (you)" else name,
                    initials = name.initials(),
                    pictureKey = person?.profilePictureUrl,
                    thumbnailKey = person?.profileThumbnailKey,
                    outcome = entry.outcome(),
                    duration = (entry.totalMs ?: 0L).takeIf { it > 0 }?.toDurationLabel().orEmpty(),
                    joinedAt = (entry.answeredAt ?: 0L).takeIf { it > 0 }?.toTimeLabel(),
                )
            }
            // The caller first, then everyone who joined, then the people who did not: the
            // order answers "who was on this call" before "who missed it".
            .sortedBy { it.outcome.ordinal }

        _callDetails.value = CallDetails(
            id = call.id.orEmpty(),
            title = title,
            initials = title.initials(),
            pictureKey = if (isGroup) null else people[with(callLogs) { call.otherPartyId() }]?.profilePictureUrl,
            thumbnailKey = if (isGroup) null else people[with(callLogs) { call.otherPartyId() }]?.profileThumbnailKey,
            direction = when {
                call.missedCall == true -> CallDirection.MISSED
                with(callLogs) { call.isOutgoing() } -> CallDirection.OUTGOING
                else -> CallDirection.INCOMING
            },
            isVideo = call.callType.equals("video", ignoreCase = true),
            isGroup = isGroup,
            startedAt = (call.startTime ?: 0L).toShortDateTimeLabel(),
            duration = (call.callDuration ?: 0L).takeIf { it > 0 }?.toDurationLabel().orEmpty(),
            participants = rows,
        )
    }

    fun dismissCallDetails() { _callDetails.value = null }

    /**
     * How one person's row reads.
     *
     * `missed` alone is not enough: the server marks a declined call as missed too, and
     * "Declined" and "Missed" mean very different things to the person reading the log.
     */
    private fun CallParticipantDto.outcome(): CallOutcome = when {
        status.equals("caller", ignoreCase = true) -> CallOutcome.CALLER
        status.equals("declined", ignoreCase = true) -> CallOutcome.DECLINED
        (answeredAt ?: 0L) > 0 || (totalMs ?: 0L) > 0 -> CallOutcome.JOINED
        missed == true -> CallOutcome.MISSED
        else -> CallOutcome.NO_ANSWER
    }

    /**
     * A title for a group call that was never a group.
     *
     * Calling several people at once creates a room with no name, so the log would show
     * every one of them as the same "Group call" and there would be no way to tell last
     * Tuesday's from this morning's. Naming the first two and counting the rest is what v2
     * settled on, and it stays one line at any group size.
     *
     * The caller comes first, then everyone else in the order the server listed them, so
     * the same call reads the same way on every device.
     */
    private fun CallLogDto.adHocTitle(people: Map<String, ProjectUser>): String {
        val ids = buildList {
            fromUserId?.takeIf { it.isNotBlank() }?.let(::add)
            callUsers.mapNotNull { it.userId }.forEach { if (it.isNotBlank() && it !in this) add(it) }
        }

        val names = ids.mapNotNull { id -> people[id]?.fullName?.takeIf { it.isNotBlank() } }

        return when {
            names.isEmpty() -> GROUP_CALL_FALLBACK
            names.size <= 2 -> names.joinToString(", ")
            else -> "${names[0]}, ${names[1]} and ${names.size - 2} others"
        }
    }

    /**
     * The open project's own row, for the two facts the filters depend on.
     *
     * Read at build time rather than held: it changes only when the project is switched,
     * and switching rebuilds this anyway.
     */
    private fun projectOnce(projectId: String): ProjectEntity? =
        realmProvider.realm.query<ProjectEntity>("projectId == $0", projectId).first().find()

    private fun CallLogDto.toRow(): CallRow? {
        val rowId = id ?: return null
        val people = directory.usersOnce(session.activeProject.value?.projectId.orEmpty())
            .associateBy { it.userId }

        val isGroup = isGroupCall
        // A group call names its room; a one-to-one names the other person, who is whichever
        // end of the row is not this user.
        val otherId = with(callLogs) { otherPartyId() }
        val person = people[otherId]
        val name = if (isGroup) {
            chatRoomName.orEmpty().ifBlank { adHocTitle(people) }
        } else {
            person?.fullName.orEmpty()
        }

        return CallRow(
            id = rowId,
            name = name,
            subtitle = if (isGroup) GROUP_CALL_FALLBACK else person?.designationName.orEmpty(),
            initials = name.initials(),
            pictureKey = person?.profilePictureUrl,
            thumbnailKey = person?.profileThumbnailKey,
            direction = when {
                missedCall == true -> CallDirection.MISSED
                with(callLogs) { isOutgoing() } -> CallDirection.OUTGOING
                else -> CallDirection.INCOMING
            },
            timestamp = (startTime ?: 0L).toShortDateTimeLabel(),
            kind = if (isGroup) ConversationKind.GROUP else ConversationKind.DIRECT,
        )
    }

    private fun build(
        conversations: List<CncConversationEntity>,
        tab: CncTab,
        chatFilter: ChatFilter,
        callFilter: CallFilter,
        query: String,
        unread: Map<String, Int>,
        calls: List<CallRow>,
        loading: Boolean,
        callsLoading: Boolean,
    ): CncUiState {
        val projectId = session.activeProject.value?.projectId.orEmpty()
        val people = directory.usersOnce(projectId).associateBy { it.userId }
        val project = projectOnce(projectId)

        // Two facts decide which chips exist and which rooms belong in the list, and both
        // come off the project rather than the conversation.
        val personalLike = project?.projectTypeId in PROJECT_TYPES_WITHOUT_GROUPS
        val pending = project?.membershipStatus.equals(STATUS_PENDING, ignoreCase = true)

        // Only people and rooms this user can still reach. A removed member keeps their
        // rows so old messages resolve a name, but is not someone to start a chat with.
        val reachable = conversations
            .filter { it.enabled }
            // A room the server made to hold one ad-hoc call is not a group anybody joined.
            .filterNot { it.isRandomCallGroup }
            // A personal project has one implicit room called "General" and no concept of
            // groups; v2 filters it out of the list by name and so does this.
            .filterNot { personalLike && it.isGroup && it.name == GENERAL_ROOM_LABEL }

        val rows = RecentChatOrder.arrange(
            entries = reachable.map { it.toRow(unread[it.conversationId] ?: 0) },
            scope = when (chatFilter) {
                ChatFilter.FAVOURITES -> RecentScope.FAVOURITES
                else -> RecentScope.CHATTED_OR_UNREAD
            },
        )

        val chatRows = rows
            .filter { row ->
                when (chatFilter) {
                    ChatFilter.ALL, ChatFilter.FAVOURITES -> true
                    ChatFilter.UNREAD -> row.unread > 0
                    ChatFilter.MEMBERS -> row.kind == ConversationKind.DIRECT
                    // A department's room is a group, but it is not one anybody created
                    // and it belongs on its own chip. Showing it under both would double
                    // every department in the Groups list.
                    ChatFilter.GROUPS ->
                        row.kind == ConversationKind.GROUP && !row.isDepartmentRoom
                    ChatFilter.DEPARTMENTS ->
                        row.kind == ConversationKind.GROUP && row.isDepartmentRoom
                }
            }
            .filter { it.matches(query) }

        // Contacts is every reachable person, ordered the same way, so the people actually
        // talked to sit at the top instead of being buried alphabetically.
        val contacts = RecentChatOrder
            .arrange(
                reachable.filterNot { it.isGroup }.map { it.toRow(unread[it.conversationId] ?: 0) },
                RecentScope.EVERYONE,
            )
            .map { row ->
                ContactRow(
                    id = row.id,
                    name = row.name,
                    designation = row.subtitle,
                    initials = row.initials,
                    pictureKey = row.pictureKey,
                    thumbnailKey = row.thumbnailKey,
                    deviceId = row.deviceId,
                    online = row.online,
                    isProjectAdmin = row.isProjectAdmin,
                    favourite = row.favourite,
                )
            }
            // Name or job. On a crew of two hundred, "who is the gaffer" is asked far more
            // often than a name nobody remembers, and the designation is right there on the
            // row — searching only names made it look like a typo had hidden someone.
            .filter { contact ->
                query.isBlank() ||
                    contact.name.contains(query, ignoreCase = true) ||
                    contact.designation.contains(query, ignoreCase = true)
            }

        return CncUiState(
            tab = tab,
            chatFilter = chatFilter,
            availableChatFilters = ChatFilter.entries.filter { filter ->
                when (filter) {
                    // Withheld from a member who has not been accepted: they can see the
                    // project but are not in it, so there are no groups to list and nothing
                    // to have starred.
                    ChatFilter.GROUPS -> !pending
                    ChatFilter.FAVOURITES -> !pending && !personalLike
                    // A personal project has no departments at all.
                    ChatFilter.DEPARTMENTS -> !pending && !personalLike
                    else -> true
                }
            },
            callFilter = callFilter,
            query = query,
            conversations = chatRows,
            contacts = contacts,
            // Searched over every page loaded, not only the newest — v2 matches the same
            // way, on the other party's name.
            calls = calls.filter {
                query.isBlank() || it.name.contains(query, ignoreCase = true)
            },
            // The Calls tab has its own fetch, so it waits on that rather than on the
            // conversation list's first load.
            loading = if (tab == CncTab.CALL) callsLoading && calls.isEmpty() else loading,
            unreadTotal = rows.sumOf { it.unread },
            // The server's count, not the number loaded: with one page of 50 and 300 missed
            // calls, counting the page would tell the user 50.
            missedTotal = totals.value[true] ?: calls.count { it.direction == CallDirection.MISSED },
            canCreateGroup = people[session.activeProject.value?.userId]?.isAdmin ?: true,
        )
    }

    private fun ConversationRow.matches(query: String): Boolean =
        query.isBlank() ||
            name.contains(query, ignoreCase = true) ||
            subtitle.contains(query, ignoreCase = true)

    private companion object {
        const val KEY_OPEN_ID = "cnc_open_conversation_id"
        const val KEY_OPEN_IS_GROUP = "cnc_open_conversation_is_group"
        const val KEY_OPEN_CALL = "cnc_open_call_id"

        /** Long enough to survive a rotation without re-running the query. */
        const val STOP_TIMEOUT_MS = 5_000L

        /** Shown for a room call the backend never named. */
        const val GROUP_CALL_FALLBACK = "Group call"

        /** v2's `PERSONAL_PROJECT_KEY` and `OTHER_PROJECT_KEY`. */
        val PROJECT_TYPES_WITHOUT_GROUPS = setOf("personal", "other")

        /** v2's `Constants.PENDING`. */
        const val STATUS_PENDING = "pending"

        /**
         * The implicit room a personal project has instead of groups.
         *
         * Matched on the label key rather than resolved text: the key is the same in every
         * language and the resolved name is not.
         */
        const val GENERAL_ROOM_LABEL = "general_label"

        /** v2's `CALL_LABEL_TOOL` — the badge the Missed list answers. */
        const val CALL_BADGE_TOOL = "call_label"
    }
}

/**
 * Storage row → list row.
 *
 * [unreadCount] comes from the badge tree rather than the row's own column, which is only
 * ever as fresh as the last recent-list fetch.
 */
internal fun CncConversationEntity.toRow(unreadCount: Int): ConversationRow = ConversationRow(
    id = conversationId,
    name = name,
    kind = if (isGroup) ConversationKind.GROUP else ConversationKind.DIRECT,
    subtitle = if (isGroup) "" else designation,
    initials = name.initials(),
    pictureKey = pictureKey.takeIf { it.isNotBlank() },
    thumbnailKey = thumbnailKey.takeIf { it.isNotBlank() },
    deviceId = deviceId.takeIf { it.isNotBlank() },
    lastActivity = lastMessage,
    sortingActivity = sortingActivity,
    unread = unreadCount,
    favourite = favourite,
    isDepartmentRoom = departmentId.isNotBlank(),
    isProjectAdmin = isProjectAdmin,
)

/** Up to two letters, the same rule the avatar uses. */
internal fun String.initials(): String =
    trim().split(Regex("\\s+"))
        .mapNotNull { part -> part.firstOrNull { it.isLetter() }?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }

/** A conversation selected in the list and shown in the pane beside it. */
data class OpenedConversation(val id: String, val isGroup: Boolean)
