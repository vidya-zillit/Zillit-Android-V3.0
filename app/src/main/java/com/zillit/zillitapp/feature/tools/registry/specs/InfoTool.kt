package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Info tile. */
val InfoTool = ToolSpec(
    identifier = "info_tool",
    icon = Icons.Outlined.Info,
    infoText = R.string.tool_info_info,
    infoTextAdmin = R.string.tool_info_info_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "How%20to%20upload%20in%20info.mp4",
    helpAnchor = "info",
    destination = null,
)
