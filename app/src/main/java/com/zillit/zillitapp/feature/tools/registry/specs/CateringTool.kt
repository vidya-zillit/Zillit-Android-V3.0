package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restaurant
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Catering tile. */
val CateringTool = ToolSpec(
    identifier = "catering_tool",
    icon = Icons.Outlined.Restaurant,
    infoText = R.string.tool_info_catering,
    infoTextAdmin = R.string.tool_info_catering_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "how%20to%20upload%20in%20catering%20for%20caterer.mp4",
    helpAnchor = "catering",
    destination = null,
)
