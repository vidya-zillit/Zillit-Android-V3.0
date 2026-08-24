package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * A tool enabled on a project, together with this user's access to it.
 *
 * Keyed on `projectId:identifier` rather than the tool's `_id`: [identifier] is the
 * stable name the app branches on (`drive`, `catering`, `docdistribution`), while the id
 * differs per project. Keying on identifier is what lets a feature ask "can I open Drive
 * here?" without first looking up an id it has no way to know.
 */
class ProjectToolEntity : RealmObject {

    @PrimaryKey
    var id: String = ""

    @Index
    var projectId: String = ""

    /** e.g. "drive", "catering". The key features branch on. */
    @Index
    var identifier: String = ""

    /** Server-assigned id for this tool within this project. */
    var toolId: String? = null

    /** Server label key; resolve before display. */
    var toolName: String = ""

    var enabled: Boolean = true
    var viewAccess: Boolean = true
    var postingAccess: Boolean = true
    var downloadAccess: Boolean = true
    var adminAccess: Boolean = false

    var sortOrder: Int = 0

    var rawJson: String = ""

    companion object {
        fun key(projectId: String, identifier: String) = "$projectId:$identifier"
    }
}
