package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Draw
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The ESignature tile. */
val ESignatureTool = ToolSpec(
    identifier = "e_signature_tool",
    icon = Icons.Outlined.Draw,
    infoText = R.string.tool_info_e_signature,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "How%20to%20use%20the%20E-Signature%20Tool%28Admin%20side%29%20Android.mp4",
    destination = null,
)
