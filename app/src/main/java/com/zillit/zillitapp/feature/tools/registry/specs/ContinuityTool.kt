package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Continuity tile. */
val ContinuityTool = ToolSpec(
    identifier = "continuity_tool",
    icon = Icons.Outlined.Movie,
    infoText = R.string.tool_info_continuity,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "How%20to%20upload%20in%20continuty.mp4",
    helpAnchor = "continuity",
    destination = null,
)
