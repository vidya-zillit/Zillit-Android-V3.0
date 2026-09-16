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
    /**
     * Whether this person lets the project see where they are.
     *
     * Off by default and theirs to change. The profile's location action exists only when
     * it is on, which is v2's rule: the button appearing at all is the consent signal.
     */
    @SerialName("show_location") val showLocation: Boolean? = null,

    /** Where they last were. Zeroes mean the server has never had a position for them. */
    @SerialName("last_location") val lastLocation: LatLongDto? = null,

    @SerialName("is_admin") val isAdmin: Boolean? = null,
    @SerialName("is_owner") val isOwner: Boolean? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("keep_name_private") val keepNamePrivate: Boolean? = null,
    @SerialName("is_external_user") val isExternalUser: Boolean? = null,
    /**
     * The device this person is signed in on.
     *
     * Presence is keyed by device, not by user, so a conversation row cannot show an
     * online dot without it. Already sent by the endpoint; it was simply not read.
     */
    @SerialName("device_id") val deviceId: String? = null,
    /**
     * When this person's conversation with the current user last moved.
     *
     * The order key for the Chat tab. Already sent by the endpoint and, like
     * `device_id`, simply never read — which is why every conversation sorted as
     * though it had no history.
     */
    @SerialName("sorting_activity") val sortingActivity: Long? = null,
    @SerialName("updated") val updated: Long? = null,
    /**
     * The user's own mailbox.
     *
     * Only the profile endpoint returns it — the crew list omits it — which is why the
     * email module reads the mailbox from [com.zillit.zillitapp.core.session.CurrentUserStore]
     * rather than from the directory.
     */
    @SerialName("mail_box_detail") val mailbox: MailboxDto? = null,
    /**
     * Whether the user consented to a Zillit mailbox at all. Server default is on, so a
     * missing value reads as true; only an explicit false means "never provision one".
     */
    @SerialName("zillit_email_enable") val zillitEmailEnable: Boolean? = null,
    /**
     * BCC presets — addresses blind-copied on every mail this user sends.
     *
     * On the **user** for a personal mailbox; the shared mailbox carries its own set, on
     * [MailboxDto]. They are separate lists on purpose: a preset that copies your own
     * archive should not apply to mail the whole Accounts department sends.
     */
    @SerialName("bcc") val bcc: List<BccPresetDto> = emptyList(),
)

@Serializable
data class BccPresetDto(
    @SerialName("email_address") val emailAddress: String = "",
)

/**
 * A mailbox's connection details.
 *
 * The same shape serves the user's personal mailbox and the project's shared "Accounts"
 * one — they differ in who may open them, not in what they are.
 *
 * The two password fields are **ciphertext** (`enc:v1:…`), not passwords. The plaintext is
 * fetched on demand from `imap-credentials/reveal`, and every reveal is audit-logged, so
 * nothing here should ever be shown to a user directly.
 */
@Serializable
data class MailboxDto(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("email_address") val emailAddress: String? = null,
    @SerialName("conversation_view") val conversationView: Boolean? = null,
    @SerialName("secure_smtp_server_host") val smtpHost: String? = null,
    @SerialName("secure_smtp_server_port") val smtpPort: Int? = null,
    @SerialName("secure_smtp_user_name") val smtpUserName: String? = null,
    @SerialName("secure_imap_server_host") val imapHost: String? = null,
    @SerialName("secure_imap_server_port") val imapPort: Int? = null,
    @SerialName("secure_imap_user_name") val imapUserName: String? = null,
    @SerialName("mail_from_domain") val mailFromDomain: String? = null,
    @SerialName("mailbox_vendor") val vendor: String? = null,
    @SerialName("bcc") val bcc: List<BccPresetDto> = emptyList(),
)

@Serializable
data class AttachmentDto(
    @SerialName("media") val media: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
    /**
     * Which bucket this file is in, and where. Not decoration: a person who joined while
     * the project sat in another region keeps their photo in that region's bucket, and
     * asking the project's own bucket for it returns `NoSuchKey`. Recorded in
     * `MediaLocations` as the users list is parsed, and read back when a transfer starts.
     */
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("region") val region: String? = null,
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

/*
 * What `/project/tools` actually sends, verified against a live response:
 *
 *   project_id, unit_id, unit_name, identifier, group_identifier,
 *   enabled, sub_units, downloadUpdatable,
 *   view_access, posting_access, download_access, tool
 *
 * Two things to know about that list.
 *
 * `unit_name` is the tool's **label key** — `accounts_label` for `accounting_tool`. It is
 * both what the name is resolved from and what the badge tree keys that tool on, so it is
 * the field that ties a tile to its unread count. Reading the name from `tool_name`, which
 * this endpoint does not send, is what left every tile blank.
 *
 * `downloadUpdatable` is **camelCase**, alone among the fields here. Declaring it in snake
 * case matched nothing and silently read false.
 *
 * Everything else below is absent from this endpoint and declared only because the same
 * shape is served under those names elsewhere: `_id`, `tool_id`, `tool_name`, `name`,
 * `admin_access`, `home`, and the viewing/posting halves of the updatable rights.
 */
@Serializable
data class ToolDto(
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("tool_id") val toolId: String? = null,
    /**
     * The tool's name, as a label key.
     *
     * **`unit_name` is the field this endpoint actually sends.** The others are accepted
     * because the same shape is served elsewhere under different names, and reading only
     * `tool_name` left every title an empty string — which rendered as a row with an icon,
     * an info button and no name at all.
     */
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("name") val name: String? = null,
    /** Also `unit_id` on this endpoint. */
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("view_access") val viewAccess: Boolean? = null,
    @SerialName("posting_access") val postingAccess: Boolean? = null,
    @SerialName("download_access") val downloadAccess: Boolean? = null,
    /** Not sent by `/project/tools`; present on other endpoints serving this shape. */
    @SerialName("admin_access") val adminAccess: Boolean? = null,
    /**
     * Which section of the Tools tab this belongs to. Blank means "Ungrouped".
     *
     * The group is a project entity — six defaults plus whatever an admin has created — so
     * this is an identifier, not a display name. Resolve it through the groups list.
     */
    @SerialName("group_identifier") val groupIdentifier: String? = null,
    /**
     * Whether this row is a Tools tile.
     *
     * Every row in a live `/project/tools` response carries `tool: true`; `home` is not sent
     * there at all. Both are kept because the same shape is served for Home units elsewhere,
     * and a row that is a Home unit must not become a tile.
     */
    @SerialName("tool") val isTool: Boolean? = null,
    @SerialName("home") val isHome: Boolean? = null,
    @SerialName("sub_units") val hasSubUnits: Boolean? = null,
    /**
     * Whether an admin may change this right for other users. For the permission grid.
     *
     * Only the download half is sent by `/project/tools`, and it is camelCase there while
     * every neighbouring field is snake_case. The other two are declared for the endpoints
     * that do send them.
     */
    @SerialName("downloadUpdatable") val downloadUpdatable: Boolean? = null,
    @SerialName("viewing_updatable") val viewingUpdatable: Boolean? = null,
    @SerialName("posting_updatable") val postingUpdatable: Boolean? = null,
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

/** The server's spelling: `lat` and `long`, not `latitude`/`longitude`. */
@Serializable
data class LatLongDto(
    val lat: Double? = null,
    val long: Double? = null,
)
