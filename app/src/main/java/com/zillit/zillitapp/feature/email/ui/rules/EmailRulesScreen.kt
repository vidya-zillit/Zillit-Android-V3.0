package com.zillit.zillitapp.feature.email.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.SettingsGroup
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.components.rememberReorderableListState
import com.zillit.zillitapp.core.ui.components.reorderable
import com.zillit.zillitapp.core.ui.components.reorderableDragHandle
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailRule
import com.zillit.zillitapp.feature.email.domain.MailboxScope
import com.zillit.zillitapp.core.ui.components.zillitSwitchColors

data class EmailRulesUiState(
    val rules: List<EmailRule> = emptyList(),
    val loading: Boolean = false,
    val scope: MailboxScope = MailboxScope.PERSONAL,
    /** Whether this user has a shared mailbox to switch to at all. */
    val showMailboxTabs: Boolean = false,
    /** Rule id → how many runs have failed. Absent is *unknown*, not zero. */
    val failureCounts: Map<String, Int> = emptyMap(),
) {
    val canCreate: Boolean get() = rules.size < EmailRule.MAX_RULES_PER_MAILBOX

    val canReorder: Boolean get() = rules.size > 1
}

/**
 * The rules list — v2's `activity_email_rules` and `item_email_rule`.
 *
 * The order **is** the priority: rules run top to bottom and a match can stop the rest, so
 * dragging a row is editing the rule set, not tidying it. That is why both explanatory
 * lines are on screen rather than behind an info icon.
 *
 * Reordering is local while the finger is down and committed once on release — one request
 * for a whole rearrangement, and the server's returned order wins.
 */
@Composable
fun EmailRulesScreen(
    state: EmailRulesUiState,
    onBack: () -> Unit,
    onScopeChange: (MailboxScope) -> Unit,
    onCreate: () -> Unit,
    onEdit: (EmailRule) -> Unit,
    onHistory: (EmailRule) -> Unit,
    onDelete: (EmailRule) -> Unit,
    onToggleEnabled: (EmailRule, Boolean) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onCommitOrder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val reorder = rememberReorderableListState(
        listState = listState,
        onMove = onMove,
        onDrop = onCommitOrder,
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ZillitTheme.colors.background,
        topBar = {
            Column {
                ZillitTopBar(
                    title = stringResource(R.string.email_rules_title),
                    onBackClick = onBack,
                    onHelpClick = null,
                )

                if (state.showMailboxTabs) {
                    TabRow(
                        selectedTabIndex = if (state.scope == MailboxScope.PERSONAL) 0 else 1,
                        containerColor = ZillitTheme.colors.surface,
                        contentColor = ZillitTheme.colors.brand,
                    ) {
                        Tab(
                            selected = state.scope == MailboxScope.PERSONAL,
                            onClick = { onScopeChange(MailboxScope.PERSONAL) },
                            text = { Text(stringResource(R.string.email_rule_tab_my_mailbox)) },
                        )
                        Tab(
                            selected = state.scope == MailboxScope.SHARED,
                            onClick = { onScopeChange(MailboxScope.SHARED) },
                            text = {
                                Text(stringResource(R.string.email_rule_tab_accounts_mailbox))
                            },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                containerColor = ZillitTheme.colors.brand,
                contentColor = ZillitTheme.colors.textOnBrand,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.email_rule_create_new)) },
                // Fifty is the server's limit; the button goes dim rather than vanishing so
                // it is clear the feature exists and is full.
                modifier = Modifier.alpha(if (state.canCreate) 1f else 0.5f),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                state.loading -> LoadingState()

                state.rules.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.FilterAlt,
                    title = stringResource(R.string.email_rule_empty_title),
                    description = stringResource(R.string.email_rule_empty_subtitle),
                )

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = ZillitTheme.spacing.md,
                        end = ZillitTheme.spacing.md,
                        bottom = 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    item {
                        RulesHelperText(canReorder = state.canReorder)
                    }

                    itemsIndexed(state.rules, key = { _, rule -> rule.id }) { index, rule ->
                        RuleCard(
                            rule = rule,
                            failureCount = state.failureCounts[rule.id] ?: 0,
                            canReorder = state.canReorder,
                            onClick = { onEdit(rule) },
                            onEdit = { onEdit(rule) },
                            onHistory = { onHistory(rule) },
                            onDelete = { onDelete(rule) },
                            onToggleEnabled = { onToggleEnabled(rule, it) },
                            modifier = Modifier
                                .reorderable(reorder, index)
                                .then(
                                    if (state.canReorder) {
                                        Modifier.reorderableDragHandle(reorder, index)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesHelperText(canReorder: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.email_rule_list_info),
            style = MaterialTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        // Pointless with one rule, so it only appears once there is an order to change.
        if (canReorder) {
            Text(
                text = stringResource(R.string.email_rule_reorder_info),
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun RuleCard(
    rule: EmailRule,
    failureCount: Int,
    canReorder: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    SettingsGroup(
        // A disabled rule is dimmed whole rather than having its text greyed — it is still
        // there, still in the order, just not running.
        modifier = modifier.alpha(if (rule.enabled) 1f else 0.55f),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(
                    start = ZillitTheme.spacing.md,
                    end = ZillitTheme.spacing.xs,
                    top = ZillitTheme.spacing.md,
                    bottom = ZillitTheme.spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ZillitTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    if (failureCount > 0) {
                        RuleBadge(
                            text = stringResource(R.string.email_rule_failed_badge, failureCount),
                            color = ZillitTheme.colors.danger,
                        )
                    }

                    if (rule.stopOnMatch) {
                        RuleBadge(
                            text = stringResource(R.string.email_rule_stops_later),
                            color = ZillitTheme.colors.textSecondary,
                        )
                    }
                }

                Text(
                    text = RuleSummary.row(context, rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
                )
            }

            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggleEnabled,
                colors = zillitSwitchColors(),
            )

            Box {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.action_more),
                    tint = ZillitTheme.colors.textSecondary,
                    modifier = Modifier
                        .padding(start = ZillitTheme.spacing.xs)
                        .size(22.dp)
                        .clickable { showMenu = true },
                )

                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.email_rule_menu_edit)) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.email_rule_menu_history)) },
                        onClick = {
                            showMenu = false
                            onHistory()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.email_rule_menu_delete)) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .padding(start = ZillitTheme.spacing.sm)
            .clip(RoundedCornerShape(10.dp))
            .background(ZillitTheme.colors.background)
            .padding(
                horizontal = ZillitTheme.spacing.sm,
                vertical = ZillitTheme.spacing.xxs,
            ),
    )
}
