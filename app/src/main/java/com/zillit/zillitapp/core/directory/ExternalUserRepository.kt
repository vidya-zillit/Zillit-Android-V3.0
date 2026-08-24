package com.zillit.zillitapp.core.directory

import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.ProjectUserEntity
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.session.SessionStore
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Someone outside the project who can still be invited — a client, a location owner.
 *
 * A view over the same [ProjectUserEntity] rows everyone else uses, distinguished by
 * `isExternalUser`.
 */
data class ExternalUser(
    val id: String,
    val fullName: String,
    val email: String,
    val phone: String? = null,
    val countryCode: String? = null,
    val gender: String? = null,
    val departmentId: String? = null,
    val departmentName: String? = null,
    val designationId: String? = null,
    val designationName: String? = null,
    /**
     * What kind of outsider this is.
     *
     * Either a label key the dictionary resolves (`crew_member_label`, `vender_label`) or,
     * for "Others", the free text someone typed. v2 stores both in the one field, and the
     * server keys off it, so it stays one field here too.
     */
    val externalUserType: String? = null,
) {
    /** What a picker row shows: the name if there is one, otherwise the address. */
    val displayName: String get() = fullName.ifBlank { email }
}

@Serializable
data class ExternalUserResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data")
    @Serializable(with = ExternalUserDataSerializer::class)
    val data: List<ExternalUserDto>? = null,
)

/**
 * Reads `data` whether it is a list or a single guest.
 *
 * The list endpoint returns an array; create and update return the one row they wrote, as a
 * bare object. One shape here, so a successful save is not reported as a failure — which is
 * exactly what happened before this existed.
 */
object ExternalUserDataSerializer : KSerializer<List<ExternalUserDto>> {

    private val delegate = ListSerializer(ExternalUserDto.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<ExternalUserDto> {
        val input = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)

        return when (val element = input.decodeJsonElement()) {
            is JsonArray -> element.map {
                input.json.decodeFromJsonElement(ExternalUserDto.serializer(), it)
            }

            is JsonObject -> listOf(
                input.json.decodeFromJsonElement(ExternalUserDto.serializer(), element),
            )

            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<ExternalUserDto>) =
        delegate.serialize(encoder, value)
}

@Serializable
data class ExternalUserDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("external_user_id") val externalUserId: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("gender") val gender: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("designation_id") val designationId: String? = null,
    @SerialName("designation_name") val designationName: String? = null,
    @SerialName("external_user_type") val externalUserType: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("updated") val updated: Long? = null,
)

/**
 * The people outside the project who can be invited to things.
 *
 * Stored in the **project user table**, flagged `isExternalUser`, exactly as v2 does — one
 * directory with a filter rather than two tables that can disagree about who someone is.
 * Everything that already resolves a name from a user id therefore resolves external
 * guests too, for free.
 *
 * Synced by **timestamp cursor**: ask for everything newer than the newest row already
 * stored, rather than re-downloading the list.
 */
@Singleton
class ExternalUserRepository @Inject constructor(
    private val api: ZillitApi,
    private val realmProvider: RealmProvider,
    private val session: SessionStore,
) {

    private val realm get() = realmProvider.realm

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun observe(projectId: String): Flow<List<ExternalUser>> =
        realm.query<ProjectUserEntity>(
            "projectId == $0 AND isExternalUser == true AND enabled == true",
            projectId,
        )
            .asFlow()
            .map { change -> change.list.map { it.toExternalUser() }.sortedBy { it.displayName.lowercase() } }

    /**
     * Anyone whose name or address contains [query].
     *
     * Matching the address matters as much as the name: an external guest is usually known
     * by the address someone was given, not by how they were filed.
     */
    fun search(projectId: String, query: String): List<ExternalUser> {
        val all = realm.query<ProjectUserEntity>(
            "projectId == $0 AND isExternalUser == true AND enabled == true",
            projectId,
        ).find().map { it.toExternalUser() }

        if (query.isBlank()) return all

        return all.filter {
            it.fullName.contains(query, ignoreCase = true) ||
                it.email.contains(query, ignoreCase = true)
        }
    }

    /** An exact address match — what decides "use this one" from "add a new one". */
    fun findByEmail(projectId: String, email: String): ExternalUser? =
        realm.query<ProjectUserEntity>(
            "projectId == $0 AND isExternalUser == true AND email ==[c] $1",
            projectId,
            email.trim(),
        ).first().find()?.toExternalUser()

    /** Pulls down anything added or changed since the last sync. */
    suspend fun refresh(filter: String = ""): ApiResult<Int> {
        val projectId = session.activeProject.value?.projectId
            ?: return ApiResult.Success(0)

        // The cursor is the newest external row already held — internal users are synced
        // separately and their timestamps must not move this one.
        val since = realm.query<ProjectUserEntity>(
            "projectId == $0 AND isExternalUser == true",
            projectId,
        ).find().maxOfOrNull { it.updated } ?: 0L

        return when (
            val result = api.get<ExternalUserResponse>(
                url = ApiEndpoints.User.EXTERNAL_USER,
                module = ModuleData.WITH_PROJECT_USER_BUCKET_DATA,
                query = buildMap {
                    // The list endpoint spells the filter differently from the field it
                    // filters on — query `ExternalUserType`, body `external_user_type`.
                    if (filter.isNotBlank()) put("ExternalUserType", filter)
                    put("nextPrevious", if (since == 0L) FIRST_PAGE else NEXT_PAGE)
                    put(
                        "timestamp",
                        (if (since == 0L) System.currentTimeMillis() else since).toString(),
                    )
                },
            )
        ) {
            is ApiResult.Success -> {
                val users = result.data.data.orEmpty().filter { !it.email.isNullOrBlank() }
                store(projectId, users)
                ZillitLog.d(TAG, "External users refreshed: ${users.size}")
                ApiResult.Success(users.size)
            }

            is ApiResult.Failure -> result
        }
    }

    suspend fun add(user: ExternalUser): ApiResult<ExternalUser?> = save(user, isUpdate = false)

    suspend fun update(user: ExternalUser): ApiResult<ExternalUser?> = save(user, isUpdate = true)

    private suspend fun save(user: ExternalUser, isUpdate: Boolean): ApiResult<ExternalUser?> {
        val projectId = session.activeProject.value?.projectId.orEmpty()

        val body = ExternalUserDto(
            id = user.id.takeIf { it.isNotBlank() },
            // v2 sends the id under a second name on update; the server keys off it there.
            externalUserId = user.id.takeIf { isUpdate && it.isNotBlank() },
            fullName = user.fullName,
            email = user.email,
            phone = user.phone,
            countryCode = user.countryCode,
            gender = user.gender,
            departmentId = user.departmentId,
            departmentName = user.departmentName,
            designationId = user.designationId,
            designationName = user.designationName,
            externalUserType = user.externalUserType,
        )

        val result = if (isUpdate) {
            api.put<ExternalUserDto, ExternalUserResponse>(
                url = ApiEndpoints.User.EXTERNAL_USER,
                body = body,
                module = ModuleData.WITH_PROJECT_USER_BUCKET_DATA,
            )
        } else {
            api.post<ExternalUserDto, ExternalUserResponse>(
                url = ApiEndpoints.User.EXTERNAL_USER,
                body = body,
                module = ModuleData.WITH_PROJECT_USER_BUCKET_DATA,
            )
        }

        return when (result) {
            is ApiResult.Success -> {
                val saved = result.data.data?.firstOrNull()
                saved?.let { store(projectId, listOf(it)) }
                ApiResult.Success(saved?.toDomain())
            }

            is ApiResult.Failure -> {
                ZillitLog.w(TAG, "Saving external user failed: ${result.error.message}")
                result
            }
        }
    }

    private suspend fun store(projectId: String, users: List<ExternalUserDto>) {
        realm.write {
            users.forEach { dto ->
                val userId = dto.userId ?: dto.id ?: dto.externalUserId ?: return@forEach
                copyToRealm(
                    ProjectUserEntity().apply {
                        // Same key as any other project user, so a name lookup by id finds
                        // an external guest without knowing it is one.
                        this.id = ProjectUserEntity.key(projectId, userId)
                        this.projectId = projectId
                        this.userId = userId
                        this.fullName = dto.fullName
                            ?: listOfNotNull(dto.firstName, dto.lastName).joinToString(" ").trim()
                        this.firstName = dto.firstName.orEmpty()
                        this.lastName = dto.lastName.orEmpty()
                        this.email = dto.email
                        this.phone = dto.phone
                        this.countryCode = dto.countryCode
                        this.departmentName = dto.departmentName
                        this.designationName = dto.designationName
                        this.enabled = dto.enabled ?: true
                        this.isExternalUser = true
                        this.updated = dto.updated ?: System.currentTimeMillis()
                        this.rawJson = json.encodeToString(ExternalUserDto.serializer(), dto)
                    },
                    // Merged, not replaced: this is an incremental sync, and clearing would
                    // drop everyone the cursor did not return — including internal users,
                    // who share this table.
                    UpdatePolicy.ALL,
                )
            }
        }
    }

    /**
     * The external-only fields, read back out of the raw payload.
     *
     * Gender and user type have no column of their own — they matter to the external-user
     * form and nowhere else, so they ride along in the stored JSON rather than widening
     * the table every project user shares.
     */
    private fun ProjectUserEntity.toExternalUser(): ExternalUser {
        val raw = rawJson.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(ExternalUserDto.serializer(), it) }.getOrNull() }

        return ExternalUser(
            id = userId,
            fullName = fullName,
            email = email.orEmpty(),
            phone = phone,
            countryCode = countryCode,
            gender = raw?.gender,
            departmentId = raw?.departmentId,
            departmentName = departmentName,
            designationId = raw?.designationId,
            designationName = designationName,
            externalUserType = raw?.externalUserType,
        )
    }

    private fun ExternalUserDto.toDomain() = ExternalUser(
        id = userId ?: id ?: externalUserId.orEmpty(),
        fullName = fullName ?: listOfNotNull(firstName, lastName).joinToString(" ").trim(),
        email = email.orEmpty(),
        phone = phone,
        countryCode = countryCode,
        gender = gender,
        departmentId = departmentId,
        departmentName = departmentName,
        designationId = designationId,
        designationName = designationName,
        externalUserType = externalUserType,
    )

    private companion object {
        const val TAG = "ExternalUserRepository"
        const val FIRST_PAGE = "previous"
        const val NEXT_PAGE = "next"
    }
}
