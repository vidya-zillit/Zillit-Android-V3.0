package com.zillit.zillitapp.feature.tools.ui.list

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.core.badge.BadgeAxis
import com.zillit.zillitapp.core.badge.BadgeKey
import com.zillit.zillitapp.core.badge.BadgeManager
import com.zillit.zillitapp.core.badge.BadgeSection
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.directory.ProjectTool
import com.zillit.zillitapp.core.labels.LabelRepository
import com.zillit.zillitapp.core.labels.ServerDictionaries
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.session.CurrentUserStore
import com.zillit.zillitapp.core.session.SessionStore
import com.zillit.zillitapp.feature.tools.data.ToolGroup
import com.zillit.zillitapp.feature.tools.data.ToolsRealtime
import com.zillit.zillitapp.feature.tools.data.ToolsRepository
import com.zillit.zillitapp.feature.tools.registry.ToolAvailability
import com.zillit.zillitapp.feature.tools.registry.ToolRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Tools tab.
 *
 * Four sources meet here and nowhere else: the tools themselves from the project directory,
 * the sections and their order from [ToolsRepository], and the unread counts from the badge
 * tree. All four are flows, so a tool moving group, a section being renamed and a message
 * arriving all land on screen without anybody asking.
 *
 * ### Badges, in one call
 * A notification is `section` → `tool` → `unit`, and its `tool` is the tool's own
 * `unit_name` — the label key the tools endpoint already sends for it. So grouping the tree
 * by `tool` yields a map this list can look up directly, and the sub-areas under `unit`
 * are summed by that same grouping. Schedule's pages and one-liner need no special case:
 * they are units of one tool, not tools of their own.
 *
 * v2 hand-maps thirty identifiers onto fixed fields of a flattened badge model, about a
 * hundred and ninety lines of it, because its model has no tree to group.
 */
@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val directory: ProjectDirectory,
    private val repository: ToolsRepository,
    private val realtime: ToolsRealtime,
    private val registry: ToolRegistry,
    private val badges: BadgeManager,
    private val labels: LabelRepository,
    private val session: SessionStore,
    private val currentUser: CurrentUserStore,
) : ViewModel() {

    private val projectId: String get() = session.activeProject.value?.projectId.orEmpty()

    private val _query = MutableStateFlow("")
    private val _loading = MutableStateFlow(true)
    private val _error = MutableStateFlow<ApiError?>(null)
    val error: StateFlow<ApiError?> = _error

    private val badgeCounts = badges.observeGrouped(
        BadgeKey.section(projectId, BadgeSection.TOOLS),
        BadgeAxis.TOOL,
    )

    val state: StateFlow<ToolsUiState> = combine(
        directory.observeTools(projectId),
        combine(repository.groups, repository.order, ::Pair),
        combine(badgeCounts, currentUser.profile, ::Pair),
        _query,
        // In the combine rather than read on demand: the dictionary arrives asynchronously,
        // and a section drawn before it lands would keep its raw identifier for good.
        labels.dictionaries,
    ) { tools, sections, countsAndProfile, query, dictionary ->
        val (groups, order) = sections
        val (rawCounts, profile) = countsAndProfile

        ToolsUiState(
            sections = buildSections(
                tools = tools,
                groups = groups,
                order = order,
                counts = rawCounts,
                query = query,
                dictionary = dictionary,
                isAdmin = profile?.isAdmin == true,
            ),
            query = query,
            loading = false,
            isAdmin = profile?.isAdmin == true,
        )
    }
        // Off the main thread. Grouping, sorting and resolving every tool's label runs
        // again whenever a badge arrives, and doing that between frames is what a scroll
        // feels as a stall.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), ToolsUiState())

    init {
        realtime.start()
        observeRealtime()
        refresh()
    }

    /** The sections the reorder sheet arranges, and the order it starts from. */
    val groupsForReorder: StateFlow<List<ToolGroup>> = repository.groups
    val orderForReorder: StateFlow<List<String>> = repository.order

    fun setQuery(query: String) {
        _query.value = query
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            directory.refreshTools(projectId, isPendingUser = isPendingSigner())
            repository.refreshGroups()
            repository.refreshOrder()
            _loading.value = false
        }
    }

    /**
     * Whether this user has joined the project but still has to sign.
     *
     * They get a different tool list from a different endpoint — the approved one answers
     * 403 for them — so the flag has to be right or the tab is empty for exactly the people
     * who are being asked to do something.
     */
    private fun isPendingSigner(): Boolean =
        currentUser.current?.status.equals(STATUS_PENDING, ignoreCase = true)

    fun saveOrder(order: List<String>) {
        viewModelScope.launch {
            val result = repository.saveOrder(order)
            if (result is ApiResult.Failure) _error.value = result.error
        }
    }

    fun consumeError() {
        _error.value = null
    }

    /** The sections, in the order this user wants them, with their tiles. */
    private fun buildSections(
        tools: List<ProjectTool>,
        groups: List<ToolGroup>,
        order: List<String>,
        counts: Map<String, Int>,
        query: String,
        dictionary: ServerDictionaries,
        isAdmin: Boolean,
    ): List<ToolSectionState> {
        val isAccountant = currentUser.current.isAccountant()

        val visible = tools
            // One endpoint feeds Home and Tools. A row that is not a tool has no tile,
            // whatever its access says.
            .filter { it.isTool && !it.isHome }
            .filter { it.viewAccess && it.enabled }
            .filter { tool ->
                when (registry[tool.identifier]?.availability) {
                    ToolAvailability.NonAccountantsOnly -> !isAccountant
                    else -> true
                }
            }
            .map { it.toRow(counts, dictionary, isAdmin) }
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }

        val byGroup = visible.groupBy { row ->
            tools.firstOrNull { it.identifier == row.identifier }
                ?.groupIdentifier
                ?.takeIf { it.isNotBlank() }
                ?: UNGROUPED
        }

        // The user's order first, then any group the project has that it did not mention,
        // then any group a tool claims that neither list knows about — a tool must never
        // disappear because its section was not in an order array.
        val sequence = buildList {
            order.forEach { if (it !in this) add(it) }
            groups.forEach { if (it.identifier !in this) add(it.identifier) }
            byGroup.keys.forEach { if (it !in this) add(it) }
        }

        return sequence.mapNotNull { identifier ->
            val rows = byGroup[identifier].orEmpty()
            if (rows.isEmpty()) return@mapNotNull null

            ToolSectionState(
                identifier = identifier,
                title = sectionTitle(identifier, groups, dictionary),
                // Unread first, then alphabetical. A tool with something waiting rises to
                // the top of its section, which is v2's rule and the reason the list is not
                // simply sorted by name.
                tools = rows.sortedWith(
                    compareByDescending<ToolRowState> { it.badgeCount }
                        .thenBy { it.title.lowercase() },
                ),
            )
        }
    }

    /**
     * Three fallbacks deep.
     *
     * The server's own group name first, then the label dictionary, then the raw identifier.
     * A header showing `group_admin` is poor, but a blank one is worse.
     */
    private fun sectionTitle(
        identifier: String,
        groups: List<ToolGroup>,
        dictionary: ServerDictionaries,
    ): String {
        if (identifier == UNGROUPED) return dictionary.resolve(UNGROUPED_LABEL)

        groups.firstOrNull { it.identifier == identifier }
            ?.name
            ?.takeIf { it.isNotBlank() && it != identifier }
            ?.let { return it }

        return dictionary.resolve("${identifier}_label")
    }

    private fun ProjectTool.toRow(
        counts: Map<String, Int>,
        dictionary: ServerDictionaries,
        isAdmin: Boolean,
    ): ToolRowState {
        val spec = registry[identifier]
        return ToolRowState(
            identifier = identifier,
            // The tool's own name is a label key, so it is resolved rather than shown raw.
            // Falls back to the identifier: a tool whose name the server did not send is
            // still recognisable as "Catering", where a blank row is not recognisable at
            // all and reads as a broken tile.
            // Resolved through every dictionary and with its substitutions applied, the
            // same way any server text is — a tool name is a label key like `accounts_label`,
            // and a template that kept its placeholder would read as a bug on the tile.
            title = dictionary.resolveOr(toolName, identifier),
            icon = spec?.icon ?: FALLBACK_ICON,
            // Keyed on the label, not the identifier: `accounting_tool` is badged as
            // `accounts_label`, and the tools endpoint already tells us which is which.
            badgeCount = counts[toolName] ?: 0,
            infoText = spec?.infoFor(isAdmin)
                ?: com.zillit.zillitapp.R.string.tool_info_unknown,
            videoUrl = spec?.videoFile?.let { ApiEndpoints.Help.VIDEO_BASE + it },
            helpUrl = spec?.helpAnchor?.let { ApiEndpoints.Help.page(it, isAdmin) },
            // A tool the registry has never heard of still appears: the server can enable
            // one before the app knows about it, and a missing tile is harder to diagnose
            // than one that says it cannot be opened here.
            openable = spec?.destination != null,
        )
    }

    private fun observeRealtime() {
        realtime.toolsChanged
            .onEach { directory.refreshTools(projectId, isPendingUser = isPendingSigner()) }
            .launchIn(viewModelScope)

        realtime.groupsChanged
            .onEach { repository.refreshGroups() }
            .launchIn(viewModelScope)

        realtime.orderChanged
            .onEach { repository.refreshOrder() }
            .launchIn(viewModelScope)
    }

    private companion object {
        const val STOP_MS = 5_000L
        const val UNGROUPED = "__ungrouped__"
        const val STATUS_PENDING = "pending"
        const val UNGROUPED_LABEL = "ungrouped_label"
        val FALLBACK_ICON = Icons.Outlined.Build
    }
}

/**
 * Whether this user is a senior accountant.
 *
 * v2's rule, and the web's: an **admin** holding one of exactly two accounts designations.
 * Every other accounts-team member — chief accountant, assistants, cashier — falls outside
 * it. The only thing this decides here is whether the standalone Invoices tile is shown,
 * since an accountant's invoice counts already sit inside the Account Hub tile.
 *
 * Matched on the designation's label key, which is what the directory stores verbatim.
 */
private fun com.zillit.zillitapp.core.session.CurrentUser?.isAccountant(): Boolean {
    if (this?.isAdmin != true) return false
    return designationName in SENIOR_ACCOUNTANT_DESIGNATIONS
}

private val SENIOR_ACCOUNTANT_DESIGNATIONS = setOf(
    "designation_production_accountant_accounts",
    "designation_financial_controller_accounts",
)
