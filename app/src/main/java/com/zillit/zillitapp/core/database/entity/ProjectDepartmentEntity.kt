package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * A department on a project — Camera, Art, Production and so on.
 *
 * Keyed by `projectId:departmentId` like every other directory row: departments are
 * configured per project, and two projects can carry the same department under different
 * ids.
 *
 * The list is small and changes rarely, but it is cached rather than fetched per screen
 * because the invitee picker needs it the instant it opens, and a spinner there would sit
 * in front of the one control the user came for.
 */
class ProjectDepartmentEntity : RealmObject {

    @PrimaryKey
    var id: String = ""

    @Index
    var projectId: String = ""

    @Index
    var departmentId: String = ""

    /**
     * Server label key (e.g. `department_camera`), not display text.
     *
     * Resolve through the label dictionary before showing it — v2 pipes every department
     * name through `getDataFromLabelKey()` for exactly this reason.
     */
    var departmentName: String = ""

    /** Stable identifier used to special-case the built-in departments. */
    var identifier: String? = null

    var systemDefined: Boolean = false

    /** Server order, preserved as an index — Realm results have no inherent ordering. */
    var sortOrder: Int = 0

    /** The untouched server object, so nested designations survive without a column. */
    var rawJson: String = ""

    companion object {
        fun key(projectId: String, departmentId: String) = "$projectId:$departmentId"
    }
}
