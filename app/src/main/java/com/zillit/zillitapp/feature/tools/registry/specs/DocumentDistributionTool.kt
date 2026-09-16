package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderShared
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The DocumentDistribution tile. */
val DocumentDistributionTool = ToolSpec(
    identifier = "document_distribution_tool",
    icon = Icons.Outlined.FolderShared,
    infoText = R.string.tool_info_doc_distribution,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "How%20to%20use%20the%20Document%20Distribution%20Tool%20Android.mp4",
    destination = null,
)
