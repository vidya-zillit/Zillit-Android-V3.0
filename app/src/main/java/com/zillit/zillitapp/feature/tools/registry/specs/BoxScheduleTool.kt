package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The BoxSchedule tile. */
val BoxScheduleTool = ToolSpec(
    identifier = "box_schedule_tool",
    icon = Icons.Outlined.CalendarMonth,
    infoText = R.string.tool_info_box_schedule,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "How%20to%20use%20the%20BoxProduction%20Schedule%20Tool%20Android.mp4",
    destination = null,
)
