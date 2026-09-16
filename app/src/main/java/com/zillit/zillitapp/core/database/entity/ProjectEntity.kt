package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * Offline cache of a project row.
 *
 * Only the fields the project list actually renders are stored. v2's `ProjectDataModelDB`
 * mirrored all ~50 fields of the API model, which meant every backend field addition
 * became a Realm schema migration whether the app used the field or not.
 */
class ProjectEntity : RealmObject {
    @PrimaryKey
    var projectId: String = ""

    var userId: String = ""
    var projectName: String = ""
    var projectType: String? = null

    /**
     * `entertainment`, `personal` or `other` — the machine value, not the label key.
     *
     * Drives which C&C filters exist: a personal project has no departments and no
     * favourites, so offering the chips would offer two empty lists.
     */
    var projectTypeId: String? = null

    /**
     * This user's standing: `accepted`, `pending`, `removed`.
     *
     * A pending member can see the project but is not yet in it, so Groups and Favourites
     * are withheld until they are accepted — the same rule v2 applies.
     */
    var membershipStatus: String = ""
    var projectSubType: String? = null
    var projectCode: String? = null
    var companyName: String? = null
    var projectLanguage: String? = null

    var isAdmin: Boolean = false
    var isFavourite: Boolean = false
    var enabled: Boolean = true

    /** Soft-delete flags — a project pending deletion still shows with a countdown. */
    var markDeleted: Boolean = false
    var deleteInHours: Long? = null

    var enterpriseClientId: String? = null

    /** Unread count shown as the per-project badge. */
    @Index
    var unread: Int = 0

    var dateCreated: Long? = null

    /**
     * The project's shared "Accounts" mailbox address, when this user may open it.
     *
     * Persisted rather than re-fetched because the email module needs it the moment a
     * project opens — before any request completes — to decide whether to offer the mailbox
     * switcher. Null means the project has none, or that this user is not entitled to it.
     */
    var accountsMailboxEmail: String? = null

    /**
     * The shared mailbox's own BCC presets, comma-joined.
     *
     * Kept apart from the user's: a preset that copies your personal archive has no
     * business applying to mail the whole Accounts department sends.
     */
    var accountsBccPresets: String = ""

    /**
     * The shared mailbox's own server settings, for the credentials sheet.
     *
     * Persisted for the same reason as the address: the sheet has to show them without a
     * request, and the reveal endpoint returns only the password.
     */
    var accountsSmtpHost: String = ""
    var accountsSmtpPort: Int = 0
    var accountsSmtpUserName: String = ""
    var accountsImapHost: String = ""
    var accountsImapPort: Int = 0

    /** When this row was last written locally — used to age out the cache. */
    var cachedAt: Long = 0L
}
