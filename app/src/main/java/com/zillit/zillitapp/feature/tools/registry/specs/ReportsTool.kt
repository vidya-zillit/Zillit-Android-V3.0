package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Reports tile. */
val ReportsTool = ToolSpec(
    identifier = "reports_tool",
    icon = Icons.Outlined.BarChart,
    infoText = R.string.tool_info_reports,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
