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

    /** When this row was last written locally — used to age out the cache. */
    var cachedAt: Long = 0L
}
