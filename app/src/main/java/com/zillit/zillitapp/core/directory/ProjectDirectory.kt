package com.zillit.zillitapp.core.directory

import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.ProjectDepartmentEntity
import com.zillit.zillitapp.core.database.entity.ProjectToolEntity
import com.zillit.zillitapp.core.database.entity.ProjectUnitEntity
import com.zillit.zillitapp.core.database.entity.ProjectUserEntity
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.core.storage.MediaLocations
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everyone, every unit and every tool on a project — cached in Realm, keyed by project.
 *
 * Follows the same **API → Realm → Flow → UI** shape as `ProjectRepository`: the network
 * only ever writes to Realm, and every reader observes Realm. That is what makes the crew
 * list render instantly on a project you have opened before, survive a failed refresh, and
 * stay consistent between a socket update and an API refresh.
 *
 * Refreshes **replace** the project's rows rather than merging into them. A user removed
 * from the project, a unit deleted, or a tool switched off simply stops appearing in the
 * response; merging would leave those rows behind forever, so someone who no longer has
 * access would keep showing up in pickers. The delete and the insert happen in one
 * transaction, so a reader never observes an empty directory in between.
 */
@Singleton
class ProjectDirectory @Inject constructor(
    private val api: ZillitApi,
    private val realmProvider: RealmProvider,
    private val session: SessionStore,
    private val mediaLocations: MediaLocations,
) {

    private val realm get() = realmProvider.realm

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // ---------------------------------------------------------------- reads

    /**
     * The project's own people.
     *
     * External guests live in this same table under `isExternalUser` — v2 keeps one
     * directory and filters it — so they are excluded here and read through
     * [ExternalUserRepository] instead. Without the filter a client would appear in the
     * crew list.
     */
    /**
     * The project's people right now, without subscribing.
     *
     * For callers that fold the directory into something else and re-run when it changes,
     * rather than rendering it — collecting a flow there would mean holding a subscription
     * open for a one-off merge.
     */
    fun usersOnce(projectId: String): List<ProjectUser> =
        realm.query<ProjectUserEntity>(
            "projectId == $0 AND isExternalUser == false",
            projectId,
        ).find().map { it.toDomain() }

    fun observeUsers(projectId: String): Flow<List<ProjectUser>> =
        realm.query<ProjectUserEntity>(
            "projectId == $0 AND isExternalUser == false",
            projectId,
        )
            .asFlow()
            .map { change -> change.list.map { it.toDomain() }.sortedBy { it.displayName.lowercase() } }

    fun observeUnits(projectId: String): Flow<List<ProjectUnit>> =
        realm.query<ProjectUnitEntity>("projectId == $0 AND enabled == true", projectId)
            .asFlow()
            .map { change -> change.list.map { it.toDomain() }.sortedBy { it.sortOrder } }

    /** Tools switched on for this project, in the order the server lists them. */
    fun observeTools(projectId: String): Flow<List<ProjectTool>> =
        realm.query<ProjectToolEntity>("projectId == $0 AND enabled == true", projectId)
            .asFlow()
            .map { change -> change.list.map { it.toDomain() }.sortedBy { it.sortOrder } }

    /**
     * The project's departments, in server order.
     *
     * Names are label keys — resolve them at render.
     */
    fun observeDepartments(projectId: String): Flow<List<ProjectDepartment>> =
        realm.query<ProjectDepartmentEntity>("projectId == $0", projectId)
            .asFlow()
            .map { change -> change.list.map { it.toDomain() }.sortedBy { it.sortOrder } }

    /**
     * One department by id.
     *
     * Reads the cache only, like the other `find*` lookups — the department list is loaded
     * when the project opens, so a miss means the department genuinely is not on it.
     */
    fun findDepartment(departmentId: String, projectId: String? = null): ProjectDepartment? {
        val scope = projectId ?: session.activeProject.value?.projectId ?: return null
        return realm.query<ProjectDepartmentEntity>(
            "projectId == $0 AND departmentId == $1",
            scope,
            departmentId,
        ).first().find()?.toDomain()
    }

    /**
     * The designations inside one department.
     *
     * Read back out of the department's stored payload rather than fetched: they arrive with
     * the department list (`designations=true`) and nothing else asks for them, so a second
     * endpoint would be a round trip for data already on disk.
     */
    fun designations(projectId: String, departmentId: String): List<ProjectDesignation> {
        val raw = realm.query<ProjectDepartmentEntity>(
            "projectId == $0 AND departmentId == $1",
            projectId,
            departmentId,
        ).first().find()?.rawJson?.takeIf { it.isNotBlank() } ?: return emptyList()

        val dto = runCatching { json.decodeFromString(DepartmentDto.serializer(), raw) }.getOrNull()

        return dto?.designations.orEmpty().mapNotNull { designation ->
            val id = designation.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ProjectDesignation(
                designationId = id,
                departmentId = departmentId,
                name = designation.designationName.orEmpty(),
                identifier = designation.identifier,
            )
        }
    }

    /**
     * One user by id. The primary lookup behind [com.zillit.zillitapp.core.directory.userDetails].
     *
     * Reads the cache only — it never triggers a fetch. The directory is loaded in full
     * when the project opens, so a miss here means the person genuinely is not on this
     * project, and firing a request per unknown id would turn a chat thread's render into
     * a burst of network calls.
     */
    fun findUser(userId: String, projectId: String? = null): ProjectUser? {
        val scope = projectId ?: session.activeProject.value?.projectId ?: return null
        return realm.query<ProjectUserEntity>("id == $0", ProjectUserEntity.key(scope, userId))
            .first()
            .find()
            ?.toDomain()
    }

    /**
     * Finds someone by their email address.
     *
     * The mail module's lookup: it knows correspondents by address, not by user id, and
     * needs to answer "is this one of us" three times over — to put a colleague's photo on
     * a message, to rank them above a saved contact in the composer's autocomplete, and to
     * leave them out of "add to contacts".
     *
     * Case-insensitive, because an address is, and cache-only for the same reason as
     * [findUser] — a miss means they are genuinely not on this project.
     */
    fun findByEmail(email: String, projectId: String? = null): ProjectUser? {
        val address = email.trim()
        if (address.isEmpty()) return null

        val scope = projectId ?: session.activeProject.value?.projectId ?: return null

        return realm.query<ProjectUserEntity>(
            "projectId == $0 AND email ==[c] $1",
            scope, address,
        )
            .first()
            .find()
            ?.toDomain()
    }

    /** Live version of [findUser] — for a header that must follow a profile change. */
    fun observeUser(userId: String, projectId: String? = null): Flow<ProjectUser?> {
        val scope = projectId ?: session.activeProject.value?.projectId.orEmpty()
        return realm.query<ProjectUserEntity>("id == $0", ProjectUserEntity.key(scope, userId))
            .first()
            .asFlow()
            .map { it.obj?.toDomain() }
    }

    /** One unit by id, from the cache. Same read-only contract as [findUser]. */
    fun findUnit(unitId: String, projectId: String? = null): ProjectUnit? {
        val scope = projectId ?: session.activeProject.value?.projectId ?: return null
        return realm.query<ProjectUnitEntity>("id == $0", ProjectUnitEntity.key(scope, unitId))
            .first().find()?.toDomain()
    }

    /** Whether a tool is available here — the gate every tool entry point should use. */
    fun findTool(identifier: String, projectId: String? = null): ProjectTool? {
        val scope = projectId ?: session.activeProject.value?.projectId ?: return null
        return realm.query<ProjectToolEntity>("id == $0", ProjectToolEntity.key(scope, identifier))
            .first().find()?.toDomain()
    }

    /**
     * Whether this project's crew has been loaded before.
     *
     * Lets a caller decide between showing cached names immediately and waiting for the
     * first fetch, without having to collect the flow to find out.
     */
    fun hasCachedUsers(projectId: String): Boolean =
        realm.query<ProjectUserEntity>(
            "projectId == $0 AND isExternalUser == false",
            projectId,
        ).count().find() > 0

    // --------------------------------------------------------------- writes

    suspend fun refreshUsers(projectId: String): ApiResult<Int> =
        when (val result = api.get<ProjectUsersResponse>(
            ApiEndpoints.Project.USERS,
            ModuleData.WITH_PROJECT_USER_ID,
        )) {
            is ApiResult.Success -> {
                val users = result.data.data.orEmpty().filter { !it.userId.isNullOrBlank() }
                storeUsers(projectId, users)
                ZillitLog.d(TAG, "Users refreshed: ${users.size} for $projectId")
                ApiResult.Success(users.size)
            }

            is ApiResult.Failure -> result
        }

    /**
     * @param project pass the target explicitly when fetching a project other than the
     *   active one — the forward picker does. Without it the request is signed with the
     *   active project's moduledata and silently returns the wrong project's units.
     */
    suspend fun refreshUnits(
        projectId: String,
        project: SessionStore.ActiveProject? = null,
    ): ApiResult<Int> =
        when (val result = api.get<UnitsResponse>(
            url = ApiEndpoints.Units.USER_LIST,
            module = ModuleData.WITH_PROJECT_USER_ID,
            projectOverride = project,
        )) {
            is ApiResult.Success -> {
                val units = result.data.data.orEmpty().filter { !it.unitId.isNullOrBlank() }
                storeUnits(projectId, units)
                ZillitLog.d(TAG, "Units refreshed: ${units.size} for $projectId")
                ApiResult.Success(units.size)
            }

            is ApiResult.Failure -> result
        }

    /**
     * @param isPendingUser v2 uses a second endpoint for users whose join is still
     *   pending; the approved-user list rejects them outright.
     */
    suspend fun refreshTools(projectId: String, isPendingUser: Boolean): ApiResult<Int> {
        val url = if (isPendingUser) {
            ApiEndpoints.Project.TOOLS_PENDING
        } else {
            ApiEndpoints.Project.TOOLS
        }

        return when (val result = api.get<ToolsResponse>(url, ModuleData.WITH_PROJECT_USER_ID)) {
            is ApiResult.Success -> {
                val tools = result.data.data.orEmpty().filter { !it.identifier.isNullOrBlank() }
                storeTools(projectId, tools)
                ZillitLog.d(TAG, "Tools refreshed: ${tools.size} for $projectId")
                ApiResult.Success(tools.size)
            }

            is ApiResult.Failure -> result
        }
    }

    /**
     * The project's departments.
     *
     * Asks for designations in the same call — the response is small and a designation
     * picker would otherwise have to make a second round trip for data already in hand.
     */
    suspend fun refreshDepartments(projectId: String): ApiResult<Int> =
        when (val result = api.get<DepartmentsResponse>(
            url = ApiEndpoints.Configuration.DEPARTMENTS,
            module = ModuleData.WITH_PROJECT_USER_ID,
            query = mapOf("designations" to "true"),
        )) {
            is ApiResult.Success -> {
                val departments = result.data.data.orEmpty().filter { !it.id.isNullOrBlank() }
                storeDepartments(projectId, departments)
                ZillitLog.d(TAG, "Departments refreshed: ${departments.size} for $projectId")
                ApiResult.Success(departments.size)
            }

            is ApiResult.Failure -> result
        }

    /** Wipes one project's directory. For leaving a project, not for switching to another. */
    suspend fun clear(projectId: String) {
        realm.write {
            delete(query<ProjectUserEntity>("projectId == $0", projectId).find())
            delete(query<ProjectUnitEntity>("projectId == $0", projectId).find())
            delete(query<ProjectToolEntity>("projectId == $0", projectId).find())
            delete(query<ProjectDepartmentEntity>("projectId == $0", projectId).find())
        }
    }

    private suspend fun storeUsers(projectId: String, users: List<ProjectUserDto>) {
        realm.write {
            // Replace-in-transaction: see the class doc for why this is not a merge.
            //
            // Scoped to the project's **own** people. External guests share this table and
            // are synced separately by their own cursor, so an unscoped delete would wipe
            // every one of them each time a project opened.
            delete(
                query<ProjectUserEntity>(
                    "projectId == $0 AND isExternalUser == false",
                    projectId,
                ).find(),
            )

            users.forEach { dto ->
                copyToRealm(
                    ProjectUserEntity().apply {
                        this.id = ProjectUserEntity.key(projectId, dto.userId.orEmpty())
                        this.projectId = projectId
                        this.userId = dto.userId.orEmpty()
                        this.fullName = dto.fullName
                            ?: listOfNotNull(dto.firstName, dto.lastName)
                                .joinToString(" ").trim()
                        this.firstName = dto.firstName.orEmpty()
                        this.lastName = dto.lastName.orEmpty()
                        this.departmentId = dto.departmentId
                        this.departmentName = dto.departmentName
                        this.designationId = dto.designationId
                        this.designationName = dto.designationName
                        this.unitId = dto.unitId
                        this.unitName = dto.unitName
                        this.email = dto.email ?: dto.primaryEmail
                        this.phone = dto.phone
                        this.countryCode = dto.countryCode
                        this.profilePictureUrl = dto.profilePicture?.media
                        this.profileThumbnailKey = dto.profilePicture?.thumbnail
                        this.showsLocation = dto.showLocation ?: false
                        this.lastLatitude = dto.lastLocation?.lat ?: 0.0
                        this.lastLongitude = dto.lastLocation?.long ?: 0.0
                        mediaLocations.remember(
                            media = dto.profilePicture?.media,
                            thumbnail = dto.profilePicture?.thumbnail,
                            bucket = dto.profilePicture?.bucket,
                            region = dto.profilePicture?.region,
                        )
                        this.isAdmin = dto.isAdmin ?: false
                        this.isOwner = dto.isOwner ?: false
                        this.enabled = dto.enabled ?: true
                        this.status = dto.status
                        this.keepNamePrivate = dto.keepNamePrivate ?: false
                        this.isExternalUser = dto.isExternalUser ?: false
                        this.deviceId = dto.deviceId.orEmpty()
                        this.sortingActivity = dto.sortingActivity ?: 0
                        this.updated = dto.updated ?: 0
                        this.rawJson = json.encodeToString(ProjectUserDto.serializer(), dto)
                    },
                    UpdatePolicy.ALL,
                )
            }
        }
    }

    private suspend fun storeUnits(projectId: String, units: List<UnitDto>) {
        realm.write {
            delete(query<ProjectUnitEntity>("projectId == $0", projectId).find())

            units.forEachIndexed { index, dto ->
                copyToRealm(
                    ProjectUnitEntity().apply {
                        this.id = ProjectUnitEntity.key(projectId, dto.unitId.orEmpty())
                        this.projectId = projectId
                        this.unitId = dto.unitId.orEmpty()
                        this.unitName = dto.unitName.orEmpty()
                        this.identifier = dto.identifier
                        this.viewAccess = dto.viewAccess ?: true
                        this.postingAccess = dto.postingAccess ?: true
                        this.downloadAccess = dto.downloadAccess ?: true
                        this.privateUnit = dto.privateUnit ?: false
                        this.systemDefined = dto.systemDefined ?: false
                        this.subUnits = dto.subUnits ?: false
                        this.enabled = dto.enabled ?: true
                        this.created = dto.created ?: 0
                        this.updated = dto.updated ?: 0
                        // Server order is the display order; preserved as an index
                        // because Realm results have no inherent ordering.
                        this.sortOrder = index
                        this.rawJson = json.encodeToString(UnitDto.serializer(), dto)
                    },
                    UpdatePolicy.ALL,
                )
            }
        }
    }

    private suspend fun storeTools(projectId: String, tools: List<ToolDto>) {
        realm.write {
            delete(query<ProjectToolEntity>("projectId == $0", projectId).find())

            tools.forEachIndexed { index, dto ->
                copyToRealm(
                    ProjectToolEntity().apply {
                        this.id = ProjectToolEntity.key(projectId, dto.identifier.orEmpty())
                        this.projectId = projectId
                        this.identifier = dto.identifier.orEmpty()
                        // `unit_id` is this endpoint's id; the others are the same
                        // shape served elsewhere.
                        this.toolId = dto.unitId ?: dto.toolId ?: dto.id
                        this.toolName = dto.unitName
                            ?: dto.toolName
                            ?: dto.name.orEmpty()
                        this.enabled = dto.enabled ?: true
                        this.viewAccess = dto.viewAccess ?: true
                        this.postingAccess = dto.postingAccess ?: true
                        this.downloadAccess = dto.downloadAccess ?: true
                        this.adminAccess = dto.adminAccess ?: false
                        this.groupIdentifier = dto.groupIdentifier.orEmpty()
                        this.isTool = dto.isTool ?: false
                        this.isHome = dto.isHome ?: false
                        this.hasSubUnits = dto.hasSubUnits ?: false
                        this.viewingUpdatable = dto.viewingUpdatable ?: false
                        this.postingUpdatable = dto.postingUpdatable ?: false
                        this.downloadUpdatable = dto.downloadUpdatable ?: false
                        this.sortOrder = index
                        this.rawJson = json.encodeToString(ToolDto.serializer(), dto)
                    },
                    UpdatePolicy.ALL,
                )
            }
        }
    }

    private suspend fun storeDepartments(projectId: String, departments: List<DepartmentDto>) {
        realm.write {
            delete(query<ProjectDepartmentEntity>("projectId == $0", projectId).find())

            departments.forEachIndexed { index, dto ->
                copyToRealm(
                    ProjectDepartmentEntity().apply {
                        this.id = ProjectDepartmentEntity.key(projectId, dto.id.orEmpty())
                        this.projectId = projectId
                        this.departmentId = dto.id.orEmpty()
                        this.departmentName = dto.departmentName.orEmpty()
                        this.identifier = dto.identifier
                        this.systemDefined = dto.systemDefined ?: false
                        this.sortOrder = index
                        this.rawJson = json.encodeToString(DepartmentDto.serializer(), dto)
                    },
                    UpdatePolicy.ALL,
                )
            }
        }
    }

    private companion object {
        const val TAG = "ProjectDirectory"
    }
}
