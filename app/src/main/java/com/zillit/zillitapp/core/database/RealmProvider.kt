package com.zillit.zillitapp.core.database

import com.zillit.zillitapp.core.database.entity.ApiLogEntity
import com.zillit.zillitapp.core.database.entity.CalendarSyncJobEntity
import com.zillit.zillitapp.core.database.entity.CncAttachmentEntity
import com.zillit.zillitapp.core.database.entity.CncConversationEntity
import com.zillit.zillitapp.core.database.entity.CncMemberEntity
import com.zillit.zillitapp.core.database.entity.CncMessageElementEntity
import com.zillit.zillitapp.core.database.entity.CncMessageEntity
import com.zillit.zillitapp.core.database.entity.CncReactionEntity
import com.zillit.zillitapp.core.database.entity.CncReplyEntity
import com.zillit.zillitapp.core.database.entity.PendingEmailEntity
import com.zillit.zillitapp.core.database.entity.PendingReceiptEntity
import com.zillit.zillitapp.core.database.entity.PendingLogEntity
import com.zillit.zillitapp.core.database.entity.ChatMessageEntity
import com.zillit.zillitapp.core.database.entity.PendingUploadEntity
import com.zillit.zillitapp.core.database.entity.ChatReplyEntity
import com.zillit.zillitapp.core.database.entity.EmailAttachmentEntity
import com.zillit.zillitapp.core.database.entity.EmailEntity
import com.zillit.zillitapp.core.database.entity.EmailFolderEntity
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
         * 18: Email/EmailAttachment/EmailFolder entities added — the mail cache. New
         *     classes, so purely additive.
         * 19: ProjectEntity.accountsMailboxEmail added; existing rows default to null,
         *     which reads correctly as "this project has no shared mailbox".
         * 20: ProjectEntity.accountsBccPresets added; empty on existing rows, which reads
         *     correctly as "no presets".
         * 21: ProjectEntity gained the shared mailbox's SMTP/IMAP settings. Empty and zero
         *     on existing rows; the next project refresh fills them.
         * 22: PendingEmailEntity added — the outbox. A new class, so purely additive.
         * 23: ProjectToolEntity gained groupIdentifier and the tool/home/sub-unit flags,
         *     which the tools endpoint already sent. Blank and false on existing rows; the
         *     next project refresh fills them.
         * 24: ProjectToolEntity gained the three `*_updatable` rights, also already sent.
         * 25: C&C storage added — CncMessageEntity and CncConversationEntity with their
         *     embedded children. New classes, so purely additive. Deliberately separate
         *     from ChatMessageEntity: see the note on CncMessageEntity for why a socket
         *     chat cannot share a table with a unit chat.
         * 26: ProjectUserEntity.deviceId added — the device the person is signed in on,
         *     which presence is keyed by. Blank on existing rows; the next project refresh
         *     fills it. Its own version because 25 had already shipped to devices: adding a
         *     property under a version that is already on disk is exactly what
         *     RLM_ERR_SCHEMA_MISMATCH is, and it kills the app at launch.
         * 27: PendingUploadEntity gained deliveryKind, isGroup and receiverDeviceId, so the
         *     one upload queue can finish a socket-chat send as well as a unit-chat POST.
         *     Existing rows default to the unit kind, which is what they were.
         * 28: ProjectUserEntity.sortingActivity added — the Chat tab's order key, already
         *     sent by the users endpoint and never read. Zero on existing rows; the next
         *     project refresh fills it.
         * 29: PendingReceiptEntity added — read and delivered receipts that could not be
         *     emitted, so a receipt lost to a dead socket is re-sent rather than leaving the
         *     sender's message on one tick forever. A new class, so purely additive.
         * 30: PendingUploadEntity gained the location columns, so a shared pin can go
         *     through the same queue as any other attachment — which is what it is, plus
         *     coordinates. Zero on existing rows, which reads correctly as "not a location".
         * - **31**: `CncMessageEntity.readByCount`. A group's "Read by N" comes off the
         *   message itself; there is no endpoint that answers it per room. Zero on existing
         *   rows, which reads correctly as "nobody yet" until the next fetch fills it in.
         * - **32**: `ProjectEntity.projectTypeId` and `.membershipStatus`, and
         *   `CncConversationEntity.departmentId` and `.isRandomCallGroup`. Together these
         *   are what the C&C filter chips are decided from: which chips exist at all, and
         *   which rooms belong under Groups rather than Departments. Blank and false on
         *   existing rows, which reads as "an ordinary group in an ordinary project" until
         *   the next refresh — the same as before the columns existed.
         * - **33**: `ProjectUserEntity.showsLocation`, `.lastLatitude`, `.lastLongitude`.
         *   A profile offers to show where somebody is only when they share it, which is a
         *   consent signal and not something to infer from whether a position happens to be
         *   stored. False and zero on existing rows, so the action stays hidden until the
         *   next directory refresh says otherwise.
         * - **34**: `EmailEntity.draftUniqueId` — the `unique_id` a draft was created with,
         *   so a draft can be stored and found before it has a server id. Empty on every
         *   existing row, which is correct: they are all server drafts or ordinary mail.
         */
        const val SCHEMA_VERSION = 34L

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
            EmailEntity::class,
            EmailAttachmentEntity::class,
            EmailFolderEntity::class,
            PendingEmailEntity::class,
            PendingReceiptEntity::class,
            CncMessageEntity::class,
            CncAttachmentEntity::class,
            CncReplyEntity::class,
            CncReactionEntity::class,
            CncMessageElementEntity::class,
            CncConversationEntity::class,
            CncMemberEntity::class,
        )
    }
}
