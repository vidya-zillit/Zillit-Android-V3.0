package com.zillit.zillitapp.feature.cnc.data

import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.CncConversationEntity
import com.zillit.zillitapp.core.database.entity.CncMemberEntity
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.storage.MediaLocations
import io.realm.kotlin.ext.query
import io.realm.kotlin.ext.toRealmList
import com.zillit.zillitapp.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gives conversation rows their names, pictures and device ids.
 *
 * ### Why this exists separately from the repository
 * The recent-list endpoint returns **ids and order keys and nothing else** — no name, no
 * photo, no designation, no device id. Everything a row actually displays comes from the
 * project directory, which the bootstrapper has already fetched for its own reasons.
 *
 * Keeping the merge here rather than inside `CncRepository` means the repository stays about
 * messages, and it means the merge can be re-run on its own whenever the directory changes
 * — a person renamed, or a new member added — without touching the message store.
 *
 * Rooms are different: they have no directory entry, so their names and members come from
 * the chat-room endpoint. Both land in the same table, because the Chat tab sorts people and
 * rooms against each other and cannot do that across two.
 */
@Singleton
class CncDirectory @Inject constructor(
    private val api: ZillitApi,
    private val directory: ProjectDirectory,
    private val realmProvider: RealmProvider,
    private val session: SessionStore,
    private val repository: CncRepository,
    private val mediaLocations: MediaLocations,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val realm get() = realmProvider.realm

    /** Rebuild every conversation row from the directory and the room list. */
    fun refresh() {
        scope.launch {
            // One guard per step, not one around all five. The rooms endpoint failing is
            // no reason to skip the block list, and a shared guard made it one — the
            // conversation list would come back without blocks, without ordering and
            // without the recent list, and the log would blame whichever step threw.
            step("people") { mergePeople() }
            step("rooms") { refreshRooms() }
            // Blocks are server state with no local source of truth. Without this the
            // banner is only ever right for a block made on this device this session.
            step("blocks") { repository.refreshBlocks(ChatSurface.CNC) }
            // Which conversations exist, and in what order. Done here as well as when
            // the tab opens, so the list is ready before the user gets there.
            step("ordering") { repository.syncOrderFromMessages(ChatSurface.CNC) }
            step("recent list") { repository.refreshRecent(ChatSurface.CNC) }
        }
    }

    /** Runs one piece of the refresh, and lets the rest continue if it fails. */
    private suspend fun step(what: String, block: suspend () -> Unit) {
        runCatching { block() }
            .onFailure { ZillitLog.w(TAG, "directory $what failed: ${it.message}") }
    }

    /**
     * Fold the project's people into the conversation table.
     *
     * Every member gets a row, not only the ones already talked to: the Contacts tab lists
     * everyone, and a row with `sortingActivity == 0` is exactly what
     * [RecentChatOrder] filters out of the Chat tab. One table, two questions.
     */
    suspend fun mergePeople() {
        val project = session.activeProject.value ?: return
        val people = directory.usersOnce(project.projectId)
        if (people.isEmpty()) return

        realm.write {
            people.forEach { person ->
                // Never list yourself as someone to message.
                if (person.userId == project.userId) return@forEach

                val key = CncConversationEntity.key(
                    ChatSurface.CNC.key,
                    project.projectId,
                    person.userId,
                )
                val row = query<CncConversationEntity>("id == $0", key).first().find()
                    ?: copyToRealm(
                        CncConversationEntity().apply {
                            id = key
                            surface = ChatSurface.CNC.key
                            projectId = project.projectId
                            conversationId = person.userId
                            isGroup = false
                        },
                    )

                row.name = person.fullName
                row.designation = person.designationName.orEmpty()
                row.pictureKey = person.profilePictureUrl.orEmpty()
                row.thumbnailKey = person.profileThumbnailKey.orEmpty()
                row.deviceId = person.deviceId
                row.isProjectAdmin = person.isAdmin
                // The order key the Chat tab sorts on. Only ever raised: a message received
                // since the last directory fetch is newer than anything the server knew.
                if (person.sortingActivity > row.sortingActivity) {
                    row.sortingActivity = person.sortingActivity
                }
                // A member who left keeps their row so old messages still resolve a name,
                // but stops being someone you can start a conversation with.
                row.enabled = person.enabled
            }
        }

        ZillitLog.d(TAG, "merged ${people.size} person(s) into conversations")
    }

    /** Fetch the rooms this user belongs to and fold them into the same table. */
    suspend fun refreshRooms(): Boolean {
        val project = session.activeProject.value ?: return false

        val result = api.get<CncRoomsResponse>(
            url = ChatSurface.CNC.rooms,
            module = ModuleData.WITH_PROJECT_USER_ID,
        )
        if (result !is ApiResult.Success) return false

        val rooms = result.data.rooms

        realm.write {
            rooms.forEach { room ->
                val roomId = room.id ?: return@forEach
                val key = CncConversationEntity.key(
                    ChatSurface.CNC.key,
                    project.projectId,
                    roomId,
                )
                val row = query<CncConversationEntity>("id == $0", key).first().find()
                    ?: copyToRealm(
                        CncConversationEntity().apply {
                            id = key
                            surface = ChatSurface.CNC.key
                            projectId = project.projectId
                            conversationId = roomId
                            isGroup = true
                        },
                    )

                row.name = room.roomName.orEmpty()
                row.departmentId = room.departmentId.orEmpty()
                row.isRandomCallGroup = room.isRandomCallGroup == true
                row.pictureKey = room.groupPicture?.media.orEmpty()
                row.thumbnailKey = room.groupPicture?.thumbnail.orEmpty()
                mediaLocations.remember(
                    media = room.groupPicture?.media,
                    thumbnail = room.groupPicture?.thumbnail,
                    bucket = room.groupPicture?.bucket,
                    region = room.groupPicture?.region,
                )
                row.ownedBy = room.ownedBy.orEmpty()
                row.isSystemDefined = room.isSystemDefined == true

                // Whether *this* user is still in the room, which is what decides if the
                // composer is offered. A removed member keeps the history.
                row.enabled = room.members.any { it.userId == project.userId && it.enabled != false }

                val incoming = room.sortingActivity ?: room.lastMessagePostedOn ?: 0
                if (incoming > row.sortingActivity) row.sortingActivity = incoming

                row.members = room.members.map { member ->
                    CncMemberEntity().apply {
                        userId = member.userId.orEmpty()
                        name = member.fullName.orEmpty()
                        pictureKey = member.attachment?.media.orEmpty()
                        thumbnailKey = member.attachment?.thumbnail.orEmpty()
                        isGroupAdmin = member.isGroupAdmin == true
                        enabled = member.enabled != false
                    }
                }.toRealmList()
            }
        }

        ZillitLog.d(TAG, "merged ${rooms.size} room(s)")
        return true
    }

    private companion object {
        const val TAG = "CncDirectory"
    }
}

/**
 * The C&C envelope, which every endpoint in this module uses.
 *
 * `{status, message, messageElements, data}` — **not** `{success, data}`, which is what an
 * earlier version of this file guessed. `status == 1` is success, and `data` is an object
 * wrapping a **named** array rather than the array itself. Getting this wrong does not fail
 * loudly: the decode throws, the call is reported as a parse error, and the screen is simply
 * empty.
 */
@Serializable
data class CncRoomsResponse(
    val status: Int? = null,
    val message: String? = null,
    val data: CncRoomsData? = null,
) {
    val rooms: List<CncRoomDto> get() = data?.chatRooms.orEmpty()
}

@Serializable
data class CncRoomsData(
    @SerialName("chat_rooms") val chatRooms: List<CncRoomDto> = emptyList(),
)

/** A chat room, as `chat-room` returns it. */
@Serializable
data class CncRoomDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("room_name") val roomName: String? = null,
    @SerialName("group_picture") val groupPicture: CncAttachmentDto? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
    @SerialName("is_system_defined") val isSystemDefined: Boolean? = null,
    @SerialName("is_random_call_group") val isRandomCallGroup: Boolean? = null,
    /** A room the calendar created for an event's call. */
    @SerialName("is_calendar_call_group") val isCalendarCallGroup: Boolean? = null,
    @SerialName("room_tool") val roomTool: String? = null,
    /** Shareable link for a guest to join this room's call. */
    @SerialName("guest_invite_link") val guestInviteLink: String? = null,
    @SerialName("guest_invite_code") val guestInviteCode: String? = null,
    val created: Long? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("budget_document_id") val budgetDocumentId: String? = null,
    @SerialName("sorting_activity") val sortingActivity: Long? = null,
    @SerialName("last_message_posted_on") val lastMessagePostedOn: Long? = null,
    val enabled: Boolean? = null,
    val updated: Long? = null,
    val members: List<CncRoomMemberDto> = emptyList(),
)

/**
 * One member, as the room list returns it.
 *
 * **No name and no picture.** The payload carries ids and flags only, so everything shown
 * about a member is resolved from the project directory. [fullName] is kept because some
 * responses do include it, but nothing may depend on it being there.
 */
@Serializable
data class CncRoomMemberDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("chat_group_admin") val isGroupAdmin: Boolean? = null,
    val enabled: Boolean? = null,
    @SerialName("enabled_in_project") val enabledInProject: Boolean? = null,
    val attachment: CncAttachmentDto? = null,
)
