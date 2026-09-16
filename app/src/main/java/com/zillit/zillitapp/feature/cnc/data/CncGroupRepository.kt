package com.zillit.zillitapp.feature.cnc.data

import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.session.SessionStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Group lifecycle: create, rename, membership, admins, leaving.
 *
 * Separate from [CncRepository] because none of this is about messages. The room endpoints
 * are REST — only the *notification* that a room changed comes over the socket — so keeping
 * them apart also keeps the socket layer free of calls that never touch it.
 *
 * Every write re-merges the room list afterwards rather than patching storage by hand: the
 * server decides membership and admin rights, and a local guess that disagrees with it is
 * worse than a round trip.
 */
@Singleton
class CncGroupRepository @Inject constructor(
    private val api: ZillitApi,
    private val cncDirectory: CncDirectory,
    private val session: SessionStore,
) {

    /** @return the new room's id, or null when the create failed. */
    suspend fun create(name: String, memberIds: List<String>): String? {
        val project = session.activeProject.value ?: return null

        // The creator is always a member and always an admin. v2 relies on the caller to
        // include themselves, which is why a group created from its own screen occasionally
        // appears to someone else and not to its author.
        val members = (memberIds + project.userId).distinct().map { userId ->
            CncRoomMemberRequest(
                userId = userId,
                isGroupAdmin = userId == project.userId,
            )
        }

        val result = api.post<CncCreateRoomRequest, CncRoomResponse>(
            url = ChatSurface.CNC.rooms,
            body = CncCreateRoomRequest(
                roomName = name,
                projectId = project.projectId,
                ownedBy = project.userId,
                members = members,
            ),
            module = ModuleData.WITH_PROJECT_USER_ID,
        )

        if (!result.succeeded("create")) return null
        // The id is what a photo attaches to, and it exists only once the server has made
        // the room. Returning it is what lets the two steps stay in one action.
        return (result as? ApiResult.Success)?.data?.data?.chatRoom?.id
    }

    suspend fun rename(roomId: String, name: String): Boolean =
        api.put<CncUpdateRoomRequest, CncRoomResponse>(
            url = ChatSurface.CNC.rooms,
            body = CncUpdateRoomRequest(roomId = roomId, roomName = name),
            module = ModuleData.WITH_PROJECT_USER_ID,
        ).succeeded("rename")

    /** Replace the whole member list. The endpoint takes the set, not a delta. */
    suspend fun setMembers(roomId: String, members: List<CncRoomMemberRequest>): Boolean =
        api.put<CncUpdateRoomRequest, CncRoomResponse>(
            url = ChatSurface.CNC.rooms,
            body = CncUpdateRoomRequest(roomId = roomId, members = members),
            module = ModuleData.WITH_PROJECT_USER_ID,
        ).succeeded("members")

    suspend fun setAdmin(roomId: String, userId: String, isAdmin: Boolean): Boolean =
        api.post<CncEditAdminRequest, CncRoomResponse>(
            url = ChatSurface.CNC.roomEditAdmin,
            body = CncEditAdminRequest(roomId = roomId, userId = userId, isGroupAdmin = isAdmin),
            module = ModuleData.WITH_PROJECT_USER_ID,
        ).succeeded("admin")

    /**
     * Delete a group outright.
     *
     * Different from leaving: leaving removes **you**, deleting removes the room for
     * everybody. Only the owner should be offered it, which is the screen's decision —
     * enforcing it here as well would just be a second place to get it wrong.
     */
    suspend fun delete(roomId: String): Boolean =
        api.delete<CncRoomResponse>(
            url = "${ChatSurface.CNC.rooms}/$roomId",
            module = ModuleData.WITH_PROJECT_USER_ID,
        ).succeeded("delete")

    /**
     * Replace a group's photo.
     *
     * The room id is in the path and the media object is the body, matching v2. The file
     * itself is already in S3 by the time this is called — the caller uploads, this records
     * where it landed.
     */
    suspend fun updatePicture(roomId: String, picture: CncAttachmentDto): Boolean =
        api.put<CncAttachmentDto, CncRoomResponse>(
            url = "${ChatSurface.CNC.roomPicture}/$roomId",
            body = picture,
            module = ModuleData.WITH_PROJECT_USER_ID,
        ).succeeded("picture")

    suspend fun leave(roomId: String): Boolean {
        val project = session.activeProject.value ?: return false
        return api.post<CncLeaveRoomRequest, CncRoomResponse>(
            url = ChatSurface.CNC.roomLeave,
            body = CncLeaveRoomRequest(roomId = roomId, userId = project.userId),
            module = ModuleData.WITH_PROJECT_USER_ID,
        ).succeeded("leave")
    }

    /** Refreshes the room list on success, so storage reflects the server's decision. */
    private suspend fun ApiResult<CncRoomResponse>.succeeded(what: String): Boolean {
        val ok = this is ApiResult.Success && data.ok
        if (ok) cncDirectory.refreshRooms() else ZillitLog.w(TAG, "room $what failed")
        return ok
    }

    private companion object {
        const val TAG = "CncGroupRepository"
    }
}

@Serializable
data class CncCreateRoomRequest(
    @SerialName("room_name") val roomName: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("owned_by") val ownedBy: String,
    @SerialName("room_tool") val roomTool: String = "cnc_section",
    val members: List<CncRoomMemberRequest>,
)

@Serializable
data class CncUpdateRoomRequest(
    @SerialName("_id") val roomId: String,
    @SerialName("room_name") val roomName: String? = null,
    val members: List<CncRoomMemberRequest>? = null,
)

@Serializable
data class CncRoomMemberRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("chat_group_admin") val isGroupAdmin: Boolean = false,
    val enabled: Boolean = true,
)

@Serializable
data class CncEditAdminRequest(
    @SerialName("room_id") val roomId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("chat_group_admin") val isGroupAdmin: Boolean,
)

@Serializable
data class CncLeaveRoomRequest(
    @SerialName("room_id") val roomId: String,
    @SerialName("user_id") val userId: String,
)

/**
 * A single-room write, in the module's envelope.
 *
 * `data.chat_room`, and `status == 1` for success — the same shape the list uses, one room
 * deep instead of an array.
 */
@Serializable
data class CncRoomResponse(
    val status: Int? = null,
    val message: String? = null,
    val data: CncRoomData? = null,
) {
    val ok: Boolean get() = status == STATUS_OK

    private companion object {
        const val STATUS_OK = 1
    }
}

@Serializable
data class CncRoomData(
    @SerialName("chat_room") val chatRoom: CncRoomDto? = null,
)
