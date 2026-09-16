package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** Its badge is a recomputed total that already folds in purchase orders and invoices, which is why Invoices is hidden from accountants. */
val AccountHubTool = ToolSpec(
    identifier = "account_hub_tool",
    icon = Icons.Outlined.AccountBalance,
    infoText = R.string.tool_info_account_hub,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
