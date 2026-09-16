package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TheaterComedy
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** Casting, where the project has one combined tool rather than the main and background split. */
val CastingTool = ToolSpec(
    identifier = "casting_tool",
    icon = Icons.Outlined.TheaterComedy,
    infoText = R.string.tool_info_casting_main,
    infoTextAdmin = R.string.tool_info_casting_main_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
