package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The ConfidentialInfo tile. */
val ConfidentialInfoTool = ToolSpec(
    identifier = "confidential_info_tool",
    icon = Icons.Outlined.Lock,
    infoText = R.string.tool_info_confidential,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "How%20to%20upload%20in%20confidential%20info.mp4",
    destination = null,
)
