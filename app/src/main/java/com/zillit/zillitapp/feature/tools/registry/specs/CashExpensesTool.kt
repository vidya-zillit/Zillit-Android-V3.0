package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalAtm
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The CashExpenses tile. */
val CashExpensesTool = ToolSpec(
    identifier = "cash_expenses_tool",
    icon = Icons.Outlined.LocalAtm,
    infoText = R.string.tool_info_cash_expenses,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
