package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The CastingBackground tile. */
val CastingBackgroundTool = ToolSpec(
    identifier = "casting_background_tool",
    icon = Icons.Outlined.Groups,
    infoText = R.string.tool_info_casting_background,
    infoTextAdmin = R.string.tool_info_casting_background_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "casting%20background.mp4",
    destination = null,
)
