package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** Its chat is a second badge key, so both are counted onto the one tile. */
val BudgetDepartmentTool = ToolSpec(
    identifier = "department_budget_tool",
    icon = Icons.Outlined.AccountBalanceWallet,
    infoText = R.string.tool_info_budget_department,
    infoTextAdmin = R.string.tool_info_budget_department_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "how%20to%20upload%20%20budget%20in%20departments.mp4",
    destination = null,
)
