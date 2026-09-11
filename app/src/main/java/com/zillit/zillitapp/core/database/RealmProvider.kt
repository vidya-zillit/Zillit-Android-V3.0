package com.zillit.zillitapp.core.database

import com.zillit.zillitapp.core.database.entity.ApiLogEntity
import com.zillit.zillitapp.core.database.entity.CalendarSyncJobEntity
import com.zillit.zillitapp.core.database.entity.PendingLogEntity
import com.zillit.zillitapp.core.database.entity.ChatMessageEntity
import com.zillit.zillitapp.core.database.entity.PendingUploadEntity
import com.zillit.zillitapp.core.database.entity.ChatReplyEntity
import com.zillit.zillitapp.core.database.entity.NativeCalendarMappingEntity
import com.zillit.zillitapp.core.database.entity.ProjectDepartmentEntity
import com.zillit.zillitapp.core.database.entity.ProjectToolEntity
import com.zillit.zillitapp.core.database.entity.ProjectUnitEntity
import com.zillit.zillitapp.core.database.entity.ProjectUserEntity
import com.zillit.zillitapp.core.database.entity.BadgeEntity
import com.zillit.zillitapp.core.database.entity.NotificationEntity
import com.zillit.zillitapp.core.database.entity.ProjectEntity
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.migration.AutomaticSchemaMigration
import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.TypedRealmObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * Owns the single [Realm] instance.
 *
 * Two deliberate departures from v2:
 *
 *  1. **Realm Kotlin SDK, not Realm Java.** v2 uses `io.realm:realm-gradle-plugin`
 *     (Realm Java 10.19), whose results are live, thread-confined objects that are not
 *     Flow-native — every one of its ~25 DB managers hand-writes two-way mapping between
 *     a `RealmObject` and a plain model to escape that. The Kotlin SDK exposes queries
 *     as coroutine [kotlinx.coroutines.flow.Flow]s, which is what lets a ViewModel expose
 *     DB state as a `StateFlow` that survives rotation. It also needs no kapt.
 *
 *  2. **A different file name.** v2 writes `zillit.realm`. Because v3 keeps the same
 *     `applicationId`, an installed v2 will be *upgraded* in place and its Realm file
 *     will still be on disk — and v2's config uses `deleteRealmIfMigrationNeeded()`,
 *     which would silently wipe it. Writing to `zillit-v3.realm` keeps the two apart so
 *     neither can destroy the other's data during the transition.
 */
@Singleton
class RealmProvider @Inject constructor() {

    val realm: Realm by lazy { Realm.open(configuration) }

    private val configuration: RealmConfiguration
        get() = RealmConfiguration.Builder(schema = SCHEMA)
            .name(REALM_FILE_NAME)
            .schemaVersion(SCHEMA_VERSION)
            // Still no deleteRealmIfMigrationNeeded() — a schema change must never
            // silently wipe user data, which is exactly what v2's config does.
            //
            // AutomaticSchemaMigration handles purely additive changes: new properties
            // take their Kotlin default on existing rows. A migration that needs to
            // *transform* data has to fill in the block explicitly, per version.
            .migration(
                AutomaticSchemaMigration {
                    // v1 -> v2: BadgeEntity.source added.
                    // v7 -> v8: PendingUploadEntity gained replyToServerId.
                    // v6 -> v7: PendingUploadEntity gained the thumbnail columns.
                    // v5 -> v6: PendingUploadEntity added — the durable upload queue.
                    // v4 -> v5: ChatMessage/ChatReply entities added.
                    // v3 -> v4: ProjectUser/Unit/Tool entities added. New classes need no
                    // data transform — existing rows are untouched.
                },
            )
            .build()

    fun close() {
        if (!realm.isClosed()) realm.close()
    }

    private companion object {
        const val REALM_FILE_NAME = "zillit-v3.realm"
        /**
         * Bump on every schema change, or Realm throws RLM_ERR_SCHEMA_MISMATCH at open
         * and the app dies on launch.
         *
         * 2: added BadgeEntity.source
         * 3: BadgeEntity primary key now includes source
         */
        /**
         * 11: BadgeEntity re-keyed from `module:scopeId` to the backend's badge path.
         * 12: ProjectDepartmentEntity added — a new class, so purely additive.
         * 13: NotificationEntity.eventEndAt added; existing rows default to 0.
         * 14: NativeCalendarMappingEntity + CalendarSyncJobEntity added — device-calendar
         *     mirroring. New classes, so purely additive.
         */
        const val SCHEMA_VERSION = 17L

        /** Every persisted entity must be listed here or queries on it throw. */
        val SCHEMA: Set<KClass<out TypedRealmObject>> = setOf(
            ProjectEntity::class,
            BadgeEntity::class,
            NotificationEntity::class,
            ApiLogEntity::class,
            ProjectUserEntity::class,
            ProjectUnitEntity::class,
            ProjectToolEntity::class,
            ProjectDepartmentEntity::class,
            ChatMessageEntity::class,
            ChatReplyEntity::class,
            PendingUploadEntity::class,
            NativeCalendarMappingEntity::class,
            CalendarSyncJobEntity::class,
            PendingLogEntity::class,
        )
    }
}
