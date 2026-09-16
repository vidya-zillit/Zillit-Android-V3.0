package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Style
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The WardrobeMain tile. */
val WardrobeMainTool = ToolSpec(
    identifier = "wardrobe_main_tool",
    icon = Icons.Outlined.Style,
    infoText = R.string.tool_info_wardrobe_main,
    infoTextAdmin = R.string.tool_info_wardrobe_main_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "wardrobe%20main.mp4",
    helpAnchor = "wardrobe_main",
    destination = null,
)
