package com.zillit.zillitapp.core.calendar.sync

import com.zillit.zillitapp.core.database.RealmProvider
import com.zillit.zillitapp.core.database.entity.ProjectEntity
import io.realm.kotlin.ext.query
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks up a project's display name for the mirrored event's description.
 *
 * Its own tiny class so the executor does not reach into the project feature from core.
 */
@Singleton
class SyncProjectNames @Inject constructor(
    private val realmProvider: RealmProvider,
) {
    fun name(projectId: String): String? =
        realmProvider.realm
            .query<ProjectEntity>("projectId == $0", projectId)
            .first()
            .find()
            ?.projectName
            ?.takeIf { it.isNotBlank() }
}
