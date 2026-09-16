package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The AdDashboard tile. */
val AdDashboardTool = ToolSpec(
    identifier = "ad_dashboard_tool",
    icon = Icons.Outlined.Dashboard,
    infoText = R.string.tool_info_ad_dashboard,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
