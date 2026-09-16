package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FactCheck
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The FormsAndSignature tile. */
val FormsAndSignatureTool = ToolSpec(
    identifier = "forms_and_signature_tool",
    icon = Icons.Outlined.FactCheck,
    infoText = R.string.tool_info_forms,
    infoTextAdmin = R.string.tool_info_forms_admin,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "Contract%20tool%20admin.mp4",
    destination = null,
)
