package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingCart
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** Non-accountant invoice notifications arrive under the purchase-order key, so that one key already carries both. */
val PurchaseOrderTool = ToolSpec(
    identifier = "purchase_order_tool",
    icon = Icons.Outlined.ShoppingCart,
    infoText = R.string.tool_info_purchase_order,
    infoTextAdmin = R.string.tool_info_purchase_order_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "purhcase%20order%20tools.mp4",
    destination = null,
)
