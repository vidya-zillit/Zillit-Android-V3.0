package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checkroom
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The WardrobeBackground tile. */
val WardrobeBackgroundTool = ToolSpec(
    identifier = "wardrobe_background_tool",
    icon = Icons.Outlined.Checkroom,
    infoText = R.string.tool_info_wardrobe_background,
    infoTextAdmin = R.string.tool_info_wardrobe_background_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "wardrobe%20background.mp4",
    helpAnchor = "wardrobe_background",
    destination = null,
)
