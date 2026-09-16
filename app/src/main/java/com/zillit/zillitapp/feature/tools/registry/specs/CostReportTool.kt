package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TrendingUp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The CostReport tile. */
val CostReportTool = ToolSpec(
    identifier = "cost_report_tool",
    icon = Icons.Outlined.TrendingUp,
    infoText = R.string.tool_info_cost_report,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
