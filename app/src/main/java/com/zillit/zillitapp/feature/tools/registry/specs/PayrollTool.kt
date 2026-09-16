package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Paid
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Payroll tile. */
val PayrollTool = ToolSpec(
    identifier = "payroll_tool",
    icon = Icons.Outlined.Paid,
    infoText = R.string.tool_info_payroll,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
