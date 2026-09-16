package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAddAlt
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The ExternalUsers tile. */
val ExternalUsersTool = ToolSpec(
    identifier = "external_users_tool",
    icon = Icons.Outlined.PersonAddAlt,
    infoText = R.string.tool_info_external_users,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "how%20to%20add%20external%20users.mp4",
    destination = null,
)
