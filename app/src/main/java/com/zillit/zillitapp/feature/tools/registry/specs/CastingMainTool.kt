package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TheaterComedy
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The CastingMain tile. */
val CastingMainTool = ToolSpec(
    identifier = "casting_main_tool",
    icon = Icons.Outlined.TheaterComedy,
    infoText = R.string.tool_info_casting_main,
    infoTextAdmin = R.string.tool_info_casting_main_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "casting%20main.mp4",
    destination = null,
)
