package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The PreProduction tile. */
val PreProductionTool = ToolSpec(
    identifier = "pre_production_tool",
    icon = Icons.Outlined.Event,
    infoText = R.string.tool_info_pre_production,
    infoTextAdmin = R.string.tool_info_pre_production_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "pre%20production%20Calendar%28tool%29.mp4",
    destination = null,
)
