package com.zillit.zillitapp.feature.email.data

import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.feature.email.domain.RuleDriveFolder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Just enough of Drive for a rule to say where attachments go.
 *
 * Deliberately **not** a Drive repository: the Drive tool has not been built in v3 yet, and
 * a thin client for one screen is easier to delete than a half-built module is to finish.
 * When Drive lands, this collapses into it — the endpoint and shapes are already its own.
 */
@Singleton
class DriveFolderApi @Inject constructor(
    private val api: ZillitApi,
) {

    /**
     * One level of the folder tree.
     *
     * At the root the [section] selects between the user's own Drive and what has been
     * shared with them; inside a folder the children are already scoped by their parent, so
     * no filter is sent — the same rule Drive's own pickers follow.
     *
     * The response is filtered to the requested level because the API may return more than
     * was asked for, and to non-deleted folders because a trashed one is not a destination.
     */
    suspend fun folders(
        parentId: String?,
        section: DriveSectionFilter,
    ): ApiResult<List<RuleDriveFolder>> {
        val query = buildMap {
            put("limit", PAGE_LIMIT.toString())
            if (parentId != null) {
                put("parent_folder_id", parentId)
            } else {
                put("quick_filter", section.apiValue)
            }
        }

        return when (val result = api.get<DriveEnvelope<PaginatedFoldersDto>>(
            ApiEndpoints.Drive.FOLDERS,
            query = query,
        )) {
            is ApiResult.Failure -> result

            is ApiResult.Success -> ApiResult.Success(
                result.data.data?.items.orEmpty()
                    .filter { it.deletedOn == 0L }
                    .filter { it.parentFolderId == parentId }
                    .sortedBy { it.folderName.lowercase() }
                    .map { it.toDomain() },
            )
        }
    }

    /** Creates a folder inside [parentId], or at the root when it is null. */
    suspend fun createFolder(name: String, parentId: String?): ApiResult<RuleDriveFolder> =
        when (val result = api.post<CreateDriveFolderRequest, DriveEnvelope<DriveFolderDto>>(
            ApiEndpoints.Drive.FOLDERS,
            CreateDriveFolderRequest(
                folderName = name,
                parentFolderId = parentId,
            ),
        )) {
            is ApiResult.Failure -> result

            is ApiResult.Success -> result.data.data
                ?.let { ApiResult.Success(it.toDomain()) }
                ?: ApiResult.Failure(
                    com.zillit.zillitapp.core.network.ApiError.Parsing("empty folder payload"),
                )
        }

    private companion object {
        const val PAGE_LIMIT = 200
    }
}

/** Which part of Drive is being browsed. */
enum class DriveSectionFilter(val apiValue: String) {
    MINE("mine"),
    SHARED("shared"),
}

@Serializable
data class DriveEnvelope<T>(
    @SerialName("status") val status: Int = 0,
    @SerialName("message") val message: String = "",
    @SerialName("data") val data: T? = null,
)

@Serializable
data class PaginatedFoldersDto(
    @SerialName("items") val items: List<DriveFolderDto> = emptyList(),
)

@Serializable
data class DriveFolderDto(
    @SerialName("_id") val id: String = "",
    @SerialName("folder_name") val folderName: String = "",
    @SerialName("parent_folder_id") val parentFolderId: String? = null,
    @SerialName("deleted_on") val deletedOn: Long = 0,
    @SerialName("folder_count") val folderCount: Int = 0,
    @SerialName("_userPermissions") val permissions: DrivePermissionsDto? = null,
)

@Serializable
data class DrivePermissionsDto(
    @SerialName("can_view") val canView: Boolean? = null,
    @SerialName("can_edit") val canEdit: Boolean? = null,
)

@Serializable
data class CreateDriveFolderRequest(
    @SerialName("folder_name") val folderName: String,
    @SerialName("parent_folder_id") val parentFolderId: String? = null,
    /** Drive requires one; this is the shade its own "new folder" default uses. */
    @SerialName("folder_color") val folderColor: String = DEFAULT_FOLDER_COLOR,
)

/** Drive's default folder colour. */
private const val DEFAULT_FOLDER_COLOR = "#F99300"

private fun DriveFolderDto.toDomain(): RuleDriveFolder = RuleDriveFolder(
    id = id,
    name = folderName,
    parentId = parentFolderId,
    hasChildren = folderCount > 0,
    // Absent permissions mean "not restricted" — the field is only sent for folders that
    // have been shared with limits, and treating its absence as read-only would make the
    // user's own Drive unselectable.
    canEdit = permissions?.canEdit != false,
)
