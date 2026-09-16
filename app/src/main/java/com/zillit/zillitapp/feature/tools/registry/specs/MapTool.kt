package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Map tile. */
val MapTool = ToolSpec(
    identifier = "map_tool",
    icon = Icons.Outlined.Map,
    infoText = R.string.tool_info_map,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "How%20to%20use%20the%20Map%20Tool%20Android.mp4",
    helpAnchor = "map",
    destination = null,
)
