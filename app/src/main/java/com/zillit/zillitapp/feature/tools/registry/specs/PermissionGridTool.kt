package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridOn
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** No badge of its own: it configures rights rather than carrying messages. */
val PermissionGridTool = ToolSpec(
    identifier = "permission_grid_tool",
    icon = Icons.Outlined.GridOn,
    infoText = R.string.tool_info_permission_grid,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "how%20to%20manage%20viewing%20and%20posting%20rights.mp4",
    destination = null,
)
