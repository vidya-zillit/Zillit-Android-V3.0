package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The DistributionList tile. */
val DistributionListTool = ToolSpec(
    identifier = "distribution_tool",
    icon = Icons.Outlined.Share,
    infoText = R.string.tool_info_distribution,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
