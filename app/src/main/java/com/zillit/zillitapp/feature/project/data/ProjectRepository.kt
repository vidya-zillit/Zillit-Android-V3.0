package com.zillit.zillitapp.feature.project.data

import com.zillit.zillitapp.core.badge.BadgeAxis
import com.zillit.zillitapp.core.badge.BadgeKey
import com.zillit.zillitapp.core.badge.BadgeManager
import com.zillit.zillitapp.core.badge.BadgeSource
import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.ProjectEntity
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.project.domain.Project
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first source of truth for projects.
 *
 * The read path ([observeProjects]) always comes from Realm, and the network only ever
 * *writes* to Realm. That means the list renders instantly from cache on a cold start,
 * a failed refresh still shows the last known data, and there is exactly one place the
 * UI reads from — so a socket update and an API refresh cannot disagree on screen.
 *
 * This is also the reference shape for every future feature: **API → Realm → Flow → UI**,
 * with badges written alongside the data they belong to.
 */
@Singleton
class ProjectRepository @Inject constructor(
    private val api: ZillitApi,
    private val realmProvider: RealmProvider,
    private val badgeManager: BadgeManager,
) {

    private val realm get() = realmProvider.realm

    /**
     * Live project list in display order: **unread badge, then favourite, then name**.
     *
     * Sorted here rather than in the ViewModel so every consumer sees one order.
     * Done in Kotlin rather than as a Realm `sort()` because the ordering is compound
     * across three fields of different types and needs a stable, case-insensitive name
     * comparison — expressing that as chained Realm sorts is both less readable and
     * harder to keep consistent with the tie-breaking rules.
     */
    /**
     * One cached project, read synchronously.
     *
     * For callers that need a fact about the open project mid-action — its language, when
     * deciding how to translate — rather than a stream to render.
     */
    fun cachedProject(projectId: String?): Project? {
        val id = projectId?.takeIf { it.isNotBlank() } ?: return null
        return realm.query<ProjectEntity>("projectId == $0", id).first().find()?.toDomain()
    }

    fun observeProjects(): Flow<List<Project>> =
        combine(
            realm.query<ProjectEntity>("enabled == true")
                .asFlow()
                .map { change -> change.list.map { it.toDomain() } },
            // Live badge counts, merged in rather than read off the cached project row.
            // This is what makes a notification arriving while the list is on screen move
            // the number immediately — the ProjectEntity only changes on a full refresh.
            // Grouped by project: one query answers for every row in the list. The prefix
            // is empty because this list spans projects — every other badge in the app pins
            // a project, this is the one that does not.
            badgeManager.observeGrouped(prefix = BadgeKey(), by = BadgeAxis.PROJECT),
        ) { projects, badges ->
            projects
                .map { project -> project.copy(unreadCount = badges[project.id] ?: project.unreadCount) }
                .sortedWith(DISPLAY_ORDER)
        }

    /** True when nothing has ever been cached — lets the UI tell "empty" from "not loaded". */
    suspend fun hasCachedProjects(): Boolean =
        realm.query<ProjectEntity>().count().find() > 0L

    /**
     * Fetches from the server and replaces the cache.
     *
     * Uses [ModuleData.DEFAULT] because this call happens *before* a project is selected —
     * there is no active project to put in the header yet. Sending
     * `WITH_PROJECT_USER_ID` here would stamp an empty project_id.
     */
    suspend fun refresh(): ApiResult<List<Project>> {
        val result = api.get<ProjectListResponse>(
            url = ApiEndpoints.Project.LIST,
            module = ModuleData.DEFAULT,
        )

        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val now = System.currentTimeMillis()
                val entities = result.data.data.mapNotNull { it.toEntity(now) }
                cache(entities)
                refreshProjectBadges()
                ApiResult.Success(entities.map { it.toDomain() })
            }
        }
    }

    /**
     * Writes the server's list into Realm as the complete truth.
     *
     * Rows absent from the response are deleted, not left behind — otherwise a project
     * the user was removed from would linger in the list forever. Both steps run in one
     * write transaction so the UI never observes a moment with an empty list.
     */
    private suspend fun cache(entities: List<ProjectEntity>) {
        realm.write {
            val incomingIds = entities.map { it.projectId }.toSet()

            query<ProjectEntity>().find()
                .filter { it.projectId !in incomingIds }
                .forEach { delete(it) }

            entities.forEach { copyToRealm(it, UpdatePolicy.ALL) }
        }
    }

    /**
     * Loads unread counts for every project — what the list badges show.
     *
     * A separate call on purpose: `GET api/v2/project` carries an `unread` field, but the
     * server returns 0 there for every row, so trusting it means the list never shows a
     * badge. v2 has the same split, with `device/unread` as the real source.
     *
     * Device-scoped, so one request covers all projects, including ones this device has
     * never opened and therefore holds no notifications for.
     */
    suspend fun refreshProjectBadges() {
        when (
            val result = api.get<DeviceUnreadResponse>(
                url = ApiEndpoints.Notification.DEVICE_UNREAD,
                // No active project when the list is on screen, so the project-scoped
                // header variants cannot be signed.
                module = ModuleData.DEFAULT,
            )
        ) {
            is ApiResult.Failure ->
                ZillitLog.w(TAG, "Project badges failed: ${result.error.message}")

            is ApiResult.Success -> {
                val rows = result.data.data.orEmpty().filter { !it.projectId.isNullOrBlank() }
                ZillitLog.d(TAG, "Project badges: ${rows.count { (it.unread ?: 0) > 0 }} with unread")

                rows.forEach { row ->
                    badgeManager.set(
                        key = BadgeKey.project(row.projectId!!),
                        count = row.unread ?: 0,
                        // Tagged so a realtime rebuild replaces only its own rows and
                        // leaves the server's snapshot intact.
                        source = BadgeSource.API,
                    )
                }
            }
        }
    }

    /** Joins a project by its share code. On success the caller should refresh. */
    suspend fun joinByCode(projectCode: String): ApiResult<Unit> =
        when (
            val result = api.post<JoinProjectRequest, ActionResponse>(
                url = ApiEndpoints.Project.JOIN,
                body = JoinProjectRequest(projectCode = projectCode.trim()),
                module = ModuleData.DEFAULT,
            )
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(Unit)
        }

    /**
     * Toggles favourite.
     *
     * Writes to Realm **first**, so the star flips instantly and the list re-sorts
     * without waiting on the network. If the call then fails, the local change is rolled
     * back and the error surfaces — the alternative, waiting for the round trip, makes
     * every tap feel broken on a slow connection.
     */
    suspend fun setFavourite(
        projectId: String,
        userId: String,
        favourite: Boolean,
    ): ApiResult<Unit> {
        writeFavouriteLocally(projectId, favourite)

        val result = api.post<FavouriteRequest, ActionResponse>(
            url = ApiEndpoints.Project.FAVOURITE,
            body = FavouriteRequest(projectId = projectId, favourite = favourite),
            module = ModuleData.WITH_PROJECT_ID,
            // The list toggles favourites before any project is opened, so the header
            // must name the project being acted on rather than the (absent) active one.
            projectOverride = SessionStore.ActiveProject(projectId = projectId, userId = userId),
        )

        return when (result) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> {
                writeFavouriteLocally(projectId, !favourite)
                result
            }
        }
    }

    private suspend fun writeFavouriteLocally(projectId: String, favourite: Boolean) {
        realm.write {
            query<ProjectEntity>("projectId == $0", projectId).first().find()
                ?.isFavourite = favourite
        }
    }

    /** Clears cached projects and their badges. Call on logout. */
    suspend fun clear() {
        realm.write { delete(query<ProjectEntity>().find()) }
        badgeManager.clearAll()
    }

    private companion object {
        const val TAG = "ProjectRepository"

        /**
         * Badge first, then favourite, then name.
         *
         * Name is compared lowercase so "apple" and "Apple" do not sort into separate
         * blocks, which is what a raw string comparison would do.
         */
        val DISPLAY_ORDER: Comparator<Project> =
            compareByDescending<Project> { it.unreadCount }
                .thenByDescending { it.isFavourite }
                .thenBy { it.name.lowercase() }
    }
}

@Serializable
private data class DeviceUnreadResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("data") val data: List<DeviceUnreadRow>? = null,
)

@Serializable
private data class DeviceUnreadRow(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("unread") val unread: Int? = null,
)
