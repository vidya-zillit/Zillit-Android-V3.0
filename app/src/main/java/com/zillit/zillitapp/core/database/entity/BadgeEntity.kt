package com.zillit.zillitapp.core.database.entity

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey

/**
 * One unread count, addressed by the **path the backend uses**.
 *
 * `projectId → section → tool → unit → levelPath` is the same address the server sends
 * notifications with and expects back on mark-read, so no translation layer can drift.
 *
 * v2 modelled badges as a five-level nested tree of positionally-named classes —
 * `Data` → `SubData` → `SubSubData` → … — and then flattened it into 238 named counters,
 * which is why it took thousands of lines to keep them consistent. Here the levels are
 * data, so a new module or a deeper folder needs no new field and no new branch.
 */
class BadgeEntity : RealmObject {
    /** `"$projectId|$section|$tool|$unit|$levelPath|$source"` — see `BadgeKey.rowId`. */
    @PrimaryKey
    var id: String = ""

    @Index
    var projectId: String = ""

    @Index
    var section: String = ""

    var tool: String = ""

    var unit: String = ""

    /**
     * `level_1`, `level_2`, … joined and terminated with `/`; empty when the path stops
     * at the unit.
     *
     * One string rather than a list so "everything below this point" is a single
     * `BEGINSWITH` — Realm cannot prefix-match a list, and fixed `level_1/2/3` columns
     * cannot express open-ended depth at all.
     */
    var levelPath: String = ""

    var count: Int = 0

    /** Server timestamp of the update, used to drop out-of-order socket events. */
    var updatedAt: Long = 0L

    /**
     * Where this count came from — see `BadgeSource`.
     *
     * Kept because the two origins have different authority. An API count is a snapshot
     * the server computed; a realtime count is derived from notifications this device has
     * actually received and been able to mark read. Without the distinction, a full resync
     * cannot tell which rows it may rebuild and which it must leave alone, and one
     * silently erases the other — which is exactly what happened to the project-list badge
     * before the source was part of the key.
     */
    var source: String = ""
}
