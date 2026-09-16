package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Payments
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Accounts tile. */
val AccountsTool = ToolSpec(
    identifier = "accounting_tool",
    icon = Icons.Outlined.Payments,
    infoText = R.string.tool_info_accounts,
    infoTextAdmin = R.string.tool_info_accounts_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "how%20to%20upload%20in%20account%20for%20accountant.mp4",
    helpAnchor = "accounts",
    destination = null,
)
