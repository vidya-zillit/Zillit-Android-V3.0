package com.zillit.zillitapp.core.bootstrap

import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the backend to create the user's Zillit mailbox for a project.
 *
 * The mailbox is not created with the membership. v2 sends `PATCH user/mail-box` the
 * moment a project is created or joined (`CommonApis.mailBoxApiHit`), and that call is
 * the only thing that provisions it — a project whose creator skipped it has an empty
 * `mail_box_detail` forever, and the mail service answers every request for it with
 * `email_credentials_not_available`. Every project created by this app before the call
 * existed is in that state, so [ensure] is also run from the bootstrap whenever the
 * profile comes back without an address.
 *
 * One attempt per project per process: the outcome is a server-side fact, and a refusal
 * (the user opted out, the project has no mail domain) would otherwise be re-asked on
 * every open. Concurrent callers for the same project share the one request.
 */
@Singleton
class MailboxProvisioner @Inject constructor(
    private val api: ZillitApi,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val lock = Mutex()
    private val attempts = mutableMapOf<String, Deferred<Boolean>>()

    /**
     * Provisions the mailbox for [userId] in [projectId] unless already attempted.
     *
     * The project is passed explicitly rather than read from the session: right after
     * create the new project is not active yet, and a header stamped with the previous
     * project would provision the wrong mailbox.
     *
     * @return true when the server accepted the request — the caller should re-read the
     * profile to pick up the new address.
     */
    suspend fun ensure(projectId: String, userId: String): Boolean {
        if (projectId.isBlank() || userId.isBlank()) return false
        val attempt = lock.withLock {
            attempts.getOrPut(projectId) { scope.async { provision(projectId, userId) } }
        }
        return attempt.await()
    }

    private suspend fun provision(projectId: String, userId: String): Boolean =
        when (
            val result = api.patch<MailboxRequest, MailboxResponse>(
                url = ApiEndpoints.User.MAIL_BOX,
                body = MailboxRequest(userId),
                module = ModuleData.WITH_PROJECT_USER_ID,
                projectOverride = SessionStore.ActiveProject(projectId, userId),
            )
        ) {
            is ApiResult.Success -> {
                ZillitLog.d(TAG, "mailbox provisioned for $userId in $projectId: ${result.data.message}")
                true
            }

            is ApiResult.Failure -> {
                ZillitLog.w(TAG, "mailbox provisioning refused for $projectId: ${result.error}")
                false
            }
        }

    @Serializable
    data class MailboxRequest(
        @SerialName("user_id") val userId: String,
    )

    @Serializable
    data class MailboxResponse(
        @SerialName("status") val status: Int = 0,
        @SerialName("message") val message: String? = null,
    )

    private companion object {
        const val TAG = "MailboxProvisioner"
    }
}
