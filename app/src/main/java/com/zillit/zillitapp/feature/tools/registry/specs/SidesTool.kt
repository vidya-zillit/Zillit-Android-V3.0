package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Sides tile. */
val SidesTool = ToolSpec(
    identifier = "sides_tool",
    icon = Icons.Outlined.ContentCopy,
    infoText = R.string.tool_info_sides,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "How%20to%20use%20the%20Sides%20Tool%20Android.mp4",
    helpAnchor = "sides",
    destination = null,
)
