package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * One person on one project.
 *
 * **Keyed by `projectId:userId`, not `userId`.** The same person can be on several
 * projects with a different designation, unit and admin flag in each; keying on the user
 * alone would let project B's row overwrite project A's. Every read is therefore scoped
 * by [projectId], and switching projects never shows the previous project's crew.
 *
 * Only fields that more than one module actually reads are given typed columns. v2's
 * `UserData` carries ~120 properties covering transportation, deal memos, mail boxes and
 * more; mirroring all of them here would mean a schema migration every time a tool needs
 * one more. [rawJson] keeps the untouched server object instead, so nothing is lost and a
 * new module can read an extra field without a schema bump — promote a field to a column
 * only once something queries or sorts on it.
 */
class ProjectUserEntity : RealmObject {

    /** `"$projectId:$userId"`. See [key]. */
    @PrimaryKey
    var id: String = ""

    /** Indexed: every directory read filters on it. */
    @Index
    var projectId: String = ""

    @Index
    var userId: String = ""

    var fullName: String = ""
    var firstName: String = ""
    var lastName: String = ""

    var departmentId: String? = null
    var departmentName: String? = null
    var designationId: String? = null
    var designationName: String? = null

    /** Server label key; resolve through the label dictionary before display. */
    var unitName: String? = null
    var unitId: String? = null

    var email: String? = null
    var phone: String? = null
    var countryCode: String? = null

    var profilePictureUrl: String? = null

    /**
     * The server's small copy of the profile picture.
     *
     * Avatars are drawn at 36–56dp, so the thumbnail is the right file to fetch — the full
     * image is a several-megabyte download to fill a circle the size of a fingernail.
     */
    var profileThumbnailKey: String? = null

    var isAdmin: Boolean = false
    var isOwner: Boolean = false
    var enabled: Boolean = true

    /** "active", "pending", … — v2's `status`. */
    var status: String? = null

    /**
     * v2's `keep_name_private`. When set, the person's name must not be shown to other
     * crew, so it is stored as a flag rather than resolved away at write time — the
     * decision belongs to whoever renders it.
     */
    var keepNamePrivate: Boolean = false

    var isExternalUser: Boolean = false

    var updated: Long = 0

    /** The complete server object, for fields without a typed column. */
    var rawJson: String = ""

    companion object {
        fun key(projectId: String, userId: String) = "$projectId:$userId"
    }
}
