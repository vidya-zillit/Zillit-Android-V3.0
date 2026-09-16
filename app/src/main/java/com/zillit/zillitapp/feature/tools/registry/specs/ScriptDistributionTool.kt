package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** Two badge keys: the full script and its pages. */
val ScriptDistributionTool = ToolSpec(
    identifier = "script_distribution_tool",
    icon = Icons.Outlined.MenuBook,
    infoText = R.string.tool_info_script,
    infoTextAdmin = R.string.tool_info_script_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "script%20and%20pages%20distribution.mp4",
    destination = null,
)
