package com.zillit.zillitapp.core.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.labels.asServerText
import com.zillit.zillitapp.core.labels.resolve
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/** A project a message can be forwarded into. */
data class ForwardProject(
    val id: String,
    val userId: String,
    val name: String,
)

/** A destination inside a project — a unit today, a C&C chat or group later. */
data class ForwardTarget(
    val id: String,
    /** Server label key or plain name; resolved at render. */
    val name: String,
    val canPost: Boolean,
    /** Posting here replaces what is already there, so it needs a confirmation first. */
    val isCallSheet: Boolean = false,
)

/** What the picker was opened for. Same picker, different destination. */
enum class ForwardMode { FORWARD, SHARE }

/**
 * The one forward/share picker, for every chat surface.
 *
 * Two levels, because a target can be in **another project**: pick the project (defaults to
 * the one you are in), then pick a destination inside it. v2 does the same, re-fetching that
 * project's tools and profile on selection — here the destinations are fetched on demand,
 * because another project's units are only in Realm if it has been opened.
 *
 * **One message, one destination.** v2 opens this list with `isMultipleSelectAllowed =
 * false`, and a forward carries a single message — unlike Delete and Share, which act on a
 * multi-message selection. Posting into a Call Sheet needs its replace confirmation, which
 * only makes sense for one destination at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardShareSheet(
    mode: ForwardMode,
    projects: List<ForwardProject>,
    currentProjectId: String,
    /** Destinations for [selectedProject]; null while they are being fetched. */
    targets: List<ForwardTarget>?,
    onProjectSelected: (ForwardProject) -> Unit,
    onConfirm: (projectId: String, targetIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
    selectedProject: ForwardProject? = null,
) {
    val project = selectedProject ?: projects.firstOrNull { it.id == currentProjectId }
    // Reset on every project change: an id chosen in one project means nothing in another.
    var selected by remember(project?.id) { mutableStateOf<String?>(null) }
    var pickingProject by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (mode == ForwardMode.FORWARD) R.string.option_forward else R.string.option_share,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = ZillitTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )

                val chosenTarget = selected
                if (chosenTarget != null && project != null) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(ZillitTheme.colors.brand, CircleShape)
                            .clickable {
                                onConfirm(project.id, listOf(chosenTarget))
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.option_forward),
                            tint = ZillitTheme.colors.textOnBrand,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // The project row is a control, not a label: forwarding across projects is a
            // first-class case, and hiding it behind a menu would make it undiscoverable.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.lg)
                    .clip(RoundedCornerShape(ZillitTheme.shapes.small))
                    .background(ZillitTheme.colors.surfaceSunken)
                    .clickable { pickingProject = !pickingProject }
                    .padding(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.forward_project),
                        style = MaterialTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textTertiary,
                    )
                    Text(
                        text = project?.name.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = ZillitTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            HorizontalDivider(
                color = ZillitTheme.colors.divider,
                modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
            )

            when {
                pickingProject -> LazyColumn {
                    items(projects, key = { it.id }) { candidate ->
                        PickerRow(
                            label = candidate.name,
                            isSelected = candidate.id == project?.id,
                            enabled = true,
                            onClick = {
                                pickingProject = false
                                onProjectSelected(candidate)
                            },
                        )
                    }
                }

                // Null means the destinations for a newly chosen project are still loading.
                targets == null -> Box(
                    modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = ZillitTheme.colors.brand)
                }

                targets.isEmpty() -> Text(
                    text = stringResource(R.string.forward_no_targets),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                )

                else -> LazyColumn {
                    items(targets, key = { it.id }) { target ->
                        PickerRow(
                            label = target.name.asServerText().resolve(),
                            isSelected = target.id == selected,
                            enabled = target.canPost,
                            trailingText = if (target.canPost) {
                                null
                            } else {
                                stringResource(R.string.forward_read_only)
                            },
                            onClick = { selected = target.id },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    trailingText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) ZillitTheme.colors.textPrimary else ZillitTheme.colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        when {
            trailingText != null -> Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.textTertiary,
            )

            isSelected -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = ZillitTheme.colors.brand,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
