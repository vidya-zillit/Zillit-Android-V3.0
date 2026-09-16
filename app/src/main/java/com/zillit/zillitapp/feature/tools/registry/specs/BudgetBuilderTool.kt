package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The BudgetBuilder tile. */
val BudgetBuilderTool = ToolSpec(
    identifier = "budget_builder_tool",
    icon = Icons.Outlined.Calculate,
    infoText = R.string.tool_info_budget_builder,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
