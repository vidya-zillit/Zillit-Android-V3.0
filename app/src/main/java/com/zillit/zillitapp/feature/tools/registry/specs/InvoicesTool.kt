package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec
import com.zillit.zillitapp.feature.tools.registry.ToolAvailability

/** Hidden from accountants, whose invoice counts are already inside Account Hub. Showing both cascades the same number twice. */
val InvoicesTool = ToolSpec(
    identifier = "invoices_tool",
    icon = Icons.Outlined.ReceiptLong,
    infoText = R.string.tool_info_invoices,
    availability = ToolAvailability.NonAccountantsOnly,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
