package com.zillit.zillitapp.feature.cnc.ui.group

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.cnc.data.CncGroupRepository
import com.zillit.zillitapp.feature.cnc.ui.list.ContactRow
import com.zillit.zillitapp.feature.cnc.ui.list.initials
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.lifecycle.SavedStateHandle
import com.zillit.zillitapp.feature.cnc.data.ChatSurface
import com.zillit.zillitapp.feature.cnc.data.CncRepository
import com.zillit.zillitapp.feature.cnc.data.CncRoomMemberRequest
import com.zillit.zillitapp.core.storage.S3Client
import com.zillit.zillitapp.core.storage.StorageCredentialsStore
import com.zillit.zillitapp.core.storage.TransferState
import com.zillit.zillitapp.core.storage.UploadRequest
import com.zillit.zillitapp.feature.cnc.data.CncAttachmentDto
import kotlinx.coroutines.flow.first
import com.zillit.zillitapp.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Creating a group.
 *
 * The draft is held here rather than in the screen so it survives a rotation and a trip to
 * the photo picker. The candidate list is the project directory, filtered live — there is no
 * separate "who can I add" endpoint, and asking for one would just be the directory again.
 */
@HiltViewModel
class CncGroupViewModel @Inject constructor(
    private val groups: CncGroupRepository,
    private val directory: ProjectDirectory,
    private val repository: CncRepository,
    private val session: SessionStore,
    private val s3: S3Client,
    private val storage: StorageCredentialsStore,
    savedState: SavedStateHandle,
) : ViewModel() {

    /**
     * The room being added to, or blank when this is a new group.
     *
     * The same screen serves both: picking people out of the project is the whole of it,
     * and the only differences are that an existing room already has a name and already has
     * members. A second screen would have been the same list with one field hidden.
     */
    private val roomId: String = savedState["roomId"] ?: ""

    private val isAddingToExisting: Boolean get() = roomId.isNotBlank()

    private val _createState = MutableStateFlow(CreateGroupUiState())
    val createState: StateFlow<CreateGroupUiState> = _createState.asStateFlow()

    init {
        _createState.update { it.copy(isAddingToExistingGroup = isAddingToExisting) }
        refreshCandidates()
    }

    /** Told to the screen when a write did not take. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun onNameChange(value: String) = _createState.update { it.copy(name = value) }

    /** The picked photo, held until there is a room to attach it to. */
    fun onPhotoPicked(localPath: String, fileName: String, mimeType: String) {
        _createState.update { it.copy(photo = PickedPhoto(localPath, fileName, mimeType)) }
    }

    private suspend fun uploadPhoto(roomId: String, photo: PickedPhoto) {
        val project = session.activeProject.value ?: return
        val credentials = storage.credentials.value

        val remoteKey =
            "${project.projectId}/chat-room/picture/Zillit_${System.currentTimeMillis()}_${photo.fileName}"

        val terminal = s3.upload(
            UploadRequest(
                remoteKey = remoteKey,
                localPath = photo.localPath,
                fileName = photo.fileName,
                module = ChatSurface.CNC.key,
                contentType = photo.mimeType,
            ),
        ).first { it is TransferState.Complete || it is TransferState.Failed }

        if (terminal !is TransferState.Complete) return

        groups.updatePicture(
            roomId = roomId,
            picture = CncAttachmentDto(
                media = terminal.remoteKey,
                thumbnail = terminal.remoteKey,
                name = photo.fileName,
                contentType = "image",
                bucket = credentials.uploadBucket,
                region = credentials.region,
            ),
        )
    }

    fun onQueryChange(value: String) {
        _createState.update { it.copy(query = value) }
        refreshCandidates()
    }

    fun onToggleMember(contact: ContactRow) {
        _createState.update { state ->
            val selected = if (state.selected.any { it.id == contact.id }) {
                state.selected.filterNot { it.id == contact.id }
            } else {
                state.selected + contact
            }
            state.copy(selected = selected)
        }
    }

    fun createGroup(onDone: () -> Unit) {
        val state = _createState.value
        if (state.selected.isEmpty() || state.saving) return
        // A new group needs a name; one that already exists has one.
        if (!isAddingToExisting && state.name.isBlank()) return

        _createState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val ok = if (isAddingToExisting) {
                addToExistingGroup(state)
            } else {
                val newRoomId = groups.create(
                    name = state.name.trim(),
                    memberIds = state.selected.map { it.id },
                )
                // The photo can only be attached once the room exists, so it waits for the
                // create rather than being uploaded alongside it. A failed photo does not
                // fail the group: the group is made and the picture can be set again.
                newRoomId?.also { id -> state.photo?.let { uploadPhoto(id, it) } } != null
            }
            _createState.update { it.copy(saving = false) }
            // Only leave on success. A failed create that closed the screen would throw
            // away a name and a dozen picked people with nothing to show for it. Staying
            // put is half the answer; the other half is saying why nothing happened.
            if (ok) onDone() else _messages.emit(R.string.something_went_wrong)
        }
    }

    /**
     * Add the picked people to a room that already exists.
     *
     * The endpoint takes the whole membership rather than a delta, so the people already in
     * the room are read from storage and posted with the new ones. Their admin rights are
     * carried across: rebuilding the list without them would quietly demote every admin in
     * the group.
     */
    private suspend fun addToExistingGroup(state: CreateGroupUiState): Boolean {
        val existing = repository.conversationOnce(ChatSurface.CNC, roomId)
            ?.members.orEmpty()
            .filter { it.enabled }
            .map { CncRoomMemberRequest(userId = it.userId, isGroupAdmin = it.isGroupAdmin) }

        val added = state.selected
            .filterNot { picked -> existing.any { it.userId == picked.id } }
            .map { CncRoomMemberRequest(userId = it.id) }

        if (added.isEmpty()) return true
        return groups.setMembers(roomId, existing + added)
    }

    private fun refreshCandidates() {
        val project = session.activeProject.value ?: return
        val query = _createState.value.query

        // Already in the room, so not offered again. Listing them would let someone "add" a
        // person who is already there, which posts an unchanged membership and looks broken.
        val alreadyIn = if (isAddingToExisting) {
            repository.conversationOnce(ChatSurface.CNC, roomId)
                ?.members.orEmpty()
                .filter { it.enabled }
                .map { it.userId }
                .toSet()
        } else {
            emptySet()
        }

        val candidates = directory.usersOnce(project.projectId)
            .asSequence()
            .filter { it.userId != project.userId }
            .filter { it.userId !in alreadyIn }
            .filter { it.enabled }
            .filter { query.isBlank() || it.fullName.contains(query, ignoreCase = true) }
            .map { person ->
                ContactRow(
                    id = person.userId,
                    name = person.fullName,
                    designation = person.designationName.orEmpty(),
                    initials = person.fullName.initials(),
                    pictureKey = person.profilePictureUrl,
                    thumbnailKey = person.profileThumbnailKey,
                    deviceId = person.deviceId.takeIf { it.isNotBlank() },
                    isProjectAdmin = person.isAdmin,
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()

        _createState.update { it.copy(candidates = candidates) }
    }
}

@Immutable
data class CreateGroupUiState(
    val name: String = "",
    val query: String = "",
    val candidates: List<ContactRow> = emptyList(),
    val selected: List<ContactRow> = emptyList(),
    val saving: Boolean = false,
    /** Hides the name field and changes the confirm button's wording. */
    val isAddingToExistingGroup: Boolean = false,
    /** Chosen before the group exists, uploaded once it does. */
    val photo: PickedPhoto? = null,
)

/** A picture picked for a group that has not been created yet. */
data class PickedPhoto(
    val localPath: String,
    val fileName: String,
    val mimeType: String,
)
