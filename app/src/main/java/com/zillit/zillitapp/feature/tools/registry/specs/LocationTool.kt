package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Location tile. */
val LocationTool = ToolSpec(
    identifier = "location_tool",
    icon = Icons.Outlined.Place,
    infoText = R.string.tool_info_location,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "location.mp4",
    helpAnchor = "location",
    destination = null,
)
