package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ListAlt
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** Generates a list that is published into Info rather than opening a screen of its own. */
val CrewListTool = ToolSpec(
    identifier = "generate_crew_list_tool",
    icon = Icons.Outlined.ListAlt,
    infoText = R.string.tool_info_crew_list,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "How%20to%20view%20and%20publish%20cew%20list.mp4",
    destination = null,
)
