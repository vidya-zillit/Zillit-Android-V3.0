package com.zillit.zillitapp.feature.project.data

import com.zillit.zillitapp.core.database.entity.ProjectEntity
import com.zillit.zillitapp.feature.project.domain.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the project endpoints.
 *
 * Only the fields v3 actually uses are declared. `ignoreUnknownKeys` is on in the shared
 * [kotlinx.serialization.json.Json], so the backend can add fields without breaking the
 * app, and nothing forces a Realm migration for a field no screen reads.
 */
@Serializable
data class ProjectListResponse(
    @SerialName("data") val data: List<ProjectDto> = emptyList(),
    @SerialName("message") val message: String? = null,
)

@Serializable
data class ProjectDto(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("project_type") val projectType: String? = null,

    /**
     * The machine value — `entertainment`, `personal`, `other` — as opposed to
     * `project_type`, which is a label key for display.
     *
     * They are not interchangeable, and the rules that turn C&C's filter chips on and off
     * are written against this one.
     */
    @SerialName("project_type_id") val projectTypeId: String? = null,

    /** This user's standing in the project: `accepted`, `pending`, `removed`. */
    @SerialName("status") val membershipStatus: String? = null,
    @SerialName("project_sub_type") val projectSubType: String? = null,
    @SerialName("project_code") val projectCode: String? = null,
    @SerialName("company_name") val companyName: String? = null,
    /** ISO code the production works in. Drives whether Translate is offered. */
    @SerialName("project_language") val projectLanguage: String? = null,
    @SerialName("enterprise_client_id") val enterpriseClientId: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("is_favourite") val isFavourite: Boolean? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("mark_deleted") val markDeleted: Boolean? = null,
    @SerialName("delete_in_hours") val deleteInHours: Long? = null,
    @SerialName("unread") val unread: Int? = null,
    @SerialName("date_created") val dateCreated: Long? = null,
    /**
     * The project's shared "Accounts" mailbox.
     *
     * Populated only for users in the project's Accounts department; `{}` or null for
     * everyone else — which is exactly how the email module decides whether to offer the
     * mailbox switcher at all.
     */
    @SerialName("accounts_mail_box_detail")
    val accountsMailbox: com.zillit.zillitapp.core.directory.MailboxDto? = null,
)

/**
 * Response shape for endpoints that *act* rather than return a collection.
 *
 * These return `"data": {}` — an empty object, not a list. Decoding them as
 * [ProjectListResponse] throws `Expected start of the array '['`, which the caller then
 * reports as a failure even though the server did the work. `data` is deliberately not
 * modelled at all: nothing reads it, and leaving it out means the backend can put
 * anything there without breaking the client.
 */
@Serializable
data class ActionResponse(
    @SerialName("status") val status: Int = 0,
    @SerialName("message") val message: String? = null,
)

/** Request body for the favourite toggle. */
@Serializable
data class FavouriteRequest(
    @SerialName("project_id") val projectId: String,
    @SerialName("favourite") val favourite: Boolean,
)

/** Request body for joining a project by its share code. */
@Serializable
data class JoinProjectRequest(
    @SerialName("project_code") val projectCode: String,
)

fun ProjectDto.toEntity(cachedAt: Long): ProjectEntity? {
    val id = projectId?.takeIf { it.isNotBlank() } ?: return null
    return ProjectEntity().also { entity ->
        entity.accountsMailboxEmail = accountsMailbox?.emailAddress?.takeIf { it.isNotBlank() }
        entity.accountsSmtpHost = accountsMailbox?.smtpHost.orEmpty()
        entity.accountsSmtpPort = accountsMailbox?.smtpPort ?: 0
        entity.accountsSmtpUserName = accountsMailbox?.smtpUserName.orEmpty()
        entity.accountsImapHost = accountsMailbox?.imapHost.orEmpty()
        entity.accountsImapPort = accountsMailbox?.imapPort ?: 0
        entity.accountsBccPresets = accountsMailbox?.bcc.orEmpty()
            .map { it.emailAddress }
            .filter { it.isNotBlank() }
            .joinToString(",")
        entity.projectId = id
        entity.userId = userId.orEmpty()
        entity.projectName = projectName.orEmpty()
        entity.projectType = projectType
        entity.projectTypeId = projectTypeId
        entity.membershipStatus = membershipStatus.orEmpty()
        entity.projectSubType = projectSubType
        entity.projectCode = projectCode
        entity.companyName = companyName
        entity.projectLanguage = projectLanguage
        entity.enterpriseClientId = enterpriseClientId
        entity.isAdmin = isAdmin
        entity.isFavourite = isFavourite ?: false
        entity.enabled = enabled ?: true
        entity.markDeleted = markDeleted ?: false
        entity.deleteInHours = deleteInHours
        entity.unread = unread ?: 0
        entity.dateCreated = dateCreated
        entity.cachedAt = cachedAt
    }
}

fun ProjectEntity.toDomain(): Project = Project(
    id = projectId,
    userId = userId,
    name = projectName,
    type = projectType,
    subType = projectSubType,
    code = projectCode,
    companyName = companyName,
    language = projectLanguage,
    isAdmin = isAdmin,
    isFavourite = isFavourite,
    enabled = enabled,
    enterpriseClientId = enterpriseClientId,
    unreadCount = unread,
    createdAt = dateCreated,
    deletionHoursRemaining = deleteInHours.takeIf { markDeleted },
)
