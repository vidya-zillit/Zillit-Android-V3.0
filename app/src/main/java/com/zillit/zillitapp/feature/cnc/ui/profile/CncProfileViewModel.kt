package com.zillit.zillitapp.feature.cnc.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.database.entity.CncConversationEntity
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.cnc.data.ChatSurface
import com.zillit.zillitapp.feature.cnc.data.CncGroupRepository
import com.zillit.zillitapp.feature.cnc.data.CncRepository
import com.zillit.zillitapp.feature.cnc.ui.list.initials
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.zillit.zillitapp.feature.cnc.data.CncRoomMemberRequest
import com.zillit.zillitapp.core.storage.MediaLocations
import com.zillit.zillitapp.core.storage.S3Client
import com.zillit.zillitapp.core.storage.StorageCredentialsStore
import com.zillit.zillitapp.core.storage.TransferState
import com.zillit.zillitapp.core.storage.UploadRequest
import com.zillit.zillitapp.feature.cnc.data.CncAttachmentDto
import kotlinx.coroutines.flow.first
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.ProjectEntity
import io.realm.kotlin.ext.query
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The details screen behind a conversation — a person's or a room's.
 *
 * One view model for both because the two screens are reached the same way, from the same
 * header, and differ only in which half of the state they read. Splitting them would mean
 * duplicating the storage read and the block handling.
 */
@HiltViewModel
class CncProfileViewModel @Inject constructor(
    private val repository: CncRepository,
    private val groups: CncGroupRepository,
    private val directory: ProjectDirectory,
    private val session: SessionStore,
    private val realmProvider: RealmProvider,
    private val s3: S3Client,
    private val storage: StorageCredentialsStore,
    private val mediaLocations: MediaLocations,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val surface = ChatSurface.CNC

    /** Whichever of the two arguments this screen was opened with. */
    private val conversationId: String =
        savedState["userId"] ?: savedState["roomId"] ?: ""

    private val projectId: String get() = session.activeProject.value?.projectId.orEmpty()

    /** Exposed so the member menu is never offered on yourself. */
    val currentUserId: String get() = session.activeProject.value?.userId.orEmpty()

    private val row: StateFlow<CncConversationEntity?> =
        repository.observeConversation(surface, conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val person: StateFlow<PersonProfile> = row
        .map { entity -> buildPerson(entity) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            buildPerson(null),
        )

    val group: StateFlow<GroupProfile> = row
        .map { entity -> buildGroup(entity) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            buildGroup(null),
        )

    /** Whether this user is still waiting to be accepted into the project. */
    private val projectIsPending: Boolean
        get() = realmProvider.realm
            .query<ProjectEntity>("projectId == $0", projectId)
            .first()
            .find()
            ?.membershipStatus
            .equals("pending", ignoreCase = true)

    /** A maps URL for the screen to open. */
    private val _locationRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val locationRequests: SharedFlow<String> = _locationRequests.asSharedFlow()

    /** Told to the screen when there is nothing to show yet, so it can say so. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    /**
     * Show where this person last was.
     *
     * v2 does one of two things here: opens the position if there is one, and otherwise
     * asks them to turn tracking on by posting a message into the conversation. The second
     * half is why this is not simply a disabled button — "I cannot see you" is a request,
     * not an error.
     */
    fun openLocation() {
        val person = directory.usersOnce(projectId).firstOrNull { it.userId == conversationId }
        val lat = person?.lastLatitude ?: 0.0
        val long = person?.lastLongitude ?: 0.0

        viewModelScope.launch {
            if (lat == 0.0 && long == 0.0) {
                _messages.emit(R.string.cnc_no_location_yet)
                return@launch
            }
            _locationRequests.emit("https://maps.google.com/?q=$lat,$long")
        }
    }

    fun toggleBlock() {
        viewModelScope.launch {
            report(
                repository.toggleBlock(
                    surface,
                    conversationId,
                    block = row.value?.blockedByMe != true,
                ),
            )
        }
    }

    /**
     * Says so when a write did not take.
     *
     * Every administration action on this screen used to discard its result: a rename the
     * server refused closed the dialog with the old name still showing, a removal left the
     * person in the list, and neither explained itself. Silence on success is right — the
     * list changing is the confirmation — but silence on failure is indistinguishable from
     * a slow connection.
     */
    private suspend fun report(ok: Boolean) {
        if (!ok) _messages.emit(R.string.something_went_wrong)
    }

    /**
     * Rename the group.
     *
     * Blank names are refused here rather than by the server: an empty room name leaves
     * every member looking at an unnamed row, and the round trip is wasted.
     */
    fun renameGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { report(groups.rename(conversationId, trimmed)) }
    }

    /**
     * Grant or revoke a member's admin rights.
     *
     * Never on yourself. Removing your own admin rights in a group you are the only admin
     * of leaves it with nobody who can change it, and the server will not undo that.
     */
    fun setMemberAdmin(userId: String, isAdmin: Boolean) {
        if (userId == session.activeProject.value?.userId) return
        viewModelScope.launch { report(groups.setAdmin(conversationId, userId, isAdmin)) }
    }

    /**
     * Remove someone from the group.
     *
     * The endpoint takes the whole membership rather than a delta, so the list is rebuilt
     * from what is stored minus this person. Rebuilding it from storage rather than from
     * the screen's copy matters: the screen may have been open while somebody else was
     * added, and posting its stale list would silently remove them too.
     */
    fun removeMember(userId: String) {
        if (userId == session.activeProject.value?.userId) return

        viewModelScope.launch {
            val remaining = row.value?.members.orEmpty()
                .filter { it.enabled && it.userId != userId }
                .map { CncRoomMemberRequest(userId = it.userId, isGroupAdmin = it.isGroupAdmin) }

            if (remaining.isNotEmpty()) report(groups.setMembers(conversationId, remaining))
        }
    }

    /**
     * Upload a new group photo and record it.
     *
     * Two steps that must not be split: the bytes go to S3 first, and only a completed
     * upload is announced to the server. Announcing first would leave every other member
     * pointing at a key that 404s, which looks exactly like a deleted photo.
     *
     * The key layout follows the one the rest of the app uses, because the backend's
     * lifecycle rules and the web client's download paths both assume it.
     */
    fun changeGroupPhoto(localPath: String, fileName: String, mimeType: String) {
        viewModelScope.launch {
            val project = session.activeProject.value ?: return@launch
            val credentials = storage.credentials.value

            val remoteKey =
                "${project.projectId}/chat-room/picture/Zillit_${System.currentTimeMillis()}_$fileName"

            val terminal = s3.upload(
                UploadRequest(
                    remoteKey = remoteKey,
                    localPath = localPath,
                    fileName = fileName,
                    module = ChatSurface.CNC.key,
                    contentType = mimeType,
                ),
            ).first { it is TransferState.Complete || it is TransferState.Failed }

            if (terminal !is TransferState.Complete) {
                report(false)
                return@launch
            }

            val ok = groups.updatePicture(
                roomId = conversationId,
                picture = CncAttachmentDto(
                    media = terminal.remoteKey,
                    // The thumbnail is the same object: the server generates a smaller copy
                    // asynchronously and replaces this, and until it does the full image is
                    // better than a blank circle.
                    thumbnail = terminal.remoteKey,
                    name = fileName,
                    contentType = "image",
                    bucket = credentials.uploadBucket,
                    region = credentials.region,
                ),
            )

            report(ok)

            // Where the file lives, so the avatar loads before the next room refresh
            // rewrites the row from the server.
            if (ok) {
                mediaLocations.remember(
                    media = terminal.remoteKey,
                    thumbnail = terminal.remoteKey,
                    bucket = credentials.uploadBucket,
                    region = credentials.region,
                )
            }
        }
    }

    fun leaveGroup(onDone: () -> Unit) {
        viewModelScope.launch {
            if (groups.leave(conversationId)) onDone() else report(false)
        }
    }

    /**
     * Delete the group for everyone.
     *
     * Offered only to the owner. Leaving and deleting sit next to each other and do very
     * different things, which is why the screen asks before either.
     */
    fun deleteGroup(onDone: () -> Unit) {
        viewModelScope.launch {
            if (groups.delete(conversationId)) onDone() else report(false)
        }
    }

    /**
     * Counted from storage on every rebuild rather than held.
     *
     * The row it feeds changes whenever a file arrives, and the query is a filter over one
     * conversation — cheaper than keeping a second subscription in step with it.
     */
    private val counts get() = repository.sharedCounts(surface, conversationId)

    private fun buildPerson(entity: CncConversationEntity?): PersonProfile {
        val person = directory.usersOnce(projectId).firstOrNull { it.userId == conversationId }
        val name = person?.fullName ?: entity?.name.orEmpty()

        return PersonProfile(
            id = conversationId,
            name = name,
            designation = person?.designationName.orEmpty(),
            department = person?.departmentName.orEmpty(),
            initials = name.initials(),
            pictureKey = person?.profilePictureUrl ?: entity?.pictureKey?.takeIf { it.isNotBlank() },
            thumbnailKey = person?.profileThumbnailKey
                ?: entity?.thumbnailKey?.takeIf { it.isNotBlank() },
            // Presence belongs to the thread header, which already observes it. Repeating
            // the subscription here would mean two listeners per person on one screen.
            online = false,
            blocked = entity?.blockedByMe == true,
            // Offered only when they share it, and never to a member still pending: v2
            // applies both conditions before the control appears at all.
            canSeeLocation = person?.showsLocation == true && !projectIsPending,
            mediaCount = counts.media,
            fileCount = counts.files,
        )
    }

    private fun buildGroup(entity: CncConversationEntity?): GroupProfile {
        val people = directory.usersOnce(projectId).associateBy { it.userId }
        val me = session.activeProject.value?.userId

        val members = entity?.members.orEmpty()
            .filter { it.enabled }
            .map { member ->
                val person = people[member.userId]
                val name = person?.fullName?.takeIf { it.isNotBlank() } ?: member.name
                GroupMemberRow(
                    id = member.userId,
                    name = name,
                    designation = person?.designationName.orEmpty(),
                    initials = name.initials(),
                    pictureKey = person?.profilePictureUrl
                        ?: member.pictureKey.takeIf { it.isNotBlank() },
                    thumbnailKey = person?.profileThumbnailKey
                        ?: member.thumbnailKey.takeIf { it.isNotBlank() },
                    isGroupAdmin = member.isGroupAdmin,
                )
            }
            // Admins first, then alphabetical: the people who can act on the group are the
            // ones a member is usually looking for.
            .sortedWith(compareByDescending<GroupMemberRow> { it.isGroupAdmin }.thenBy { it.name.lowercase() })

        return GroupProfile(
            id = conversationId,
            name = entity?.name.orEmpty(),
            initials = entity?.name.orEmpty().initials(),
            pictureKey = entity?.pictureKey?.takeIf { it.isNotBlank() },
            thumbnailKey = entity?.thumbnailKey?.takeIf { it.isNotBlank() },
            members = members,
            // The owner and any group admin may administer it; a system-defined room is
            // nobody's to change.
            canAdminister = entity?.isSystemDefined != true &&
                (entity?.ownedBy == me || members.any { it.id == me && it.isGroupAdmin }),
            // Deleting is the owner's alone — a group admin may add and remove people, but
            // not remove the room from under everyone else.
            canDelete = entity?.isSystemDefined != true && entity?.ownedBy == me,
            mediaCount = counts.media,
            fileCount = counts.files,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
