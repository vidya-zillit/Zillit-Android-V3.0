package com.zillit.zillitapp.feature.email.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.feature.email.data.EmailMailboxContext
import com.zillit.zillitapp.feature.email.data.EmailRepository
import com.zillit.zillitapp.feature.email.data.EmailRuleRepository
import com.zillit.zillitapp.feature.email.data.toDomain
import com.zillit.zillitapp.feature.email.domain.ConditionField
import com.zillit.zillitapp.feature.email.domain.ConditionOperator
import com.zillit.zillitapp.feature.email.domain.EmailFolders
import com.zillit.zillitapp.feature.email.domain.EmailRule
import com.zillit.zillitapp.feature.email.domain.ExecutionStatus
import com.zillit.zillitapp.feature.email.domain.MailboxScope
import com.zillit.zillitapp.feature.email.domain.RuleAction
import com.zillit.zillitapp.feature.email.domain.RuleActionType
import com.zillit.zillitapp.feature.email.domain.RuleCondition
import com.zillit.zillitapp.feature.email.data.DriveSectionFilter
import com.zillit.zillitapp.feature.email.domain.RuleDriveFolder
import com.zillit.zillitapp.feature.email.domain.RuleExecution
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Rules: the list, the editor and the run history.
 *
 * One view model because all three act on the same rule set and the same mailbox scope, and
 * because a reorder committed on the list has to be reflected in an editor opened straight
 * afterwards.
 */
@HiltViewModel
class EmailRulesViewModel @Inject constructor(
    private val repository: EmailRuleRepository,
    private val emails: EmailRepository,
    private val mailbox: EmailMailboxContext,
    private val driveApi: com.zillit.zillitapp.feature.email.data.DriveFolderApi,
    private val directory: com.zillit.zillitapp.core.directory.ProjectDirectory,
    private val session: com.zillit.zillitapp.core.session.SessionStore,
) : ViewModel() {

    /**
     * The mailbox that was active when this screen opened.
     *
     * [EmailMailboxContext] is a singleton the whole module reads, so the scope tab here is
     * not local state — switching it points the inbox, the composer and the credentials
     * sheet at the shared mailbox too. Browsing the Accounts rules must not do that, so the
     * entry scope is captured and put back on the way out.
     */
    private val entryScope: MailboxScope = mailbox.effectiveScope

    /** True while a rule is being fetched into the editor, as opposed to saved from it. */
    private val _editorLoading = MutableStateFlow(false)
    val editorLoading: StateFlow<Boolean> = _editorLoading.asStateFlow()

    private val _actionLimitReached = MutableStateFlow(false)
    val actionLimitReached: StateFlow<Boolean> = _actionLimitReached.asStateFlow()

    private val _state = MutableStateFlow(EmailRulesUiState(loading = true))
    val state: StateFlow<EmailRulesUiState> = _state.asStateFlow()

    private val _error = MutableStateFlow<ApiError?>(null)
    val error: StateFlow<ApiError?> = _error.asStateFlow()

    /** The order as the server last confirmed it — what a failed reorder rolls back to. */
    private var committedOrder: List<EmailRule> = emptyList()

    // ── Editor ───────────────────────────────────────────────────────────────

    private val _draft = MutableStateFlow(RuleDraft())
    val draft: StateFlow<RuleDraft> = _draft.asStateFlow()

    private val _issue = MutableStateFlow<RuleIssue?>(null)
    val issue: StateFlow<RuleIssue?> = _issue.asStateFlow()

    private val _editorBusy = MutableStateFlow(false)
    val editorBusy: StateFlow<Boolean> = _editorBusy.asStateFlow()

    /** Destinations a move action may file into. */
    private val _folderNames = MutableStateFlow<List<String>>(emptyList())
    val folderNames: StateFlow<List<String>> = _folderNames.asStateFlow()

    private var editingRule: EmailRule? = null

    private val _senderSuggestions = MutableStateFlow<List<String>>(emptyList())

    /**
     * Addresses to offer under the rule editor's **From** field.
     *
     * Suggestions, not a choice: a rule usually matches an outside sender, so the field
     * stays free text and these only save typing for the common case of a colleague.
     */
    val senderSuggestions: StateFlow<List<String>> = _senderSuggestions.asStateFlow()

    // ── Drive folder picker ──────────────────────────────────────────────────

    private val _drivePicker = MutableStateFlow(DriveFolderPickerState())
    val drivePicker: StateFlow<DriveFolderPickerState> = _drivePicker.asStateFlow()

    /** Which action the picker is choosing for; null when it is closed. */
    private val _drivePickerFor = MutableStateFlow<Int?>(null)
    val drivePickerFor: StateFlow<Int?> = _drivePickerFor.asStateFlow()

    fun openDrivePicker(actionIndex: Int) {
        _drivePickerFor.value = actionIndex
        _drivePicker.value = DriveFolderPickerState(loading = true)
        loadDriveFolders()
    }

    fun closeDrivePicker() {
        _drivePickerFor.value = null
    }

    fun setDriveSection(section: DriveSection) {
        // Switching side clears the path: a breadcrumb from My Drive means nothing inside
        // Shared with me.
        _drivePicker.value = DriveFolderPickerState(section = section, loading = true)
        loadDriveFolders()
    }

    fun openDriveFolder(folder: RuleDriveFolder) {
        _drivePicker.value = _drivePicker.value.copy(
            breadcrumbs = _drivePicker.value.breadcrumbs + folder,
            loading = true,
        )
        loadDriveFolders()
    }

    /** Walks one level up. Returns false at the root, so the caller can dismiss instead. */
    fun driveFolderUp(): Boolean {
        val crumbs = _drivePicker.value.breadcrumbs
        if (crumbs.isEmpty()) return false

        _drivePicker.value = _drivePicker.value.copy(
            breadcrumbs = crumbs.dropLast(1),
            loading = true,
        )
        loadDriveFolders()
        return true
    }

    fun createDriveFolder(name: String) {
        if (name.isBlank()) return

        viewModelScope.launch {
            val parent = _drivePicker.value.openFolder?.id
            when (val result = driveApi.createFolder(name.trim(), parent)) {
                // Drilling into the new folder means Select acts on it straight away,
                // which is what somebody who just created it wants.
                is ApiResult.Success -> openDriveFolder(result.data)
                is ApiResult.Failure -> _error.value = result.error
            }
        }
    }

    private fun loadDriveFolders() {
        viewModelScope.launch {
            val state = _drivePicker.value
            val result = driveApi.folders(
                parentId = state.openFolder?.id,
                section = when (state.section) {
                    DriveSection.MINE -> DriveSectionFilter.MINE
                    DriveSection.SHARED -> DriveSectionFilter.SHARED
                },
            )

            _drivePicker.value = when (result) {
                is ApiResult.Success -> _drivePicker.value.copy(
                    folders = result.data,
                    loading = false,
                )

                is ApiResult.Failure -> {
                    _error.value = result.error
                    _drivePicker.value.copy(folders = emptyList(), loading = false)
                }
            }
        }
    }

    // ── Executions ───────────────────────────────────────────────────────────

    private val _executions = MutableStateFlow<List<RuleExecution>>(emptyList())
    val executions: StateFlow<List<RuleExecution>> = _executions.asStateFlow()

    private val _executionFilter = MutableStateFlow<ExecutionStatus?>(null)
    val executionFilter: StateFlow<ExecutionStatus?> = _executionFilter.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private var executionsExhausted = false

    init {
        load()
        loadFolderNames()
        loadSenderSuggestions()
    }

    private fun loadSenderSuggestions() {
        viewModelScope.launch {
            val projectId = session.activeProject.value?.projectId.orEmpty()
            _senderSuggestions.value = directory.observeUsers(projectId).first()
                .mapNotNull { it.email?.takeIf { address -> address.isNotBlank() } }
                .distinct()
                .sorted()
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = true,
                scope = mailbox.effectiveScope,
                showMailboxTabs = mailbox.entitled.value &&
                    !mailbox.accountsMailbox.value.isNullOrBlank(),
            )

            when (val result = repository.rules()) {
                is ApiResult.Success -> {
                    committedOrder = result.data
                    _state.value = _state.value.copy(rules = result.data, loading = false)
                    loadFailureCounts(result.data)
                }

                is ApiResult.Failure -> {
                    // Same fallback as the mail list: an entitlement revoked mid-session
                    // drops back to the personal rules rather than showing an empty tab
                    // the user cannot leave.
                    if (mailbox.dropSharedOnRefusal(result.error)) {
                        load()
                    } else {
                        _error.value = result.error
                        _state.value = _state.value.copy(loading = false)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        mailbox.select(entryScope)
        super.onCleared()
    }

    fun setScope(scope: MailboxScope) {
        if (mailbox.effectiveScope == scope) return
        mailbox.select(scope)
        load()
    }

    /**
     * Moves a rule within the list, locally.
     *
     * Not persisted — [commitOrder] does that when the finger lifts. A request per crossing
     * would be a dozen calls for one rearrangement, each racing the last.
     */
    fun move(from: Int, to: Int) {
        val current = _state.value.rules.toMutableList()
        if (from !in current.indices || to !in current.indices) return

        current.add(to, current.removeAt(from))
        _state.value = _state.value.copy(rules = current)
    }

    fun commitOrder() {
        val order = _state.value.rules.map { it.id }

        // A drag that ends on its own row changes nothing. Posting it anyway would rewrite
        // the run order on the server for a gesture the user effectively cancelled.
        if (order.isEmpty() || order == committedOrder.map { it.id }) return

        viewModelScope.launch {
            when (val result = repository.reorder(order)) {
                // The server's order wins: it is the one that will actually run.
                is ApiResult.Success -> {
                    committedOrder = result.data
                    _state.value = _state.value.copy(rules = result.data)
                }

                is ApiResult.Failure -> {
                    _error.value = result.error
                    _state.value = _state.value.copy(rules = committedOrder)
                }
            }
        }
    }

    fun setEnabled(rule: EmailRule, enabled: Boolean) {
        if (rule.enabled == enabled) return

        // Optimistic, and reverted on failure — a switch that waits for a round trip reads
        // as broken.
        _state.value = _state.value.copy(
            rules = _state.value.rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it },
        )

        viewModelScope.launch {
            val result = repository.setEnabled(rule, enabled)
            if (result is ApiResult.Failure) {
                _error.value = result.error
                _state.value = _state.value.copy(
                    rules = _state.value.rules.map {
                        if (it.id == rule.id) it.copy(enabled = !enabled) else it
                    },
                )
            }
        }
    }

    fun delete(rule: EmailRule) {
        viewModelScope.launch {
            val result = repository.delete(rule.id)
            if (result is ApiResult.Failure) _error.value = result.error else load()
        }
    }

    private fun loadFailureCounts(rules: List<EmailRule>) {
        viewModelScope.launch {
            // One request per rule, capped — absent is not zero, so a row past the cap
            // simply shows no badge rather than claiming everything is fine.
            _state.value = _state.value.copy(failureCounts = repository.failureCounts(rules))
        }
    }

    private fun loadFolderNames() {
        viewModelScope.launch {
            _folderNames.value = emails.observeFolders().first()
                .map { it.toDomain().folderName }
                // A rule may file into a folder the user made, or into Junk or Trash.
                // Every other system folder is excluded, which is the narrower v2 rule —
                // matching on the folder name only, case-insensitively, since the server
                // has been seen to vary the casing.
                .filter { name ->
                    EmailFolders.SYSTEM.none { it.equals(name, ignoreCase = true) } ||
                        RULE_SYSTEM_DESTINATIONS.any { it.equals(name, ignoreCase = true) }
                }
        }
    }

    // ── Editor ───────────────────────────────────────────────────────────────

    /**
     * Opens a rule for editing, or starts a new one.
     *
     * A loaded rule that uses anything this screen cannot express opens **read-only** rather
     * than being quietly simplified — saving a `from_domain` match back as a `from` match
     * would change what the rule does without telling anybody.
     */
    fun openEditor(ruleId: String?) {
        _issue.value = null

        if (ruleId == null) {
            editingRule = null
            _draft.value = RuleDraft()
            return
        }

        viewModelScope.launch {
            _editorBusy.value = true
            _editorLoading.value = true

            when (val result = repository.rule(ruleId)) {
                is ApiResult.Success -> {
                    editingRule = result.data
                    _draft.value = result.data.toDraft().withMissingDestinations()
                }

                is ApiResult.Failure -> _error.value = result.error
            }
            _editorBusy.value = false
            _editorLoading.value = false
        }
    }

    fun consumeActionLimit() {
        _actionLimitReached.value = false
    }

    /**
     * Flags destinations the rule points at that are no longer there.
     *
     * A folder or a Drive folder can be deleted long after the rule was written. Without
     * this the row renders as if the destination were fine, and the save is refused by the
     * server with a label key the user never asked about.
     */
    private suspend fun RuleDraft.withMissingDestinations(): RuleDraft {
        val folders = emails.observeFolders().first().map { it.toDomain().folderName }
        val missingFolders = actions
            .filterIsInstance<RuleAction.MoveToFolder>()
            .map { it.folderName }
            .filter { name -> folders.none { it.equals(name, ignoreCase = true) } }
            .toSet()

        // The Drive check is local: the service resolves and returns `drive_folder_name`
        // for a folder that exists, so an id with no name is one that has been deleted.
        // Listing Drive to diff would need a request per level, and a folder nested below
        // the top level would be wrongly flagged.
        val missingDrive = actions
            .filterIsInstance<RuleAction.SaveAttachmentsToDrive>()
            .filter { it.driveFolderId.isNotBlank() && it.driveFolderName.isBlank() }
            .map { it.driveFolderId }
            .toSet()

        return copy(missingFolderNames = missingFolders, missingDriveFolderIds = missingDrive)
    }

    fun updateDraft(draft: RuleDraft) {
        _draft.value = draft
        // Typing clears the complaint about the thing being typed.
        _issue.value = null
    }

    fun addAction(type: RuleActionType) {
        val current = _draft.value
        // Refused out loud. Silently doing nothing reads as a broken button.
        if (current.actions.size >= EmailRule.MAX_ACTIONS) {
            _actionLimitReached.value = true
            return
        }
        _draft.value = current.copy(actions = current.actions + type.empty())
    }

    /**
     * Creates an email folder and selects it on [actionIndex].
     *
     * Offered from inside the rule editor because that is where the need appears: filing
     * into a folder that does not exist yet otherwise means leaving the half-written rule,
     * making the folder in the drawer, and starting again. The **server's** returned name is
     * what gets selected — it may namespace what was typed, as `INBOX.Invoices`.
     */
    fun createEmailFolder(actionIndex: Int, name: String) {
        viewModelScope.launch {
            when (val result = emails.createFolder(name.trim())) {
                is ApiResult.Success -> {
                    emails.syncFolders()
                    loadFolderNames()

                    val actions = _draft.value.actions.toMutableList()
                    val action = actions.getOrNull(actionIndex) as? RuleAction.MoveToFolder
                        ?: return@launch

                    actions[actionIndex] = action.copy(folderName = result.data.folderName)
                    _draft.value = _draft.value.copy(
                        actions = actions,
                        missingFolderNames = _draft.value.missingFolderNames - action.folderName,
                    )
                }

                is ApiResult.Failure -> _error.value = result.error
            }
        }
    }

    fun save(onDone: () -> Unit) {
        val draft = _draft.value
        if (draft.readOnly) return

        val problem = draft.firstIssue()
        if (problem != null) {
            _issue.value = problem
            return
        }

        viewModelScope.launch {
            _editorBusy.value = true

            val rule = draft.toRule(editingRule)
            val result = if (editingRule == null) {
                repository.create(rule)
            } else {
                repository.update(rule)
            }

            when (result) {
                is ApiResult.Success -> {
                    load()
                    onDone()
                }

                is ApiResult.Failure -> _error.value = result.error
            }
            _editorBusy.value = false
        }
    }

    fun setDriveFolder(actionIndex: Int, folderId: String, folderName: String) {
        val actions = _draft.value.actions.toMutableList()
        val action = actions.getOrNull(actionIndex) as? RuleAction.SaveAttachmentsToDrive ?: return

        actions[actionIndex] = action.copy(driveFolderId = folderId, driveFolderName = folderName)
        _draft.value = _draft.value.copy(
            actions = actions,
            // Choosing a folder answers the "this one no longer exists" complaint.
            missingDriveFolderIds = _draft.value.missingDriveFolderIds - action.driveFolderId,
        )
    }

    // ── Executions ───────────────────────────────────────────────────────────

    fun loadExecutions(ruleId: String, reset: Boolean = true) {
        // Without this guard the list's "near the end" trigger fires on the empty first
        // frame and races the reset load, so page one is fetched and appended twice.
        if (!reset && (_loadingMore.value || executionsExhausted)) return

        if (reset) {
            executionsExhausted = false
            _executions.value = emptyList()
        }

        viewModelScope.launch {
            _loadingMore.value = true

            val result = repository.executions(
                ruleId = ruleId,
                skip = _executions.value.size,
                status = _executionFilter.value,
            )

            when (result) {
                is ApiResult.Success -> {
                    if (result.data.isEmpty()) executionsExhausted = true
                    _executions.value = _executions.value + result.data
                }

                is ApiResult.Failure -> _error.value = result.error
            }
            _loadingMore.value = false
        }
    }

    fun setExecutionFilter(ruleId: String, status: ExecutionStatus?) {
        _executionFilter.value = status
        loadExecutions(ruleId, reset = true)
    }

    fun consumeError() {
        _error.value = null
    }

    // ── Rule ↔ draft ─────────────────────────────────────────────────────────

    /**
     * Reads a rule into the three fields this editor has.
     *
     * Anything that will not round-trip marks the draft read-only: a non-`contains`
     * operator, a boolean field with the wrong operator, a field the rows cannot represent,
     * or the same field used twice.
     */
    private fun EmailRule.toDraft(): RuleDraft {
        val byField = conditions.groupBy { it.field }
        val duplicated = byField.any { (_, list) -> list.size > 1 }

        val unsupported = conditions.any { condition ->
            when (condition.field) {
                ConditionField.FROM,
                ConditionField.SUBJECT,
                ConditionField.SUBJECT_OR_BODY,
                -> condition.operator != ConditionOperator.CONTAINS

                ConditionField.HAS_ATTACHMENT -> condition.operator != ConditionOperator.IS_TRUE

                // from_domain, body and always have no row on this screen.
                else -> true
            }
        }

        return RuleDraft(
            name = name,
            from = byField[ConditionField.FROM]?.firstOrNull()?.value.orEmpty(),
            subject = byField[ConditionField.SUBJECT]?.firstOrNull()?.value.orEmpty(),
            hasWords = byField[ConditionField.SUBJECT_OR_BODY]?.firstOrNull()?.value.orEmpty(),
            hasAttachment = byField.containsKey(ConditionField.HAS_ATTACHMENT),
            actions = actions,
            readOnly = duplicated || unsupported,
            // Preserved on save even though the editor cannot change it.
            matchesAny = matchType == com.zillit.zillitapp.feature.email.domain.RuleMatchType.ANY,
        )
    }

    /** Builds the rule this draft describes, keeping what the editor cannot show. */
    private fun RuleDraft.toRule(existing: EmailRule?): EmailRule {
        val conditions = buildList {
            if (from.isNotBlank()) {
                add(RuleCondition(ConditionField.FROM, ConditionOperator.CONTAINS, from.trim()))
            }
            if (subject.isNotBlank()) {
                add(
                    RuleCondition(
                        ConditionField.SUBJECT,
                        ConditionOperator.CONTAINS,
                        subject.trim(),
                    ),
                )
            }
            if (hasWords.isNotBlank()) {
                add(
                    RuleCondition(
                        ConditionField.SUBJECT_OR_BODY,
                        ConditionOperator.CONTAINS,
                        hasWords.trim(),
                    ),
                )
            }
            if (hasAttachment) {
                add(RuleCondition(ConditionField.HAS_ATTACHMENT, ConditionOperator.IS_TRUE))
            }
        }

        return (existing ?: EmailRule()).copy(
            name = name.trim(),
            conditions = conditions,
            actions = actions,
            scope = mailbox.effectiveScope,
        )
    }

    private companion object {
        /** The only system folders a rule may move mail into. */
        val RULE_SYSTEM_DESTINATIONS = listOf(EmailFolders.JUNK, EmailFolders.TRASH)
    }
}
