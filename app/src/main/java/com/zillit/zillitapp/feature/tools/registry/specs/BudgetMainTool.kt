package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Savings
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The BudgetMain tile. */
val BudgetMainTool = ToolSpec(
    identifier = "main_budget_tool",
    icon = Icons.Outlined.Savings,
    infoText = R.string.tool_info_budget_main,
    infoTextAdmin = R.string.tool_info_budget_main_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "how%20to%20upload%20budget.mp4",
    destination = null,
)
