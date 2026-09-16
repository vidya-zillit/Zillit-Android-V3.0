package com.zillit.zillitapp.core.bootstrap

import com.zillit.zillitapp.core.di.ApplicationScope
import com.zillit.zillitapp.core.directory.ProfileResponse
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ModuleData
import com.zillit.zillitapp.core.network.ZillitApi
import com.zillit.zillitapp.core.session.CurrentUserStore
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.cnc.data.CncDirectory
import com.zillit.zillitapp.feature.cnc.data.CncRealtime
import com.zillit.zillitapp.core.chat.ChatSocketBridge
import com.zillit.zillitapp.core.socket.SocketManager
import com.zillit.zillitapp.core.storage.StorageCredentialsStore
import com.zillit.zillitapp.core.directory.MailboxInfo
import com.zillit.zillitapp.core.directory.ProjectUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import com.zillit.zillitapp.core.database.entity.ProjectEntity
import io.realm.kotlin.ext.query

/**
 * The only way a project is opened.
 *
 * v2 opened a project by having each screen fetch what it needed, which is why the order
 * differed by entry point and why some screens rendered before the session was set. Here
 * one call establishes the session, starts the socket, and loads the project's data
 * concurrently — so nothing downstream has to wonder whether the context is ready.
 */
@Singleton
class ProjectBootstrapper @Inject constructor(
    private val api: ZillitApi,
    private val session: SessionStore,
    private val directory: ProjectDirectory,
    private val currentUser: CurrentUserStore,
    private val socketManager: SocketManager,
    private val storageCredentials: StorageCredentialsStore,
    private val chatSocketBridge: ChatSocketBridge,
    private val tokenSession: com.zillit.zillitapp.core.auth.TokenSession,
    private val realmProvider: com.zillit.zillitapp.core.database.RealmProvider,
    private val mailboxContext: com.zillit.zillitapp.feature.email.data.EmailMailboxContext,
    private val mailboxProvisioner: MailboxProvisioner,
    private val emailRealtime: com.zillit.zillitapp.feature.email.data.EmailRealtime,
    private val conversationView: com.zillit.zillitapp.feature.email.data.ConversationViewPreference,
    private val sendQueue: com.zillit.zillitapp.feature.email.data.EmailSendQueue,
    @ApplicationScope private val scope: CoroutineScope,
    private val cncRealtime: CncRealtime,
    private val cncDirectory: CncDirectory,
) {

    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.Idle)
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    /** The in-progress open, cancelled if another begins — opening B must not be finished by A. */
    private var inFlight: Job? = null

    /**
     * Opens [projectId] and loads everything the project screens read.
     *
     * The session is set and the socket started **before** the loads, so events arriving
     * during the fetch are already scoped to the right project.
     */
    fun open(
        projectId: String,
        userId: String,
        enterpriseClientId: String? = null,
        isPendingUser: Boolean = false,
    ) {
        session.setActiveProject(SessionStore.ActiveProject(projectId, userId, enterpriseClientId))
        // Mint the project token ahead of the bootstrap burst. Also the moment a fresh
        // install's device becomes known to the server — create/join registered it — so a
        // probe that answered `libs_invalid_device_id` is retried here.
        tokenSession.onActiveProjectChanged(projectId)
        socketManager.start()
        chatSocketBridge.start()

        inFlight?.cancel()
        inFlight = scope.launch { refresh(projectId, isPendingUser) }
    }

    /** Reloads the project already open — pull-to-refresh, and after a rights change. */
    fun refreshActive(isPendingUser: Boolean = false) {
        val projectId = session.activeProject.value?.projectId ?: return
        inFlight?.cancel()
        inFlight = scope.launch { refresh(projectId, isPendingUser) }
    }

    private suspend fun refresh(projectId: String, isPendingUser: Boolean) {
        _state.value = BootstrapState.Loading(projectId)
        ZillitLog.d(TAG, "Bootstrapping project $projectId (pending=$isPendingUser)")

        // Concurrent: these do not depend on each other, and each writes to its own
        // table. Sequencing them would make the open as slow as the sum of the round trips.
        val profile = scope.async { fetchProfile(projectId) }
        val results = scope.let {
            listOf(
                scope.async { "profile" to profile.await().ok },
                scope.async { "users" to directory.refreshUsers(projectId).isSuccess },
                scope.async { "units" to directory.refreshUnits(projectId).isSuccess },
                scope.async { "tools" to directory.refreshTools(projectId, isPendingUser).isSuccess },
                // Cheap, and the invitee and designation pickers need it the moment they
                // open — a fetch there would put a spinner in front of the one control
                // the user came for.
                scope.async { "departments" to directory.refreshDepartments(projectId).isSuccess },
                // Region + configuration. Without both, every attachment upload fails
                // with an unhelpful "requirements not satisfied" — so they belong in the
                // set of calls that make a project usable, not in the upload path.
                scope.async { "storage" to storageCredentials.refresh() },
            ).awaitAll()
        }

        val failed = results.filterNot { it.second }.map { it.first }

        // A member whose profile carries no mailbox address has never had one provisioned
        // — the backend only creates it on request (v2 asks at create/join; projects made
        // before this app did are still without one). Ask once, then re-read the profile
        // so the Email tab opens on the new address instead of "no mailbox".
        val userId = session.activeProject.value?.userId.orEmpty()
        if (!isPendingUser && profile.await().needsMailbox &&
            mailboxProvisioner.ensure(projectId, userId)
        ) {
            fetchProfile(projectId)
        }

        // Mail: which mailboxes this project has, and start listening for new mail. Both
        // before the Email tab is ever opened, so its badge is right when the user looks.
        publishMailbox(projectId)
        // The grouping preference is per mailbox and comes from the record just published.
        conversationView.refresh()
        emailRealtime.start()

        // Chat & Calling: subscribe before the tab is opened, so a message that arrives
        // while the user is on Home still lands in storage and moves the badge. Idempotent,
        // so re-entering a project does not double the listeners. It also seeds the recent
        // list and names from the directory that was just refreshed above.
        cncRealtime.start()
        cncDirectory.refresh()

        // Anything the outbox still holds from a previous session goes out now, before the
        // user has a chance to wonder where it went.
        sendQueue.flush()

        _state.value = if (failed.isEmpty()) {
            ZillitLog.d(TAG, "Bootstrap complete for $projectId")
            BootstrapState.Ready(projectId)
        } else {
            // Warn rather than error: cached data still renders, and the user can act.
            ZillitLog.w(TAG, "Bootstrap partial for $projectId, failed=$failed")
            BootstrapState.Partial(projectId, failed)
        }
    }

    /**
     * Tells the email module which mailboxes this project has.
     *
     * Done here rather than when the Email tab first opens, because the tab's badge has to
     * be right before anyone taps it — and because an entitlement that has been revoked
     * must drop the user back to their personal mailbox immediately, not on their next
     * request's 403.
     */
    private fun publishMailbox(projectId: String) {
        val project = realmProvider.realm
            .query<ProjectEntity>("projectId == $0", projectId)
            .first()
            .find()

        val accounts = project?.accountsMailboxEmail?.takeIf { it.isNotBlank() }

        // Entitlement and provisioning are the same signal here: the field is populated
        // only for users who may open the mailbox, and absent for everyone else.
        mailboxContext.update(
            accountsMailbox = accounts,
            entitled = accounts != null,
            accountsBccPresets = project?.accountsBccPresets
                .orEmpty()
                .split(",")
                .filter { it.isNotBlank() },
            accountsMailboxInfo = accounts?.let {
                com.zillit.zillitapp.core.directory.MailboxInfo(
                    address = it,
                    smtpHost = project?.accountsSmtpHost.orEmpty(),
                    smtpPort = project?.accountsSmtpPort ?: 0,
                    smtpUserName = project?.accountsSmtpUserName.orEmpty(),
                    imapHost = project?.accountsImapHost.orEmpty(),
                    imapPort = project?.accountsImapPort ?: 0,
                )
            },
        )
    }

    /**
     * The signed-in user's own profile.
     *
     * Fetched separately from the crew list even though the same person appears in it:
     * the profile endpoint returns fields the list omits, and it must land in preferences
     * so a cold start can answer "am I an admin?" before any request completes.
     */
    /**
     * @property ok whether the profile landed in [CurrentUserStore].
     * @property needsMailbox the user wants a Zillit mailbox and has none yet.
     */
    private data class ProfileFetch(val ok: Boolean, val needsMailbox: Boolean = false)

    private suspend fun fetchProfile(projectId: String): ProfileFetch =
        when (val result = api.get<ProfileResponse>(
            ApiEndpoints.User.PROFILE,
            ModuleData.WITH_PROJECT_USER_ID,
        )) {
            is ApiResult.Success -> {
                val dto = result.data.data
                // Checked against the local val rather than `dto?.userId` so the branch
                // below has a non-null dto without a second null check on every field.
                if (dto == null || dto.userId.isNullOrBlank()) {
                    ZillitLog.w(TAG, "Profile response had no user_id")
                    ProfileFetch(ok = false)
                } else {
                    currentUser.update(
                        ProjectUser(
                            userId = dto.userId,
                            projectId = projectId,
                            fullName = dto.fullName
                                ?: listOfNotNull(dto.firstName, dto.lastName)
                                    .joinToString(" ").trim(),
                            firstName = dto.firstName.orEmpty(),
                            lastName = dto.lastName.orEmpty(),
                            departmentId = dto.departmentId,
                            departmentName = dto.departmentName,
                            designationId = dto.designationId,
                            designationName = dto.designationName,
                            unitId = dto.unitId,
                            unitName = dto.unitName,
                            email = dto.email ?: dto.primaryEmail,
                            phone = dto.phone,
                            countryCode = dto.countryCode,
                            profilePictureUrl = dto.profilePicture?.media,
                            profileThumbnailKey = dto.profilePicture?.thumbnail,
                            isAdmin = dto.isAdmin ?: false,
                            isOwner = dto.isOwner ?: false,
                            enabled = dto.enabled ?: true,
                            status = dto.status,
                            keepNamePrivate = dto.keepNamePrivate ?: false,
                            isExternalUser = dto.isExternalUser ?: false,
                            updated = dto.updated ?: 0,
                            // Only this endpoint returns the mailbox; the crew list omits
                            // it, which is why the email module reads it from the profile.
                            mailbox = dto.mailbox
                                ?.takeIf { !it.emailAddress.isNullOrBlank() }
                                ?.let { mailbox ->
                                    MailboxInfo(
                                        address = mailbox.emailAddress.orEmpty(),
                                        name = mailbox.name.orEmpty(),
                                        smtpHost = mailbox.smtpHost.orEmpty(),
                                        smtpPort = mailbox.smtpPort ?: 0,
                                        smtpUserName = mailbox.smtpUserName.orEmpty(),
                                        imapHost = mailbox.imapHost.orEmpty(),
                                        imapPort = mailbox.imapPort ?: 0,
                                        imapUserName = mailbox.imapUserName.orEmpty(),
                                        conversationView = mailbox.conversationView ?: false,
                                        bccPresets = mailbox.bcc.map { it.emailAddress }
                                            .filter { it.isNotBlank() },
                                    )
                                },
                            bccPresets = dto.bcc.map { it.emailAddress }
                                .filter { it.isNotBlank() },
                            rawJson = "",
                        ),
                    )
                    ProfileFetch(
                        ok = true,
                        needsMailbox = dto.mailbox?.emailAddress.isNullOrBlank() &&
                            dto.zillitEmailEnable != false,
                    )
                }
            }

            is ApiResult.Failure -> ProfileFetch(ok = false)
        }

    private val <T> ApiResult<T>.isSuccess: Boolean
        get() = this is ApiResult.Success

    private companion object {
        const val TAG = "ProjectBootstrap"
    }
}

/**
 * How far opening a project has got.
 *
 * [Partial] exists because "some of it failed" is the common case on a bad connection and
 * is genuinely different from failure: the cached data still renders and the user can keep
 * working, so collapsing it into an error state would block them for no reason. It names
 * which calls failed so a retry can be specific.
 */
sealed interface BootstrapState {
    data object Idle : BootstrapState
    data class Loading(val projectId: String) : BootstrapState
    data class Ready(val projectId: String) : BootstrapState
    data class Partial(val projectId: String, val failed: List<String>) : BootstrapState
}
