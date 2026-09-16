package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The SaPortal tile. */
val SaPortalTool = ToolSpec(
    identifier = "supporting_artistes_extras_tool",
    icon = Icons.Outlined.Badge,
    infoText = R.string.tool_info_sa_portal,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
