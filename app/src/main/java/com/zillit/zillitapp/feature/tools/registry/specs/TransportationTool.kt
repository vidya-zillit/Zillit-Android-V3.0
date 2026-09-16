package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Transportation tile. */
val TransportationTool = ToolSpec(
    identifier = "transportation_tool",
    icon = Icons.Outlined.DirectionsCar,
    infoText = R.string.tool_info_transportation,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    helpAnchor = "transportation",
    destination = null,
)
