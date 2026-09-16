package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The only tile that opens another tab rather than a screen. */
val EmailTool = ToolSpec(
    identifier = "email_tool",
    icon = Icons.Outlined.Email,
    infoText = R.string.tool_info_email,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    helpAnchor = "email",
    destination = null,
)
