package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** Identifier and badge key disagree: `callsheet_tool` against `call_sheet_label`. */
val CallSheetTool = ToolSpec(
    identifier = "callsheet_tool",
    icon = Icons.Outlined.Assignment,
    infoText = R.string.tool_info_callsheet,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
