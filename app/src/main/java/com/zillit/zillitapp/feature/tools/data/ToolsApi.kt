package com.zillit.zillitapp.feature.tools.data

import com.zillit.zillitapp.core.directory.ToolDto
import com.zillit.zillitapp.core.directory.ToolsResponse
import com.zillit.zillitapp.core.network.ApiEndpoints
import com.zillit.zillitapp.core.network.ApiError
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.network.ZillitApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The tool groups, the section order, and the admin switches.
 *
 * The tool **list** itself is not here: it belongs to the project directory, which fetches it
 * during bootstrap because half the app asks "can I open Drive here?" long before anyone
 * opens the Tools tab.
 */
@Singleton
class ToolsApi @Inject constructor(
    private val api: ZillitApi,
) {

    suspend fun groups(language: String): ApiResult<List<ToolGroupDto>> =
        api.get<ToolGroupListResponse>(
            ApiEndpoints.Project.TOOL_GROUPS,
            query = mapOf("lang" to language),
        ).map { it.data.orEmpty() }

    suspend fun createGroup(name: String): ApiResult<ToolGroupDto> =
        api.post<ToolGroupNameRequest, ToolGroupResponse>(
            ApiEndpoints.Project.TOOL_GROUPS,
            ToolGroupNameRequest(name),
        ).mapNotNull { it.data }

    suspend fun renameGroup(groupId: String, name: String): ApiResult<ToolGroupDto> =
        api.put<ToolGroupNameRequest, ToolGroupResponse>(
            "${ApiEndpoints.Project.TOOL_GROUPS}/$groupId",
            ToolGroupNameRequest(name),
        ).mapNotNull { it.data }

    suspend fun deleteGroup(groupId: String): ApiResult<Unit> =
        api.delete<ToolGroupResponse>("${ApiEndpoints.Project.TOOL_GROUPS}/$groupId")
            .map { }

    /** Blank [groupIdentifier] moves the tool out of every group. */
    suspend fun moveToGroup(identifier: String, groupIdentifier: String): ApiResult<Unit> =
        api.put<MoveToolGroupRequest, ToolGroupResponse>(
            ApiEndpoints.Project.TOOL_GROUP_MOVE,
            MoveToolGroupRequest(identifier, groupIdentifier),
        ).map { }

    suspend fun order(): ApiResult<List<String>> =
        api.get<ToolGroupOrderResponse>(ApiEndpoints.Project.TOOL_GROUP_ORDER)
            .map { it.data?.ordered().orEmpty() }

    /** The save answers with the order it stored, so there is no follow-up read. */
    suspend fun saveOrder(order: List<String>): ApiResult<List<String>> =
        api.put<ToolGroupOrderRequest, ToolGroupOrderResponse>(
            ApiEndpoints.Project.TOOL_GROUP_ORDER,
            ToolGroupOrderRequest(order),
        ).map { it.data?.ordered().orEmpty() }

    /** Every tool the project could have, switched on or not. Finer grained than the tab. */
    suspend fun adminTools(): ApiResult<List<ToolDto>> =
        api.get<ToolsResponse>(ApiEndpoints.Project.TOOLS_ADMIN)
            .map { it.data.orEmpty().filter { tool -> !tool.identifier.isNullOrBlank() } }

    suspend fun setEnabled(tools: List<EnableToolItem>): ApiResult<Unit> =
        api.put<EnableToolsRequest, ToolGroupResponse>(
            ApiEndpoints.Project.TOOLS_ENABLE,
            EnableToolsRequest(tools),
        ).map { }
}

private inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

/**
 * Treats a success whose payload is missing as a failure.
 *
 * A 200 with no `data` is not something to hand back as a valid group — it would be written
 * into the list as a blank row rather than reported.
 */
private inline fun <T, R : Any> ApiResult<T>.mapNotNull(transform: (T) -> R?): ApiResult<R> =
    when (this) {
        is ApiResult.Success -> transform(data)
            ?.let { ApiResult.Success(it) }
            ?: ApiResult.Failure(ApiError.Parsing("empty tool group payload"))

        is ApiResult.Failure -> this
    }
