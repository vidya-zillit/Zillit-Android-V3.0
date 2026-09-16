package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventNote
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Dod tile. */
val DodTool = ToolSpec(
    identifier = "dod_tool",
    icon = Icons.Outlined.EventNote,
    infoText = R.string.tool_info_dod,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "how%20to%20upload%20DOD.mp4",
    destination = null,
)
