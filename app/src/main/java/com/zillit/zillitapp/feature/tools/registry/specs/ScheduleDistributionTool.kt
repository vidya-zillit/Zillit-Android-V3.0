package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** Three badge keys: the full schedule, its pages and the one-liner all count onto this tile. */
val ScheduleDistributionTool = ToolSpec(
    identifier = "schedule_distribution_tool",
    icon = Icons.Outlined.CalendarToday,
    infoText = R.string.tool_info_schedule,
    infoTextAdmin = R.string.tool_info_schedule_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "schedule%20full%20and%20one%20line.mp4",
    destination = null,
)
