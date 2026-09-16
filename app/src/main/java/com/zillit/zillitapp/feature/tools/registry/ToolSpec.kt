package com.zillit.zillitapp.feature.tools.registry

import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.zillitapp.core.directory.ProjectTool
import com.zillit.zillitapp.navigation.Route

/**
 * Everything the app knows about one tool, in one place.
 *
 * ### Badges are not in here
 * A notification carries `section` → `tool` → `unit`, and its `tool` is the tool's own
 * `unit_name` — the label key the tools endpoint already sends. So the count is looked up by
 * that, and the sub-areas under `unit` are summed by grouping on `tool`. Listing the keys
 * here as well was duplication of something the server already states, and hand-copying it
 * is exactly how twelve of them ended up wrong.
 *
 * v2 describes a tool in four places that do not know about each other: a branch in the
 * click `when`, a branch in `getToolIcon`, a branch in `getSuffixMessage`, and a line in the
 * badge mapping. Adding a tool means four edits in three files, and missing one fails
 * silently — a missing icon branch renders a placeholder, a missing badge branch shows no
 * count. This is that description, once.
 *
 * @param identifier the key the tools endpoint uses, e.g. `catering_tool`. The primary key.
 * @param icon the tile's icon.
 * @param infoText the explainer behind the tile's (i).
 * @param infoTextAdmin the admin's version of it, where the tool has one. Several tools say
 *   something different to the person who can configure them than to the person using them.
 * @param videoFile the tutorial for this tool, as the file name under the tutorial bucket.
 *   Null means there is none and the info dialog offers no video — v2 falls back to a
 *   generic placeholder clip, which is worse than not offering one.
 * @param helpAnchor the section of the help site this tool is documented in.
 * @param destination where tapping the tile goes. **Null means not on Android yet**, and the
 *   tile says so instead of doing nothing — which is what a missing branch in v2's `when`
 *   does today.
 * @param availability whether the tile is shown at all, beyond the access flags the server
 *   sends.
 */
data class ToolSpec(
    val identifier: String,
    val icon: ImageVector,
    val infoText: Int,
    val infoTextAdmin: Int? = null,
    val videoFile: String? = null,
    val helpAnchor: String? = null,
    val destination: ((ProjectTool) -> Route?)? = null,
    val availability: ToolAvailability = ToolAvailability.Everyone,
) {
    /** The explainer for this viewer. */
    fun infoFor(isAdmin: Boolean): Int = if (isAdmin) infoTextAdmin ?: infoText else infoText
}

/** Who a tile is for, when the server's own access flags are not the whole rule. */
sealed interface ToolAvailability {
    data object Everyone : ToolAvailability

    /**
     * Hidden from accountants.
     *
     * One case: the standalone Invoices tile. An accountant's invoice counts are already
     * inside the Account Hub tile, so showing both cascades the same number twice.
     */
    data object NonAccountantsOnly : ToolAvailability

    /** Shown, but says the tool is only usable on the web rather than opening. */
    data object WebOnly : ToolAvailability
}
