package com.zillit.zillitapp.feature.tools.ui.list

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/** One tile. */
@Immutable
data class ToolRowState(
    val identifier: String,
    val title: String,
    val icon: ImageVector,
    val badgeCount: Int = 0,
    val infoText: Int,
    /** The tutorial for this tool, ready to open. Null means there is none to offer. */
    val videoUrl: String? = null,
    /** Where this tool is documented. Null means it has no page of its own. */
    val helpUrl: String? = null,
    /** False when the tool has no screen on Android yet; tapping says so. */
    val openable: Boolean = true,
)

/** A section and the tiles in it. */
@Immutable
data class ToolSectionState(
    val identifier: String,
    val title: String,
    val tools: List<ToolRowState>,
) {
    /** Shown beside the header. The screenshot's "9". */
    val count: Int get() = tools.size
}

@Immutable
data class ToolsUiState(
    val sections: List<ToolSectionState> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val isAdmin: Boolean = false,
) {
    val hasQuery: Boolean get() = query.isNotBlank()

    /** Nothing to show. Which message to use depends on whether a search is running. */
    val isEmpty: Boolean get() = sections.isEmpty() && !loading
}
