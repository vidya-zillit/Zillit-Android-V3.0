package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.ext.realmListOf
import io.realm.kotlin.types.EmbeddedRealmObject
import io.realm.kotlin.types.RealmList
import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * One row of the Chat tab — a person or a room.
 *
 * ### Why people and rooms share a table
 * The Chat tab interleaves them and sorts them against each other, so they have to be
 * comparable in one query. v2 keeps them in `UserDataDb` and `ChatRoomsDB` and then merges
 * and re-sorts in the view model on every change, which is why its list order drifts
 * between the two tabs that show it.
 *
 * The fields that only make sense for one kind are simply empty on the other: a person has
 * no members, a room has no designation.
 */
class CncConversationEntity : RealmObject {

    @PrimaryKey
    var id: String = ""

    /** [com.zillit.zillitapp.feature.cnc.data.ChatSurface] key. */
    @Index
    var surface: String = ""

    @Index
    var projectId: String = ""

    /** The person's user id, or the room id. */
    @Index
    var conversationId: String = ""

    var isGroup: Boolean = false

    var name: String = ""

    /** Designation label key for a person; empty for a room. Resolved at render time. */
    var designation: String = ""

    var pictureKey: String = ""
    var thumbnailKey: String = ""

    /**
     * The other person's device id, for presence.
     *
     * Presence is keyed by device, so a row without one shows no dot. Empty for a room,
     * which never shows presence.
     */
    var deviceId: String = ""

    /**
     * When this conversation last moved, epoch millis.
     *
     * The server's `sorting_activity`, overwritten by the `created` of every message sent
     * or received afterwards. This alone orders the Chat tab.
     */
    @Index
    var sortingActivity: Long = 0

    /** Unread count from the badge tree. Decides membership of the list, never position. */
    var unread: Int = 0

    var favourite: Boolean = false

    /** Project admin, shown as "· Admin" after the name. */
    var isProjectAdmin: Boolean = false

    /** A short preview of the last message, for the row's second line. */
    var lastMessage: String = ""
    var lastMessageType: String = ""

    // -- block and access ------------------------------------------------------

    /** This user blocked the other one. */
    var blockedByMe: Boolean = false

    /** The other one blocked this user. */
    var blockedMe: Boolean = false

    // -- room only -------------------------------------------------------------

    var members: RealmList<CncMemberEntity> = realmListOf()

    /** Who created the room. Only they and group admins may administer it. */
    var ownedBy: String = ""

    /** A room the backend created and nobody may edit or leave. */
    var isSystemDefined: Boolean = false

    /**
     * Set on a department's own room, blank on an ordinary group.
     *
     * This is the whole difference between the Groups list and the Departments list, and
     * without it the two show the same rooms.
     */
    var departmentId: String = ""

    /**
     * A room the server made to hold one ad-hoc call.
     *
     * Never shown in any chat list: it exists so a call between four people has somewhere
     * to live, and it would otherwise appear as a group nobody created.
     */
    var isRandomCallGroup: Boolean = false

    /** False once this user has been removed; history stays readable. */
    var enabled: Boolean = true

    companion object {
        fun key(surface: String, projectId: String, conversationId: String) =
            "$surface:$projectId:$conversationId"
    }
}

/** One person in a room. */
class CncMemberEntity : EmbeddedRealmObject {
    var userId: String = ""
    var name: String = ""
    var designation: String = ""
    var pictureKey: String = ""
    var thumbnailKey: String = ""

    /** May add and remove members, rename the room and change its picture. */
    var isGroupAdmin: Boolean = false

    /** False once removed from the room; kept so past messages still resolve a name. */
    var enabled: Boolean = true
}
