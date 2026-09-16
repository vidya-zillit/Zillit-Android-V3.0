package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checkroom
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** Wardrobe, where the project has one combined tool rather than the main and background split. */
val WardrobeTool = ToolSpec(
    identifier = "wardrobe_tool",
    icon = Icons.Outlined.Checkroom,
    infoText = R.string.tool_info_wardrobe_main,
    infoTextAdmin = R.string.tool_info_wardrobe_main_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
