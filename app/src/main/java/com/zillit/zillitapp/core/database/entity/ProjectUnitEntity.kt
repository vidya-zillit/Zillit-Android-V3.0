package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * A Home unit — each one its own chat thread.
 *
 * Keyed by `projectId:unitId` for the same reason as [ProjectUserEntity]: access flags
 * and ordering are per project, and a unit id is only unique within one.
 */
class ProjectUnitEntity : RealmObject {

    @PrimaryKey
    var id: String = ""

    @Index
    var projectId: String = ""

    @Index
    var unitId: String = ""

    /** Server label key (e.g. `home_unit_notices`); resolve before display. */
    var unitName: String = ""

    /** Stable server identifier used to special-case built-in units. */
    var identifier: String? = null

    var viewAccess: Boolean = true

    /** v2's `posting_access`. False means the composer is replaced by a notice. */
    var postingAccess: Boolean = true

    var downloadAccess: Boolean = true

    var privateUnit: Boolean = false
    var systemDefined: Boolean = false
    var subUnits: Boolean = false

    var enabled: Boolean = true

    var created: Long = 0
    var updated: Long = 0

    /** Display order within the strip. Server order is preserved via this index. */
    var sortOrder: Int = 0

    var rawJson: String = ""

    companion object {
        fun key(projectId: String, unitId: String) = "$projectId:$unitId"
    }
}
