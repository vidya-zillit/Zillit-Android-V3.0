package com.zillit.zillitapp.feature.tools.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A section on the Tools tab.
 *
 * Groups are project entities, not a fixed enum: six ship by default and an admin can add
 * more. [groupIdentifier] is the stable key and survives a rename, so everything that
 * references a group — a tool's membership, the saved order — stores the identifier and
 * resolves the name through this list.
 */
@Serializable
data class ToolGroupDto(
    @SerialName("tool_group_id") val toolGroupId: String? = null,
    @SerialName("group_identifier") val groupIdentifier: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("system_defined") val systemDefined: Boolean? = null,
    @SerialName("order") val order: Int? = null,
)

@Serializable
data class ToolGroupListResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: List<ToolGroupDto>? = null,
)

/** Create and rename both answer with the single group they changed. */
@Serializable
data class ToolGroupResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: ToolGroupDto? = null,
)

@Serializable
data class ToolGroupNameRequest(
    @SerialName("group_name") val groupName: String,
)

@Serializable
data class MoveToolGroupRequest(
    @SerialName("identifier") val identifier: String,
    /** Blank moves the tool out of every group, into "Ungrouped". */
    @SerialName("group_identifier") val groupIdentifier: String,
)

@Serializable
data class ToolGroupOrderResponse(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: ToolGroupOrderData? = null,
)

@Serializable
data class ToolGroupOrderData(
    /** True when the user has never reordered and this is the project's default. */
    @SerialName("is_default") val isDefault: Boolean? = null,
    @SerialName("groups") val groups: List<ToolGroupOrderItem>? = null,
) {
    /** Identifiers, top first. */
    fun ordered(): List<String> = groups.orEmpty()
        .sortedBy { it.order ?: Int.MAX_VALUE }
        .mapNotNull { it.groupIdentifier }
}

@Serializable
data class ToolGroupOrderItem(
    @SerialName("group_identifier") val groupIdentifier: String? = null,
    @SerialName("order") val order: Int? = null,
)

/**
 * The saved order.
 *
 * The array must hold **exactly** the project's current group identifiers, each once, or the
 * backend rejects it. Reconcile before sending rather than posting what the user dragged.
 */
@Serializable
data class ToolGroupOrderRequest(
    @SerialName("order") val order: List<String>,
)

@Serializable
data class EnableToolsRequest(
    @SerialName("tools") val tools: List<EnableToolItem>,
)

@Serializable
data class EnableToolItem(
    @SerialName("identifier") val identifier: String,
    @SerialName("enabled") val enabled: Boolean,
)
