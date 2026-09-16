package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The ScriptNotes tile. */
val ScriptNotesTool = ToolSpec(
    identifier = "script_notes_tool",
    icon = Icons.Outlined.EditNote,
    infoText = R.string.tool_info_script_notes,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "continuty%20script%20notes.mp4",
    destination = null,
)
