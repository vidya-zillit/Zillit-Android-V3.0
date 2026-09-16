package com.zillit.zillitapp.feature.email.data

import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ZillitApi
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The mail service, wrapped.
 *
 * Exists for two reasons that would otherwise be repeated at forty call sites:
 *
 *  1. **The envelope decides success, not the HTTP code.** A 200 carrying `status: 0` is a
 *     failure whose `message` is a label key. [unwrap] turns that into an ordinary
 *     [ApiResult.Failure] so callers can use the same handling as everything else in the
 *     app.
 *  2. **The accounts-mailbox flag.** It is a query parameter on every verb — including POST
 *     and PUT — so bodies, and therefore the body-hash signature, stay untouched. It is
 *     never sent as `false`, and two families of call deviate: email groups are
 *     project-scoped and never carry it, and rule *writes* put it in the body as the string
 *     `"true"`. Encoding that here means a call site cannot get it wrong.
 */
@Singleton
class EmailApi @Inject constructor(
    private val api: ZillitApi,
    private val mailbox: EmailMailboxContext,
) {

    // ── Folders ──────────────────────────────────────────────────────────────

    suspend fun folders(): ApiResult<List<FolderDto>> =
        api.get<EmailEnvelope<List<FolderDto>>>(
            ApiEndpoints.Email.FOLDERS,
            query = accountsQuery(),
        ).unwrap()

    suspend fun createFolder(name: String): ApiResult<FolderDto> =
        api.post<FolderRequest, EmailEnvelope<FolderDto>>(
            ApiEndpoints.Email.FOLDERS,
            FolderRequest(folderName = name),
            query = accountsQuery(),
        ).unwrap()

    suspend fun renameFolder(from: String, to: String): ApiResult<FolderDto> =
        api.put<FolderRequest, EmailEnvelope<FolderDto>>(
            ApiEndpoints.Email.FOLDERS,
            FolderRequest(folderName = from, newFolderName = to),
            query = accountsQuery(),
        ).unwrap()

    /**
     * Deletes a folder.
     *
     * A DELETE **with a body**, which is unusual but is the service's shape.
     */
    suspend fun deleteFolder(name: String): ApiResult<Unit> =
        api.deleteWithBody<FolderRequest, EmailEnvelope<JsonObject>>(
            ApiEndpoints.Email.FOLDERS,
            FolderRequest(folderName = name),
            query = accountsQuery(),
        ).unwrapUnit()

    // ── Messages ─────────────────────────────────────────────────────────────

    /**
     * The folder's complete uid list.
     *
     * @param accounts captured by the caller at the start of a sync, so a mailbox switch
     *   part-way through cannot tag half the rows with the wrong scope.
     */
    suspend fun folderUids(folderName: String, accounts: Boolean): ApiResult<List<FolderUidDto>> =
        api.post<FolderUidsRequest, EmailEnvelope<List<FolderUidDto>>>(
            ApiEndpoints.Email.FOLDER_UIDS,
            FolderUidsRequest(folderName),
            query = accountsQuery(accounts),
        ).unwrap()

    /** Headers for one batch of uids. Bodies come from [trail]. */
    suspend fun emailIndex(
        uids: List<Int>,
        folderName: String,
        accounts: Boolean,
    ): ApiResult<EmailIndexResponse> =
        api.post<EmailIndexRequest, EmailEnvelope<EmailIndexResponse>>(
            ApiEndpoints.Email.EMAIL_INDEX,
            EmailIndexRequest(uids, folderName),
            query = accountsQuery(accounts),
        ).unwrap()

    /** Full messages — what opening a mail or a conversation fetches. */
    suspend fun trail(folderName: String, messageIds: List<String>): ApiResult<List<EmailDto>> =
        api.post<EmailTrailRequest, EmailEnvelope<List<EmailDto>>>(
            ApiEndpoints.Email.EMAIL_TRAIL,
            EmailTrailRequest(folderName, messageIds),
            query = accountsQuery(),
        ).unwrap()

    /**
     * @param useAccountsMailbox which mailbox this goes out from. Passed explicitly rather
     *   than read from the current selection, because a queued mail is delivered later —
     *   possibly after the user has switched mailboxes — and must go out from the one it
     *   was written in.
     */
    suspend fun send(
        request: SendEmailRequest,
        useAccountsMailbox: Boolean = mailbox.useAccountsParam,
    ): ApiResult<Unit> =
        api.post<SendEmailRequest, EmailEnvelope<JsonObject>>(
            ApiEndpoints.Email.SEND,
            request,
            query = accountsQuery(useAccountsMailbox),
        ).unwrapUnit()

    suspend fun move(
        messageIds: List<String>,
        source: String,
        target: String,
    ): ApiResult<Unit> =
        api.put<MoveEmailsRequest, EmailEnvelope<JsonObject>>(
            ApiEndpoints.Email.EMAILS,
            MoveEmailsRequest(messageIds, source, target),
            query = accountsQuery(),
        ).unwrapUnit()

    suspend fun delete(messageIds: List<String>): ApiResult<Unit> =
        api.deleteWithBody<DeleteEmailsRequest, EmailEnvelope<JsonObject>>(
            ApiEndpoints.Email.EMAILS,
            DeleteEmailsRequest(messageIds),
            query = accountsQuery(),
        ).unwrapUnit()

    suspend fun emptyTrash(): ApiResult<Unit> =
        api.delete<EmailEnvelope<JsonObject>>(
            ApiEndpoints.Email.EMPTY_TRASH,
            query = accountsQuery(),
        ).unwrapUnit()

    /** The whole file, base64 inside the JSON. No streaming and no range support. */
    suspend fun attachment(
        attachmentId: String,
        messageId: String,
        folderName: String,
    ): ApiResult<AttachmentDataDto> =
        api.post<AttachmentRequest, EmailEnvelope<AttachmentDataDto>>(
            ApiEndpoints.Email.ATTACHMENT,
            AttachmentRequest(attachmentId, messageId, folderName),
            query = accountsQuery(),
        ).unwrap()

    // ── Drafts ───────────────────────────────────────────────────────────────

    suspend fun saveDraft(request: SaveDraftRequest): ApiResult<DraftDto> =
        api.post<SaveDraftRequest, EmailEnvelope<DraftDto>>(
            ApiEndpoints.Email.DRAFTS,
            request,
            query = accountsQuery(),
        ).unwrap()

    suspend fun updateDraft(draftId: String, request: SaveDraftRequest): ApiResult<Unit> =
        api.put<SaveDraftRequest, EmailEnvelope<DraftDto>>(
            ApiEndpoints.Email.draft(draftId),
            request,
            query = accountsQuery(),
        ).unwrapUnit()

    suspend fun deleteDrafts(draftIds: List<String>): ApiResult<Unit> =
        api.deleteWithBody<DeleteDraftsRequest, EmailEnvelope<JsonObject>>(
            ApiEndpoints.Email.DRAFTS,
            DeleteDraftsRequest(draftIds),
            query = accountsQuery(),
        ).unwrapUnit()

    /**
     * Drafts, newest first.
     *
     * Cursor-by-timestamp rather than a page number, and there is only ever one page — the
     * service has no continuation for this, so the cursor's only job is to mean "now".
     *
     * **It must not be this device's "now".** The `updated` stamps it is compared against
     * come from the server's clock, and a device whose clock merely lags — a minute is
     * ordinary, and the dev server runs about that far ahead — asks for everything before a
     * moment the server considers past. A draft saved seconds earlier then falls outside
     * the page and reads as deleted: open a draft, close it, watch the row disappear while
     * the draft sits perfectly intact on the server. The margin costs nothing, because
     * there is no page after this one to skip into.
     */
    suspend fun drafts(
        before: Long = System.currentTimeMillis() + CURSOR_MARGIN_MS,
    ): ApiResult<DraftListDto> =
        api.get<EmailEnvelope<DraftListDto>>(
            ApiEndpoints.Email.draftsBefore(before),
            query = accountsQuery(),
        ).unwrap()

    // ── Contacts ─────────────────────────────────────────────────────────────

    suspend fun contacts(): ApiResult<List<ContactDto>> =
        api.get<EmailEnvelope<List<ContactDto>>>(
            ApiEndpoints.Email.CONTACTS,
            query = accountsQuery(),
        ).unwrap()

    /** Creating takes a list even for one, and answers with a list. */
    suspend fun createContact(contact: ContactDto): ApiResult<List<ContactDto>> =
        api.post<CreateContactsRequest, EmailEnvelope<List<ContactDto>>>(
            ApiEndpoints.Email.CONTACTS,
            CreateContactsRequest(listOf(contact)),
            query = accountsQuery(),
        ).unwrap()

    suspend fun updateContact(id: String, contact: ContactDto): ApiResult<ContactDto> =
        api.put<ContactDto, EmailEnvelope<ContactDto>>(
            ApiEndpoints.Email.contact(id),
            contact,
            query = accountsQuery(),
        ).unwrap()

    suspend fun deleteContact(id: String): ApiResult<Unit> =
        api.delete<EmailEnvelope<JsonObject>>(
            ApiEndpoints.Email.contact(id),
            query = accountsQuery(),
        ).unwrapUnit()

    // ── Signatures ───────────────────────────────────────────────────────────

    suspend fun signatures(): ApiResult<List<SignatureDto>> =
        api.get<EmailEnvelope<List<SignatureDto>>>(
            ApiEndpoints.Email.SIGNATURES,
            query = accountsQuery(),
        ).unwrap()

    suspend fun createSignature(title: String, body: String): ApiResult<SignatureDto> =
        api.post<CreateSignatureRequest, EmailEnvelope<SignatureDto>>(
            ApiEndpoints.Email.SIGNATURE_CREATE,
            CreateSignatureRequest(title, body),
            query = accountsQuery(),
        ).unwrap()

    suspend fun updateSignature(
        id: String,
        title: String,
        body: String,
    ): ApiResult<SignatureDto> =
        api.put<UpdateSignatureRequest, EmailEnvelope<SignatureDto>>(
            ApiEndpoints.Email.SIGNATURE_UPDATE,
            UpdateSignatureRequest(id, title, body),
            query = accountsQuery(),
        ).unwrap()

    suspend fun deleteSignature(id: String): ApiResult<Unit> =
        api.delete<EmailEnvelope<JsonObject>>(
            ApiEndpoints.Email.signature(id),
            query = accountsQuery(),
        ).unwrapUnit()

    // ── Groups — project-scoped, so never the accounts flag ──────────────────

    suspend fun groups(): ApiResult<List<EmailGroupDto>> =
        api.get<EmailEnvelope<List<EmailGroupDto>>>(ApiEndpoints.Email.GROUPS).unwrap()

    suspend fun createGroup(name: String, members: List<String>): ApiResult<EmailGroupDto> =
        api.post<CreateGroupRequest, EmailEnvelope<EmailGroupDto>>(
            ApiEndpoints.Email.GROUPS,
            CreateGroupRequest(name, members),
        ).unwrap()

    suspend fun updateGroup(
        id: String,
        name: String,
        members: List<String>,
    ): ApiResult<EmailGroupDto> =
        api.put<UpdateGroupRequest, EmailEnvelope<EmailGroupDto>>(
            ApiEndpoints.Email.GROUPS,
            UpdateGroupRequest(id, name, members),
        ).unwrap()

    suspend fun deleteGroup(id: String): ApiResult<Unit> =
        api.delete<EmailEnvelope<JsonObject>>(ApiEndpoints.Email.group(id)).unwrapUnit()

    // ── Forwarding ───────────────────────────────────────────────────────────

    suspend fun forwarding(): ApiResult<ForwardingDto> =
        api.get<EmailEnvelope<ForwardingDto>>(
            ApiEndpoints.Email.FORWARDING,
            query = accountsQuery(),
        ).unwrap()

    suspend fun saveForwarding(address: String): ApiResult<ForwardingDto> =
        api.post<SaveForwardingRequest, EmailEnvelope<ForwardingDto>>(
            ApiEndpoints.Email.FORWARDING,
            SaveForwardingRequest(address),
            query = accountsQuery(),
        ).unwrap()

    suspend fun deleteForwarding(): ApiResult<Unit> =
        api.delete<EmailEnvelope<JsonObject>>(
            ApiEndpoints.Email.FORWARDING,
            query = accountsQuery(),
        ).unwrapUnit()

    /** Who has opened a mail this user sent. */
    suspend fun readBy(messageId: String): ApiResult<ReadByDto> =
        api.post<ReadByRequest, EmailEnvelope<ReadByDto>>(
            ApiEndpoints.Email.READ_BY,
            ReadByRequest(messageId),
            query = accountsQuery(),
        ).unwrap()

    // ── Credentials ──────────────────────────────────────────────────────────

    suspend fun revealCredentials(): ApiResult<RevealedCredentialsDto> =
        api.get<EmailEnvelope<RevealedCredentialsDto>>(
            ApiEndpoints.Email.CREDENTIALS_REVEAL,
            query = accountsQuery(),
        ).unwrap()

    suspend fun updatePassword(password: String): ApiResult<Unit> =
        api.put<UpdatePasswordRequest, EmailEnvelope<JsonObject>>(
            ApiEndpoints.Email.CREDENTIALS_UPDATE,
            UpdatePasswordRequest(password),
            query = accountsQuery(),
        ).unwrapUnit()

    // ── Rules ────────────────────────────────────────────────────────────────

    suspend fun rules(): ApiResult<RuleListDto> =
        api.get<EmailEnvelope<RuleListDto>>(
            ApiEndpoints.Email.RULES,
            query = accountsQuery(),
        ).unwrap()

    suspend fun rule(ruleId: String): ApiResult<RuleWrapperDto> =
        api.get<EmailEnvelope<RuleWrapperDto>>(
            ApiEndpoints.Email.rule(ruleId),
            query = accountsQuery(),
        ).unwrap()

    /**
     * Creates a rule.
     *
     * The body is built by the caller ([toCreateBody]) because the schemas are strict about
     * which keys each condition and action type may carry — and it is the caller that puts
     * the accounts flag **in the body** here rather than in the query.
     */
    suspend fun createRule(body: JsonObject): ApiResult<RuleWrapperDto> =
        api.post<JsonObject, EmailEnvelope<RuleWrapperDto>>(
            ApiEndpoints.Email.RULES,
            body,
        ).unwrap()

    suspend fun updateRule(ruleId: String, body: JsonObject): ApiResult<RuleWrapperDto> =
        api.put<JsonObject, EmailEnvelope<RuleWrapperDto>>(
            ApiEndpoints.Email.rule(ruleId),
            body,
        ).unwrap()

    suspend fun deleteRule(ruleId: String): ApiResult<Unit> =
        api.delete<EmailEnvelope<JsonObject>>(
            ApiEndpoints.Email.rule(ruleId),
            query = accountsQuery(),
        ).unwrapUnit()

    suspend fun reorderRules(ruleIds: List<String>): ApiResult<RuleListDto> =
        api.post<ReorderRulesRequest, EmailEnvelope<RuleListDto>>(
            ApiEndpoints.Email.RULES_REORDER,
            ReorderRulesRequest(ruleIds),
            query = accountsQuery(),
        ).unwrap()

    suspend fun ruleExecutions(
        ruleId: String,
        limit: Int = DEFAULT_PAGE,
        skip: Int = 0,
        status: String? = null,
    ): ApiResult<RuleExecutionPageDto> =
        api.get<EmailEnvelope<RuleExecutionPageDto>>(
            ApiEndpoints.Email.ruleExecutions(ruleId),
            query = buildMap {
                put("limit", limit.coerceIn(1, MAX_PAGE).toString())
                put("skip", skip.coerceAtLeast(0).toString())
                status?.let { put("status", it) }
                putAll(accountsQuery())
            },
        ).unwrap()

    // ── Settings that live on the main host ──────────────────────────────────

    suspend fun setConversationView(enabled: Boolean): ApiResult<Unit> =
        api.patch<ConversationViewRequest, EmailEnvelope<JsonObject>>(
            if (mailbox.useAccountsParam) {
                ApiEndpoints.Email.Main.ACCOUNTS_CONVERSATION_VIEW
            } else {
                ApiEndpoints.Email.Main.CONVERSATION_VIEW
            },
            ConversationViewRequest(enabled),
        ).unwrapUnit()

    /**
     * Replaces the BCC preset list.
     *
     * Two different endpoints on the main host, chosen by which mailbox is active: the
     * personal list belongs to the user and the shared one to the project's mailbox. Both
     * take the complete list, so removing a preset is a save without it.
     */
    suspend fun setBccPresets(addresses: List<String>): ApiResult<Unit> =
        api.patch<BccPresetsRequest, EmailEnvelope<JsonObject>>(
            if (mailbox.useAccountsParam) {
                ApiEndpoints.Email.Main.ACCOUNTS_BCC
            } else {
                ApiEndpoints.Email.Main.BCC_PRESETS
            },
            BccPresetsRequest(addresses.map(::RecipientDto)),
        ).unwrapUnit()

    /**
     * `use_project_account_mailbox=true`, or nothing at all.
     *
     * Never `false` — the service reads absence as personal, and sending the parameter with
     * a false value is not part of the contract.
     */
    private fun accountsQuery(accounts: Boolean = mailbox.useAccountsParam): Map<String, String> =
        if (accounts) {
            mapOf(ApiEndpoints.Email.ACCOUNTS_MAILBOX_PARAM to "true")
        } else {
            emptyMap()
        }

    private companion object {
        const val DEFAULT_PAGE = 25
        const val MAX_PAGE = 100
    }
}

/**
 * Turns the service's envelope into an ordinary result.
 *
 * `status != 1` is a failure **even on a 200**, and `message` is a label key that the UI
 * resolves through the same dictionary as every other server message — so it is carried as
 * an [ApiError.Http] rather than flattened into a sentence here.
 */
private fun <T> ApiResult<EmailEnvelope<T>>.unwrap(): ApiResult<T> = when (this) {
    is ApiResult.Failure -> this

    is ApiResult.Success -> when {
        !data.isSuccess -> ApiResult.Failure(
            ApiError.Http(status = data.status, message = data.message),
        )

        data.data == null -> ApiResult.Failure(
            ApiError.Parsing("empty payload for a successful email response"),
        )

        else -> ApiResult.Success(data.data)
    }
}

/** For calls whose payload carries nothing worth reading. */
private fun <T> ApiResult<EmailEnvelope<T>>.unwrapUnit(): ApiResult<Unit> = when (this) {
    is ApiResult.Failure -> this

    is ApiResult.Success ->
        if (data.isSuccess) {
            ApiResult.Success(Unit)
        } else {
            ApiResult.Failure(ApiError.Http(status = data.status, message = data.message))
        }
}

/**
 * How far past "now" the drafts cursor reaches, to absorb clock skew between this device
 * and the mail service. A day is far more than any real drift and cannot skip a draft,
 * since nothing is ever stamped in the future.
 */
private const val CURSOR_MARGIN_MS = 24L * 60 * 60 * 1000
