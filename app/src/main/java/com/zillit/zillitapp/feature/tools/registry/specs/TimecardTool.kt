package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Timecard tile. */
val TimecardTool = ToolSpec(
    identifier = "timecard_tool",
    icon = Icons.Outlined.Schedule,
    infoText = R.string.tool_info_timecard,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
