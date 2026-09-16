package com.zillit.zillitapp.core.badge

import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.BadgeEntity
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.RealmQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place badges are read and written, for every module.
 *
 * Everything is a **prefix query over the badge path** ([BadgeKey]): the Home tab total is
 * "everything under `home_label`", a unit's badge is "everything under that unit", the
 * project-list row is "everything in that project". A new module participates by choosing
 * a prefix — no field, no enum branch, no code here.
 *
 * Counts are persisted in Realm so they survive process death, and reads are Flows so a
 * screen re-renders when a socket event changes a count underneath it.
 */
@Singleton
class BadgeManager @Inject constructor(
    private val realmProvider: RealmProvider,
) {

    private val realm get() = realmProvider.realm

    /** Live unread under [prefix]. Emits 0 when nothing matches. */
    fun observeUnder(prefix: BadgeKey, excludeTool: String? = null): Flow<Int> =
        realm.queryUnder(prefix, excludeTool)
            .asFlow()
            .map { change -> change.list.resolveCount() }
            .distinctUntilChanged()

    /**
     * Live unread under [prefix], split by one path component.
     *
     * For a list screen that draws a badge per row — the unit strip, the project list —
     * where one query has to answer for every row at once.
     */
    fun observeGrouped(
        prefix: BadgeKey,
        by: BadgeAxis,
        excludeTool: String? = null,
    ): Flow<Map<String, Int>> =
        realm.queryUnder(prefix, excludeTool)
            .asFlow()
            .map { change ->
                change.list
                    .groupBy(by.selector)
                    .filterKeys { it.isNotEmpty() }
                    .mapValues { (_, rows) -> rows.resolveCount() }
            }
            .distinctUntilChanged()

    /**
     * Live unread under [prefix], split by one component of the **level path**.
     *
     * [observeGrouped] can only split by a named column, and some badge paths carry the
     * thing you need to group by further down. Email is the case that forced this: its rows
     * are `folder → uid → messageId → mailbox address`, so "how many unread in each
     * mailbox" is a grouping on level 3 and cannot be expressed as a prefix.
     *
     * @param levelIndex zero-based. Rows with no component at that depth are skipped.
     */
    fun observeLevelCounts(
        prefix: BadgeKey,
        levelIndex: Int,
        excludeTool: String? = null,
    ): Flow<Map<String, Int>> =
        realm.queryUnder(prefix, excludeTool)
            .asFlow()
            .map { change ->
                change.list
                    .groupBy { row -> row.levelAt(levelIndex) }
                    .filterKeys { it.isNotEmpty() }
                    .mapValues { (_, rows) -> rows.resolveCount() }
            }
            .distinctUntilChanged()

    /** Unread under [prefix] right now, without observing. */
    fun countUnder(prefix: BadgeKey, excludeTool: String? = null): Int =
        realm.queryUnder(prefix, excludeTool).find().resolveCount()

    /**
     * Sets an absolute count for one exact path.
     *
     * [updatedAt] guards against out-of-order delivery: an event older than the row it
     * would overwrite is ignored, so a slow event cannot resurrect a count the user has
     * already cleared.
     */
    suspend fun set(
        key: BadgeKey,
        count: Int,
        updatedAt: Long = System.currentTimeMillis(),
        source: BadgeSource = BadgeSource.REALTIME,
    ) {
        val id = key.rowId(source.key)

        realm.write {
            val existing = query<BadgeEntity>("id == $0", id).first().find()
            if (existing != null && existing.updatedAt > updatedAt) return@write

            copyToRealm(
                BadgeEntity().apply {
                    this.id = id
                    projectId = key.projectId.orEmpty()
                    section = key.section.orEmpty()
                    tool = key.tool.orEmpty()
                    unit = key.unit.orEmpty()
                    levelPath = key.levelPath
                    this.count = count
                    this.updatedAt = updatedAt
                    this.source = source.key
                },
                UpdatePolicy.ALL,
            )
        }
    }

    /**
     * Replaces every row from one origin, atomically.
     *
     * **One transaction on purpose.** Clearing and then setting group by group is a write
     * per group, and Realm emits after each — so every observer saw the badge blank out
     * and then tick back up once per group. On screen that is a badge flickering three or
     * four times each time a project opens.
     *
     * Rebuilding wholesale rather than diffing because the caller derives the full set
     * from the notifications anyway; a diff would be more code for the same result.
     */
    suspend fun replaceSource(source: BadgeSource, counts: Map<BadgeKey, Int>) {
        realm.write {
            delete(query<BadgeEntity>("source == $0", source.key).find())

            val now = System.currentTimeMillis()
            counts.forEach { (key, count) ->
                if (count <= 0) return@forEach
                copyToRealm(
                    BadgeEntity().apply {
                        id = key.rowId(source.key)
                        projectId = key.projectId.orEmpty()
                        section = key.section.orEmpty()
                        tool = key.tool.orEmpty()
                        unit = key.unit.orEmpty()
                        levelPath = key.levelPath
                        this.count = count
                        updatedAt = now
                        this.source = source.key
                    },
                    UpdatePolicy.ALL,
                )
            }
        }
    }

    /** Drops every row under [prefix] — the badge disappears rather than showing zero. */
    suspend fun clearUnder(prefix: BadgeKey) {
        realm.write { delete(queryUnder(prefix).find()) }
    }

    suspend fun clearAll() {
        realm.write { delete(query<BadgeEntity>().find()) }
    }

    /** Drops one origin's rows, leaving the other's intact — see [BadgeSource]. */
    suspend fun clearBySource(source: BadgeSource) {
        realm.write { delete(query<BadgeEntity>("source == $0", source.key).find()) }
    }

    /**
     * Picks the count when a path has rows from both origins.
     *
     * Realtime wins whenever it exists: it is derived from notifications this device has
     * actually received and been able to mark read, so it reflects what the user has seen.
     * The API snapshot is the fallback for a cold start, before any notification has been
     * stored. Summing them would double-count — both describe the same unread items.
     */
    private fun List<BadgeEntity>.resolveCount(): Int {
        val realtime = filter { it.source == BadgeSource.REALTIME.key }
        return if (realtime.isNotEmpty()) {
            realtime.sumOf { it.count }
        } else {
            filter { it.source == BadgeSource.API.key }.sumOf { it.count }
        }
    }
}

/** Which path component [BadgeManager.observeGrouped] splits on. */
enum class BadgeAxis(internal val selector: (BadgeEntity) -> String) {
    PROJECT({ it.projectId }),
    SECTION({ it.section }),
    TOOL({ it.tool }),
    UNIT({ it.unit }),
}

/**
 * Builds the prefix query.
 *
 * Only the components the caller pinned become predicates, so a null is genuinely "any"
 * rather than "empty string". Levels match by prefix, which is what makes a folder's badge
 * include everything nested beneath it.
 */
/**
 * @param excludeTool a tool whose rows this prefix does **not** cover. Needed because a
 *   Home unit's badge deliberately leaves out calendar invitations — see [BadgeTool].
 */
private fun io.realm.kotlin.TypedRealm.queryUnder(
    prefix: BadgeKey,
    excludeTool: String? = null,
): RealmQuery<BadgeEntity> {
    val predicates = mutableListOf<String>()
    val args = mutableListOf<Any>()

    fun pin(field: String, value: String?, operator: String = "==") {
        if (value.isNullOrEmpty()) return
        // Realm positional arguments are indexed in the order they are appended, so the
        // placeholder has to be numbered before the value is added.
        predicates += "$field $operator $${args.size}"
        args += value
    }

    pin("projectId", prefix.projectId)
    pin("section", prefix.section)
    pin("tool", prefix.tool)
    pin("unit", prefix.unit)
    pin("levelPath", prefix.levelPath.takeIf { it.isNotEmpty() }, operator = "BEGINSWITH")
    pin("tool", excludeTool, operator = "!=")

    if (predicates.isEmpty()) return query()
    return query(predicates.joinToString(" AND "), *args.toTypedArray())
}

/**
 * One segment of a row's level path, or empty when it has no such depth.
 *
 * The path is stored joined with a trailing separator (see [BadgeKey.levelPath]), so the
 * split leaves an empty final element that has to be dropped.
 */
private fun BadgeEntity.levelAt(index: Int): String =
    levelPath.split(BadgeKey.SEPARATOR).filter { it.isNotEmpty() }.getOrElse(index) { "" }
