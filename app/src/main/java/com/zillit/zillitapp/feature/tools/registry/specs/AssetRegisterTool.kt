package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The AssetRegister tile. */
val AssetRegisterTool = ToolSpec(
    identifier = "asset_report_tool",
    icon = Icons.Outlined.Inventory2,
    infoText = R.string.tool_info_asset_register,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "how%20to%20generate%20view%20asset%20report.mp4",
    destination = null,
)
