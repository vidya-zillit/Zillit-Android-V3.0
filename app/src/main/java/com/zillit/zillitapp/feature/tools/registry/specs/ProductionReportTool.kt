package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Summarize
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The ProductionReport tile. */
val ProductionReportTool = ToolSpec(
    identifier = "production_report_tool",
    icon = Icons.Outlined.Summarize,
    infoText = R.string.tool_info_production_report,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "how%20to%20upload%20in%20production%20report.mp4",
    destination = null,
)
