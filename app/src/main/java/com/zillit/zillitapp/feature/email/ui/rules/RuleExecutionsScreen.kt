package com.zillit.zillitapp.feature.email.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.email.domain.RuleActionType
import com.zillit.zillitapp.core.labels.LocalLabels
import com.zillit.zillitapp.core.labels.rememberDictionaries
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.FilterChipRow
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.SettingsGroup
import com.zillit.zillitapp.core.ui.components.ZillitFilterChip
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.ExecutionActionResult
import com.zillit.zillitapp.feature.email.domain.ExecutionStatus
import com.zillit.zillitapp.feature.email.domain.RuleExecution
import com.zillit.zillitapp.feature.email.ui.EmailDates

/**
 * Did this rule actually fire — v2's `activity_rule_executions` and `item_rule_execution`.
 *
 * The screen that answers "I set this up, why is my mail still in the inbox". Every run is
 * listed, including the ones that succeeded, because "it ran and did nothing" and "it never
 * ran" are different problems with different fixes.
 */
@Composable
fun RuleExecutionsScreen(
    ruleName: String,
    executions: List<RuleExecution>,
    statusFilter: ExecutionStatus?,
    loading: Boolean,
    loadingMore: Boolean,
    onStatusFilterChange: (ExecutionStatus?) -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Fetches the next page a few rows before the end, so the list does not stop dead while
    // the request is in flight.
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore }.collect { if (it) onLoadMore() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ZillitTheme.colors.background,
        topBar = {
            Column {
                ZillitTopBar(
                    title = ruleName.ifBlank { stringResource(R.string.email_rule_menu_history) },
                    onBackClick = onBack,
                    onHelpClick = null,
                )

                FilterChipRow(modifier = Modifier.padding(vertical = ZillitTheme.spacing.md)) {
                    ZillitFilterChip(
                        label = stringResource(R.string.email_rule_exec_filter_all),
                        selected = statusFilter == null,
                        onClick = { onStatusFilterChange(null) },
                    )
                    ExecutionStatus.entries.forEach { status ->
                        ZillitFilterChip(
                            label = stringResource(status.labelRes),
                            selected = statusFilter == status,
                            onClick = { onStatusFilterChange(status) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                loading && executions.isEmpty() -> LoadingState()

                executions.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.email_rule_exec_empty_title),
                    description = stringResource(R.string.email_rule_exec_empty_subtitle),
                )

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(ZillitTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    items(executions, key = { it.id }) { execution ->
                        ExecutionCard(execution)
                    }

                    if (loadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(ZillitTheme.spacing.md),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = ZillitTheme.colors.brand,
                                    modifier = Modifier.padding(ZillitTheme.spacing.sm),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionCard(execution: RuleExecution) {
    val context = LocalContext.current
    // Every dictionary, not just labels: a key can live in any of the three.
    val labels = rememberDictionaries()

    SettingsGroup {
        Column(
            modifier = Modifier.padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = EmailDates.executionStamp(execution.createdAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(execution.status.labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = execution.status.color(),
                )
            }

            // A run that is still retrying is not yet a failure — saying so stops people
            // editing a rule that was about to work.
            if (execution.isRetrying) {
                Text(
                    text = stringResource(
                        R.string.email_rule_exec_retrying,
                        execution.attempts,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textTertiary,
                )
            } else if (execution.needsAttention) {
                Text(
                    text = stringResource(R.string.email_rule_exec_needs_attention),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textTertiary,
                )
            }

            execution.results.forEach { result ->
                Text(
                    text = stringResource(
                        R.string.email_rule_exec_result_line,
                        RuleSummary.actionTypeLabel(context, RuleActionType.from(result.type))
                            .ifBlank { result.type },
                        RuleSummary.executionDetail(
                            context = context,
                            detail = result.detail.ifBlank { result.status },
                            labels = labels,
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = result.color(),
                )
            }

            if (execution.error.isNotBlank()) {
                Text(
                    text = RuleSummary.executionDetail(context, execution.error),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.danger,
                )
            }
        }
    }
}

private val ExecutionStatus.labelRes: Int
    get() = when (this) {
        ExecutionStatus.SUCCESS -> R.string.email_rule_exec_success
        ExecutionStatus.PARTIAL -> R.string.email_rule_exec_partial
        ExecutionStatus.FAILED -> R.string.email_rule_exec_failed
        ExecutionStatus.PENDING -> R.string.email_rule_exec_pending
    }

@Composable
private fun ExecutionStatus.color(): Color = when (this) {
    ExecutionStatus.SUCCESS -> ZillitTheme.colors.success
    ExecutionStatus.PARTIAL -> ZillitTheme.colors.warning
    ExecutionStatus.FAILED -> ZillitTheme.colors.danger
    ExecutionStatus.PENDING -> ZillitTheme.colors.textTertiary
}

/**
 * A per-action line's colour.
 *
 * "Skipped" is neutral, not red: no attachments to save, nothing matching the extension
 * filter, or a forward the loop guard suppressed are all normal outcomes, and colouring them
 * as errors sends people looking for a fault that is not there.
 */
@Composable
private fun ExecutionActionResult.color(): Color = when {
    isFailed -> ZillitTheme.colors.danger
    isSkipped -> ZillitTheme.colors.textTertiary
    else -> ZillitTheme.colors.textSecondary
}

private const val LOAD_MORE_THRESHOLD = 5
