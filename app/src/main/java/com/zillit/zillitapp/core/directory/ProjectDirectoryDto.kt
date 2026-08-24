package com.zillit.zillitapp.core.directory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the project-open fan-out.
 *
 * Every field is nullable with a default. The backend omits properties freely depending
 * on the caller's role, and a non-null field would make the whole response fail to parse
 * over one missing key — losing the entire crew list because one user has no designation.
 */

@Serializable
data class ProjectUsersResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: List<ProjectUserDto>? = null,
)

@Serializable
data class ProjectUserDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("designation_id") val designationId: String? = null,
    @SerialName("designation_name") val designationName: String? = null,
    @SerialName("join_unit_id") val unitId: String? = null,
    @SerialName("join_unit_name") val unitName: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("primary_email") val primaryEmail: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("profile_picture") val profilePicture: AttachmentDto? = null,
    @SerialName("is_admin") val isAdmin: Boolean? = null,
    @SerialName("is_owner") val isOwner: Boolean? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("keep_name_private") val keepNamePrivate: Boolean? = null,
    @SerialName("is_external_user") val isExternalUser: Boolean? = null,
    @SerialName("updated") val updated: Long? = null,
)

@Serializable
data class AttachmentDto(
    @SerialName("media") val media: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
)

@Serializable
data class ProfileResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: ProjectUserDto? = null,
)

@Serializable
data class UnitsResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: List<UnitDto>? = null,
)

@Serializable
data class UnitDto(
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("view_access") val viewAccess: Boolean? = null,
    @SerialName("posting_access") val postingAccess: Boolean? = null,
    @SerialName("download_access") val downloadAccess: Boolean? = null,
    @SerialName("private_unit") val privateUnit: Boolean? = null,
    @SerialName("system_defined") val systemDefined: Boolean? = null,
    @SerialName("sub_units") val subUnits: Boolean? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("created") val created: Long? = null,
    @SerialName("updated") val updated: Long? = null,
)

@Serializable
data class ToolsResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: List<ToolDto>? = null,
)

@Serializable
data class ToolDto(
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("tool_id") val toolId: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("view_access") val viewAccess: Boolean? = null,
    @SerialName("posting_access") val postingAccess: Boolean? = null,
    @SerialName("download_access") val downloadAccess: Boolean? = null,
    @SerialName("admin_access") val adminAccess: Boolean? = null,
)

@Serializable
data class DepartmentsResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: List<DepartmentDto>? = null,
)

@Serializable
data class DepartmentDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    /** Label key, not display text. */
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("system_defined") val systemDefined: Boolean? = null,
    /**
     * Only present when the request asks for them (`designations=true`).
     *
     * Kept on the DTO so the raw JSON stored alongside a department carries them, which
     * is what a designation picker will read rather than refetching.
     */
    @SerialName("designations") val designations: List<DesignationDto>? = null,
)

@Serializable
data class DesignationDto(
    @SerialName("_id") val id: String? = null,
    /** Label key, not display text. */
    @SerialName("designation_name") val designationName: String? = null,
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("system_defined") val systemDefined: Boolean? = null,
)
