package com.zillit.zillitapp.feature.email.data

import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.feature.email.domain.EmailRule
import com.zillit.zillitapp.feature.email.domain.ExecutionStatus
import com.zillit.zillitapp.feature.email.domain.RuleExecution
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inbound rules and their run history.
 *
 * Not cached: a rule set is at most fifty small objects, it is edited from the web as often
 * as from here, and showing a stale one would mean showing somebody a rule that is no longer
 * acting on their mail.
 */
@Singleton
class EmailRuleRepository @Inject constructor(
    private val api: EmailApi,
    private val mailbox: EmailMailboxContext,
) {

    suspend fun rules(): ApiResult<List<EmailRule>> = when (val result = api.rules()) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> ApiResult.Success(
            // Array position is priority, and the server returns them in order — but sorting
            // defensively costs nothing and a list rendered out of order would silently
            // misrepresent which rule wins.
            result.data.rules.sortedBy { it.priority }.map { it.toDomain() },
        )
    }

    suspend fun rule(ruleId: String): ApiResult<EmailRule> = when (val result = api.rule(ruleId)) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> result.data.rule
            ?.let { ApiResult.Success(it.toDomain()) }
            ?: ApiResult.Failure(
                com.zillit.zillitapp.core.network.ApiError.Parsing("rule payload was empty"),
            )
    }

    suspend fun create(rule: EmailRule): ApiResult<Unit> =
        api.createRule(rule.toCreateBody(mailbox.useAccountsParam)).map { }

    /** A full edit — name, conditions and actions all replaced. */
    suspend fun update(rule: EmailRule): ApiResult<Unit> = api.updateRule(
        rule.id,
        rule.toUpdateBody(
            accountsMailbox = mailbox.useAccountsParam,
            includeName = true,
            includeConditions = true,
            includeActions = true,
            includeMatchType = true,
            includeStopOnMatch = true,
        ),
    ).map { }

    /**
     * Flips one rule on or off.
     *
     * Sends **only** `enabled`. A full-object update here would let a toggle overwrite an
     * edit made elsewhere between this list loading and the switch being tapped.
     */
    suspend fun setEnabled(rule: EmailRule, enabled: Boolean): ApiResult<Unit> = api.updateRule(
        rule.id,
        rule.copy(enabled = enabled).toUpdateBody(
            accountsMailbox = mailbox.useAccountsParam,
            includeEnabled = true,
        ),
    ).map { }

    suspend fun delete(ruleId: String): ApiResult<Unit> = api.deleteRule(ruleId)

    /**
     * Commits a reorder.
     *
     * One request for the whole arrangement, sent when the finger lifts rather than on each
     * crossing — and the server's returned order wins, because it is what will actually run.
     */
    suspend fun reorder(ruleIds: List<String>): ApiResult<List<EmailRule>> =
        when (val result = api.reorderRules(ruleIds)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(
                result.data.rules.sortedBy { it.priority }.map { it.toDomain() },
            )
        }

    suspend fun executions(
        ruleId: String,
        limit: Int = PAGE_SIZE,
        skip: Int = 0,
        status: ExecutionStatus? = null,
    ): ApiResult<List<RuleExecution>> =
        when (val result = api.ruleExecutions(ruleId, limit, skip, status?.key)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(result.data.executions.map { it.toDomain() })
        }

    /**
     * How many runs of each rule have failed, for the list's badges.
     *
     * There is no count on the rules payload, so this is **one request per rule** — which is
     * why it is capped and throttled. Past the cap a row simply shows no badge; absent is
     * not the same as zero, and the list must not claim otherwise.
     *
     * A `failed_count` on the list response would delete this whole function, and it is
     * worth asking the backend for.
     */
    suspend fun failureCounts(rules: List<EmailRule>): Map<String, Int> = coroutineScope {
        val gate = Semaphore(BADGE_CONCURRENCY)

        rules.take(MAX_BADGE_LOOKUPS)
            .map { rule ->
                async {
                    gate.withPermit {
                        val result = api.ruleExecutions(
                            ruleId = rule.id,
                            limit = 1,
                            status = ExecutionStatus.FAILED.key,
                        )
                        rule.id to ((result as? ApiResult.Success)?.data?.total ?: 0)
                    }
                }
            }
            .awaitAll()
            .filter { (_, count) -> count > 0 }
            .toMap()
    }

    private companion object {
        const val PAGE_SIZE = 25

        /** Beyond this the fan-out costs more than the badges are worth. */
        const val MAX_BADGE_LOOKUPS = 20
        const val BADGE_CONCURRENCY = 4
    }
}
