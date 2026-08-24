package com.zillit.zillitapp.core.directory

import com.zillit.zillitapp.core.database.entity.ProjectDepartmentEntity
import com.zillit.zillitapp.core.database.entity.ProjectToolEntity
import com.zillit.zillitapp.core.database.entity.ProjectUnitEntity
import com.zillit.zillitapp.core.database.entity.ProjectUserEntity

/**
 * A person on the active project, as the rest of the app sees them.
 *
 * Separate from [ProjectUserEntity] so no screen ends up holding a live Realm object it
 * can read after the write transaction that produced it has closed.
 */
data class ProjectUser(
    val userId: String,
    val projectId: String,
    val fullName: String,
    val firstName: String,
    val lastName: String,
    val departmentId: String?,
    val departmentName: String?,
    val designationId: String?,
    val designationName: String?,
    val unitId: String?,
    val unitName: String?,
    val email: String?,
    val phone: String?,
    val countryCode: String?,
    val profilePictureUrl: String?,
    /** Small copy of [profilePictureUrl], which is what an avatar should load. */
    val profileThumbnailKey: String? = null,
    val isAdmin: Boolean,
    val isOwner: Boolean,
    val enabled: Boolean,
    val status: String?,
    val keepNamePrivate: Boolean,
    val isExternalUser: Boolean,
    val updated: Long,
    /** The untouched server object, for fields without a typed column. */
    val rawJson: String,
) {
    /**
     * What to show in a chat header or a member row.
     *
     * Honours `keep_name_private`: a user who has hidden their name is shown by
     * designation instead, and falls back to a neutral label rather than leaking the
     * name through an empty-string check.
     */
    val displayName: String
        get() = when {
            !keepNamePrivate && fullName.isNotBlank() -> fullName
            !designationName.isNullOrBlank() -> designationName
            else -> ""
        }

    /** "Vidya Pixel (Driver)" — the shape v2 renders in chat. */
    val displayNameWithRole: String
        get() = when {
            displayName.isBlank() -> ""
            designationName.isNullOrBlank() -> displayName
            else -> "$displayName ($designationName)"
        }

    val initials: String
        get() = displayName.trim().split(Regex("\\s+"))
            .mapNotNull { it.firstOrNull { c -> c.isLetter() }?.uppercaseChar() }
            .take(2).joinToString("").ifEmpty { "?" }
}

/** A Home unit — its own chat thread. */
data class ProjectUnit(
    val unitId: String,
    val projectId: String,
    /** Server label key. Resolve through the label dictionary before display. */
    val unitName: String,
    val identifier: String?,
    val viewAccess: Boolean,
    val postingAccess: Boolean,
    val downloadAccess: Boolean,
    val privateUnit: Boolean,
    val systemDefined: Boolean,
    val enabled: Boolean,
    val sortOrder: Int,
)

/** A tool enabled on the project, with this user's access to it. */
data class ProjectTool(
    val identifier: String,
    val projectId: String,
    val toolId: String?,
    /** Server label key. */
    val toolName: String,
    val enabled: Boolean,
    val viewAccess: Boolean,
    val postingAccess: Boolean,
    val downloadAccess: Boolean,
    val adminAccess: Boolean,
    val sortOrder: Int,
)

/**
 * A department on the project.
 *
 * [name] is a server label key. Resolve it through the label dictionary at render time —
 * storing the resolved text would freeze it to the language in force when it was fetched.
 */
data class ProjectDepartment(
    val departmentId: String,
    val projectId: String,
    val name: String,
    val identifier: String?,
    val systemDefined: Boolean,
    val sortOrder: Int,
)

/**
 * A role within a department.
 *
 * [name] is a label key, like a department's — resolve it at render.
 */
data class ProjectDesignation(
    val designationId: String,
    val departmentId: String,
    val name: String,
    val identifier: String?,
)

internal fun ProjectDepartmentEntity.toDomain() = ProjectDepartment(
    departmentId = departmentId,
    projectId = projectId,
    name = departmentName,
    identifier = identifier,
    systemDefined = systemDefined,
    sortOrder = sortOrder,
)

internal fun ProjectUserEntity.toDomain() = ProjectUser(
    userId = userId,
    projectId = projectId,
    fullName = fullName,
    firstName = firstName,
    lastName = lastName,
    departmentId = departmentId,
    departmentName = departmentName,
    designationId = designationId,
    designationName = designationName,
    unitId = unitId,
    unitName = unitName,
    email = email,
    phone = phone,
    countryCode = countryCode,
    profilePictureUrl = profilePictureUrl,
    profileThumbnailKey = profileThumbnailKey,
    isAdmin = isAdmin,
    isOwner = isOwner,
    enabled = enabled,
    status = status,
    keepNamePrivate = keepNamePrivate,
    isExternalUser = isExternalUser,
    updated = updated,
    rawJson = rawJson,
)

internal fun ProjectUnitEntity.toDomain() = ProjectUnit(
    unitId = unitId,
    projectId = projectId,
    unitName = unitName,
    identifier = identifier,
    viewAccess = viewAccess,
    postingAccess = postingAccess,
    downloadAccess = downloadAccess,
    privateUnit = privateUnit,
    systemDefined = systemDefined,
    enabled = enabled,
    sortOrder = sortOrder,
)

internal fun ProjectToolEntity.toDomain() = ProjectTool(
    identifier = identifier,
    projectId = projectId,
    toolId = toolId,
    toolName = toolName,
    enabled = enabled,
    viewAccess = viewAccess,
    postingAccess = postingAccess,
    downloadAccess = downloadAccess,
    adminAccess = adminAccess,
    sortOrder = sortOrder,
)
