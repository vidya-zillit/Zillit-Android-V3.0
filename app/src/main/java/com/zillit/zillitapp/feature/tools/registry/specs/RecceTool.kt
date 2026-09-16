package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Recce tile. */
val RecceTool = ToolSpec(
    identifier = "recce_tool",
    icon = Icons.Outlined.Explore,
    infoText = R.string.tool_info_recce,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "How%20to%20use%20the%20Recce%20Tool%20Android.mp4",
    helpAnchor = "recce",
    destination = null,
)
