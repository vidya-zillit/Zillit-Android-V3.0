package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The DealMemo tile. */
val DealMemoTool = ToolSpec(
    identifier = "deal_memo_tool",
    icon = Icons.Outlined.Description,
    infoText = R.string.tool_info_deal_memo,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    destination = null,
)
