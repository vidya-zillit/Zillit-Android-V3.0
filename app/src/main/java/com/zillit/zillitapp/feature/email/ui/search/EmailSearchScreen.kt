package com.zillit.zillitapp.feature.email.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.FilterChipFlow
import com.zillit.zillitapp.core.ui.components.FilterChipRow
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.SearchField
import com.zillit.zillitapp.core.ui.components.ZillitFilterChip
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailFolder
import com.zillit.zillitapp.feature.email.ui.list.EmailReadFilter
import com.zillit.zillitapp.feature.email.ui.list.EmailRow
import com.zillit.zillitapp.feature.email.ui.list.EmailRowState
import com.zillit.zillitapp.feature.email.ui.list.folderDisplayName
import com.zillit.zillitapp.core.ui.components.zillitSwitchColors

/**
 * Which parts of a message the query is matched against.
 *
 * Named for the module rather than bare `SearchField`, which is the shared text input in
 * `core/ui/components` — the two would otherwise shadow each other in exactly the file that
 * uses both.
 */
enum class EmailSearchField(val default: Boolean) {
    SUBJECT(true),
    FROM(true),
    TO(true),
    CC(false),
    BCC(false),
}

data class EmailSearchUiState(
    val query: String = "",
    val results: List<EmailRowState> = emptyList(),
    val searching: Boolean = false,
    /** False until a query has actually been run — the difference between "no results". */
    val hasSearched: Boolean = false,
    val showFilters: Boolean = false,
    val availableFolders: List<EmailFolder> = emptyList(),
    val selectedFolders: Set<String> = emptySet(),
    val fields: Set<EmailSearchField> = EmailSearchField.entries.filter { it.default }.toSet(),
    val readFilter: EmailReadFilter = EmailReadFilter.ALL,
    val hasAttachments: Boolean = false,
)

/**
 * Search — v2's `fragment_search`.
 *
 * **Local only.** There is no search endpoint: this is a query over what has already synced,
 * so a folder the user has never opened cannot be searched and Drafts never can, since they
 * are API-only and never cached. The empty state says "Search your emails" rather than
 * promising more than that.
 *
 * The body is deliberately not searched, matching v2 — a body search over every cached
 * message on a phone is slow enough to feel broken, and the fields that are searched are
 * the ones people actually search by.
 */
@Composable
fun EmailSearchScreen(
    state: EmailSearchUiState,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onToggleFilters: () -> Unit,
    onToggleFolder: (String) -> Unit,
    onToggleField: (EmailSearchField) -> Unit,
    onReadFilterChange: (EmailReadFilter) -> Unit,
    onHasAttachmentsChange: (Boolean) -> Unit,
    onResultClick: (EmailRowState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = ZillitTheme.colors.textPrimary,
                )
            }

            SearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                placeholder = stringResource(R.string.email_search_hint),
                // The whole screen exists to be typed into, so the keyboard opens with it.
                autoFocus = true,
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = onToggleFilters) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = stringResource(R.string.email_search_filters),
                    tint = if (state.showFilters) {
                        ZillitTheme.colors.brand
                    } else {
                        ZillitTheme.colors.textSecondary
                    },
                )
            }
        }

        AnimatedVisibility(visible = state.showFilters) {
            Column {
                SearchFilters(
                    state = state,
                    onToggleFolder = onToggleFolder,
                    onToggleField = onToggleField,
                    onReadFilterChange = onReadFilterChange,
                    onHasAttachmentsChange = onHasAttachmentsChange,
                )
                HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.searching -> LoadingState()

                !state.hasSearched -> EmptyState(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.email_search_initial),
                )

                state.results.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.email_search_no_results),
                    description = stringResource(R.string.email_search_no_results_subtitle),
                )

                else -> Column {
                    Text(
                        text = pluralStringResource(
                            R.plurals.email_search_result_count,
                            state.results.size,
                            state.results.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = ZillitTheme.colors.textTertiary,
                        modifier = Modifier.padding(
                            horizontal = ZillitTheme.spacing.lg,
                            vertical = ZillitTheme.spacing.sm,
                        ),
                    )

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.results, key = { it.id }) { row ->
                            EmailRow(
                                state = row,
                                onClick = { onResultClick(row) },
                                // A result is a way into a message, not something to act on
                                // in bulk — there is no selection or row menu here.
                                onLongClick = {},
                                onMoreClick = {},
                                selectionActive = true,
                            )
                            HorizontalDivider(
                                color = ZillitTheme.colors.divider,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 66.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFilters(
    state: EmailSearchUiState,
    onToggleFolder: (String) -> Unit,
    onToggleField: (EmailSearchField) -> Unit,
    onReadFilterChange: (EmailReadFilter) -> Unit,
    onHasAttachmentsChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        FilterSection(stringResource(R.string.email_search_folders)) {
            // Sideways rather than wrapping: a mailbox can have twenty folders, and a wrap
            // would push everything else off the screen.
            FilterChipRow {
                state.availableFolders.forEach { folder ->
                    ZillitFilterChip(
                        label = folderDisplayName(folder.folderName),
                        selected = folder.folderName in state.selectedFolders,
                        onClick = { onToggleFolder(folder.folderName) },
                    )
                }
            }
        }

        FilterSection(stringResource(R.string.email_search_in), padded = true) {
            FilterChipFlow {
                EmailSearchField.entries.forEach { field ->
                    ZillitFilterChip(
                        label = stringResource(field.labelRes),
                        selected = field in state.fields,
                        onClick = { onToggleField(field) },
                    )
                }
            }
        }

        FilterSection(stringResource(R.string.email_filter_status), padded = true) {
            FilterChipFlow {
                ZillitFilterChip(
                    label = stringResource(R.string.email_filter_all),
                    selected = state.readFilter == EmailReadFilter.ALL,
                    onClick = { onReadFilterChange(EmailReadFilter.ALL) },
                )
                ZillitFilterChip(
                    label = stringResource(R.string.email_filter_read),
                    selected = state.readFilter == EmailReadFilter.READ,
                    onClick = { onReadFilterChange(EmailReadFilter.READ) },
                )
                ZillitFilterChip(
                    label = stringResource(R.string.email_filter_unread),
                    selected = state.readFilter == EmailReadFilter.UNREAD,
                    onClick = { onReadFilterChange(EmailReadFilter.UNREAD) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.email_filter_has_attachments),
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.hasAttachments,
                onCheckedChange = onHasAttachmentsChange,
                colors = zillitSwitchColors(),
            )
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    padded: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg),
        )
        Box(
            modifier = if (padded) {
                Modifier.padding(horizontal = ZillitTheme.spacing.lg)
            } else {
                Modifier
            },
        ) {
            content()
        }
    }
}

private val EmailSearchField.labelRes: Int
    get() = when (this) {
        EmailSearchField.SUBJECT -> R.string.email_field_subject
        EmailSearchField.FROM -> R.string.email_field_from
        EmailSearchField.TO -> R.string.email_field_to
        EmailSearchField.CC -> R.string.email_field_cc
        EmailSearchField.BCC -> R.string.email_field_bcc
    }
