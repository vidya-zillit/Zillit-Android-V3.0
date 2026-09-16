package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The CardExpenses tile. */
val CardExpensesTool = ToolSpec(
    identifier = "card_expenses_tool",
    icon = Icons.Outlined.CreditCard,
    infoText = R.string.tool_info_card_expenses,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
