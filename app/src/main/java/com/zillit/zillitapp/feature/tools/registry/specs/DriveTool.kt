package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Drive tile. */
val DriveTool = ToolSpec(
    identifier = "drive_tool",
    icon = Icons.Outlined.Cloud,
    infoText = R.string.tool_info_drive,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "How%20to%20use%20the%20Drive%20Tool%20Android.mp4",
    helpAnchor = "drive",
    destination = null,
)
