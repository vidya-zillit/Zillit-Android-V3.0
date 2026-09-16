package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Production tile. */
val ProductionTool = ToolSpec(
    identifier = "production_tool",
    icon = Icons.Outlined.Videocam,
    infoText = R.string.tool_info_production,
    infoTextAdmin = R.string.tool_info_production_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "production%20Calendar%28tool%29.mp4",
    destination = null,
)
