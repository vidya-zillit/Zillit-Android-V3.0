package com.zillit.zillitapp.core.help

import com.zillit.zillitapp.BuildConfig
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.session.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/** The two links behind a ⓘ. Either can be absent — not every topic has both. */
data class HelpLinksFor(
    val videoUrl: String? = null,
    val moreUrl: String? = null,
)

/**
 * Where a help topic's video and documentation live **for the person asking**.
 *
 * Both answers depend on the reader. An admin sees a different Edit My Profile tutorial
 * because their screen has fields a member's does not, and the documentation site serves a
 * different page per audience — `?for=admin`, `?for=accounts`, or neither. v2 threads the
 * same two rules through `getVideoUrl()` and `getEndPoint()`, reading the signed-in user out
 * of a global; here they are resolved in one place from the directory.
 */
@Singleton
class HelpLinks @Inject constructor(
    private val directory: ProjectDirectory,
    private val session: SessionStore,
) {

    fun linksFor(topic: HelpTopic): HelpLinksFor = HelpLinksFor(
        videoUrl = videoUrl(topic),
        moreUrl = moreUrl(topic),
    )

    /**
     * The tutorial, if the topic has one.
     *
     * An admin gets [HelpTopic.adminVideo] when one was recorded; everyone else gets the
     * ordinary one. v2 falls back to a placeholder clip for unmapped keys — deliberately not
     * copied, because a "video" that explains nothing is worse than no button at all.
     */
    fun videoUrl(topic: HelpTopic): String? {
        val path = topic.adminVideo?.takeIf { isAdmin() } ?: topic.video ?: return null
        return BuildConfig.VIDEO_BASE_URL + path
    }

    /**
     * The documentation page, addressed to the right audience.
     *
     * The Accounts department has its own version of several pages, so it wins over the
     * plain admin/member split — the same precedence v2 uses.
     */
    fun moreUrl(topic: HelpTopic): String? {
        val anchor = topic.docsAnchor ?: return null

        val audience = when {
            departmentIdentifier() == DEPARTMENT_ACCOUNTS -> "?for=accounts"
            isAdmin() -> "?for=admin"
            else -> ""
        }

        return "${BuildConfig.DOCS_URL}$audience#$anchor"
    }

    private fun currentUser() = session.activeProject.value?.let { directory.findUser(it.userId, it.projectId) }

    private fun isAdmin(): Boolean = currentUser()?.isAdmin == true

    private fun departmentIdentifier(): String? {
        val project = session.activeProject.value ?: return null
        val departmentId = currentUser()?.departmentId ?: return null
        return directory.findDepartment(departmentId, project.projectId)?.identifier
    }

    private companion object {
        /** v2's `Constants.ACCOUNT_IDENTIFIER`. */
        const val DEPARTMENT_ACCOUNTS = "department_accounts"
    }
}
