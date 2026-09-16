package com.zillit.zillitapp.feature.project.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.labels.LocalLabels
import com.zillit.zillitapp.core.labels.rememberDictionaries
import com.zillit.zillitapp.core.labels.resolveLabel
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.ErrorState
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.resolve
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.ui.toUiText
import com.zillit.zillitapp.core.ui.window.WindowSize
import com.zillit.zillitapp.core.ui.window.contentMaxWidth
import com.zillit.zillitapp.core.ui.window.currentWindowSize
import com.zillit.zillitapp.core.ui.window.horizontalPadding
import com.zillit.zillitapp.feature.project.domain.Project

private const val UNREAD_DISPLAY_CAP = 99

/** Below this a card's content starts truncating, so it drives the column count. */
private val MIN_CARD_WIDTH = 320.dp

private const val TYPE_SEPARATOR = " · "

@Composable
fun ProjectListRoute(
    onProjectClick: (Project) -> Unit,
    onCreateProject: () -> Unit,
    onJoinProject: () -> Unit,
    onScanQrCode: () -> Unit,
    onHelpClick: () -> Unit,
    viewModel: ProjectListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ProjectListScreen(
        uiState = uiState,
        onProjectClick = { project ->
            // Establish the session first; navigating without it leaves every downstream
            // request unscoped.
            if (viewModel.onProjectSelected(project)) onProjectClick(project)
        },
        onCreateProject = onCreateProject,
        onJoinProject = onJoinProject,
        onScanQrCode = onScanQrCode,
        onHelpClick = onHelpClick,
        onRefresh = viewModel::refresh,
        onFavouriteToggle = viewModel::onFavouriteToggle,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onSearchActiveChange = viewModel::onSearchActiveChange,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    uiState: ProjectListUiState,
    onProjectClick: (Project) -> Unit,
    onCreateProject: () -> Unit,
    onJoinProject: () -> Unit,
    onScanQrCode: () -> Unit,
    onHelpClick: () -> Unit,
    onRefresh: () -> Unit,
    onFavouriteToggle: (Project) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
) {
    val windowSize = currentWindowSize()
    var infoProject by remember { mutableStateOf<Project?>(null) }

    infoProject?.let { project ->
        ProjectInfoSheet(project = project, onDismiss = { infoProject = null })
    }

    // Back closes search rather than leaving the screen, so the gesture agrees with the
    // visible Close button.
    BackHandler(enabled = uiState.isSearchActive) { onSearchActiveChange(false) }

    Scaffold(
        containerColor = ZillitTheme.colors.background,
        // The custom header applies its own top inset, so the body only needs the
        // horizontal (cutout) and bottom (navigation bar) sides.
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
        topBar = {
            if (uiState.isSearchActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Same inset source as ZillitTopBar — must not be safeDrawing,
                        // whose IME component animates and would drop the top inset the
                        // moment the search keyboard opens.
                        .windowInsetsPadding(WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(
                            horizontal = windowSize.horizontalPadding(),
                            vertical = ZillitTheme.spacing.sm,
                        ),
                ) {
                    ProjectSearchBar(
                        query = uiState.searchQuery,
                        onQueryChange = onSearchQueryChange,
                        onClose = { onSearchActiveChange(false) },
                    )
                }
            } else {
                ZillitTopBar(
                    title = stringResource(R.string.project_list_title),
                    // Search is hidden when there is nothing to search — an empty account
                    // should not offer a filter over zero rows.
                    actions = {
                        if (uiState.totalProjectCount > 0) {
                            IconButton(onClick = { onSearchActiveChange(true) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.project_search_open),
                                    tint = ZillitTheme.colors.textPrimary,
                                )
                            }
                        }
                    },
                    onScanQrClick = onScanQrCode,
                    onHelpClick = onHelpClick,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.isLoading -> LoadingState()

                uiState.isBlockingError -> ErrorState(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.project_list_error_title),
                    description = uiState.error.toUiText().resolve(),
                    onRetry = onRefresh,
                    retryLabel = stringResource(R.string.action_retry),
                )

                uiState.isEmpty -> ProjectEmptyState(
                    onCreateProject = onCreateProject,
                    onJoinProject = onJoinProject,
                )

                uiState.isEmptySearchResult -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.project_search_empty_title),
                    description = stringResource(
                        R.string.project_search_empty_description,
                        uiState.searchQuery,
                    ),
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = onRefresh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        ProjectGrid(
                            projects = uiState.projects,
                            windowSize = windowSize,
                            onProjectClick = onProjectClick,
                            onFavouriteToggle = onFavouriteToggle,
                            onInfoClick = { infoProject = it },
                        )
                    }

                    // Start/Join stay reachable once the user already has projects —
                    // in the empty state they are the whole screen, here they sit
                    // below the list so they never scroll out of reach.
                    if (!uiState.isSearchActive) {
                        ProjectListActions(
                            onCreateProject = onCreateProject,
                            onJoinProject = onJoinProject,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shown when the account has no projects — the two ways in.
 *
 * Creating is primary and joining secondary because a user with no projects at all is
 * more often starting one; an invited user arrives with a code and will look for the
 * second button deliberately.
 */
@Composable
private fun ProjectEmptyState(
    onCreateProject: () -> Unit,
    onJoinProject: () -> Unit,
) {
    EmptyState(
        icon = Icons.Outlined.MovieFilter,
        title = stringResource(R.string.project_list_empty_title),
        description = stringResource(R.string.project_list_empty_description),
    ) {
        PrimaryButton(
            text = stringResource(R.string.project_action_create),
            onClick = onCreateProject,
            leadingIcon = Icons.Outlined.Add,
        )
        SecondaryButton(
            text = stringResource(R.string.project_action_join),
            onClick = onJoinProject,
            leadingIcon = Icons.Outlined.GroupAdd,
        )
    }
}

/** Persistent Start/Join actions under a populated list. */
@Composable
private fun ProjectListActions(
    onCreateProject: () -> Unit,
    onJoinProject: () -> Unit,
) {
    val windowSize = currentWindowSize()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.background)
            .padding(
                horizontal = windowSize.horizontalPadding(),
                vertical = ZillitTheme.spacing.md,
            ),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        PrimaryButton(
            text = stringResource(R.string.project_action_create),
            onClick = onCreateProject,
            leadingIcon = Icons.Outlined.Add,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(
            text = stringResource(R.string.project_action_join),
            onClick = onJoinProject,
            leadingIcon = Icons.Outlined.GroupAdd,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProjectGrid(
    projects: List<Project>,
    windowSize: WindowSize,
    onProjectClick: (Project) -> Unit,
    onFavouriteToggle: (Project) -> Unit,
    onInfoClick: (Project) -> Unit,
) {
    // rememberLazyGridState saves through rememberSaveable, so scroll position survives
    // rotation and process death.
    val gridState = rememberLazyGridState()
    val maxWidth = windowSize.contentMaxWidth()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            // Adaptive, not Fixed: columns are derived from the width actually available,
            // so phone-portrait, phone-landscape, tablet and split-screen all fall out of
            // one rule. A fixed count left a single card stranded in a third of a
            // landscape screen.
            columns = GridCells.Adaptive(minSize = MIN_CARD_WIDTH),
            state = gridState,
            modifier = Modifier
                .fillMaxHeight()
                .then(
                    if (maxWidth != Dp.Unspecified) {
                        Modifier.widthIn(max = maxWidth)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                ),
            contentPadding = PaddingValues(
                start = windowSize.horizontalPadding(),
                end = windowSize.horizontalPadding(),
                top = ZillitTheme.spacing.sm,
                bottom = ZillitTheme.spacing.xxl,
            ),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            items(items = projects, key = { it.id }) { project ->
                ProjectCard(
                    project = project,
                    onClick = { onProjectClick(project) },
                    onFavouriteToggle = { onFavouriteToggle(project) },
                    onInfoClick = { onInfoClick(project) },
                )
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onFavouriteToggle: () -> Unit,
    onInfoClick: () -> Unit,
) {
    val context = LocalContext.current

    // Resolved here, in the composable, because these are server label keys and the
    // dictionary lives in the composition — see LabelResolution.kt.
    // Every dictionary, not just labels: a key can live in any of the three.
    val labels = rememberDictionaries()
    val typeLine = remember(project.typeLabelKeys, labels) {
        project.typeLabelKeys.joinToString(TYPE_SEPARATOR) { labels.resolve(it) }
    }

    val favouriteDescription = stringResource(
        if (project.isFavourite) {
            R.string.project_favourite_remove
        } else {
            R.string.project_favourite_add
        },
        project.name,
    )

    // One description for the row, so a screen reader announces the project once instead
    // of reading avatar, name, badge and count as four separate items. The favourite
    // button stays separately focusable because it is a distinct action.
    val rowDescription = buildString {
        append(context.getString(R.string.cd_project_avatar, project.name))
        if (typeLine.isNotEmpty()) append(", $typeLine")
        if (project.isFavourite) append(", ${context.getString(R.string.cd_favourite)}")
        if (project.isAdmin) append(", ${context.getString(R.string.project_badge_admin)}")
        if (project.unreadCount > 0) {
            append(", ${context.getString(R.string.cd_unread_count, project.unreadCount)}")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZillitTheme.shapes.large))
            .background(ZillitTheme.colors.surface)
            .border(
                width = 1.dp,
                color = ZillitTheme.colors.border,
                shape = RoundedCornerShape(ZillitTheme.shapes.large),
            )
            .clickable(onClick = onClick)
            .padding(
                start = ZillitTheme.spacing.md,
                end = ZillitTheme.spacing.sm,
                top = ZillitTheme.spacing.md,
                bottom = ZillitTheme.spacing.md,
            ),
        // No blanket spacing here: the trailing icons supply their own, and a uniform
        // gap pushed info and the star far apart.
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = rowDescription },
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Badge overlays the avatar's top-right corner rather than sitting inline,
            // so the count reads as belonging to this project rather than to the row.
            // The wrapper is padded so the badge can overflow the tile without clipping.
            Box(modifier = Modifier.padding(top = 6.dp, end = 6.dp)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = ZillitTheme.colors.brandSoft,
                            shape = RoundedCornerShape(ZillitTheme.shapes.medium),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = project.initials,
                        style = MaterialTheme.typography.titleMedium,
                        color = ZillitTheme.colors.brand,
                    )
                }

                if (project.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            // Small offset so the badge sits ON the tile's corner rather
                            // than floating clear of it.
                            .offset(x = 7.dp, y = (-7).dp)
                            .background(ZillitTheme.colors.danger, CircleShape)
                            // Thin surface-coloured ring separates it from the tile
                            // without the heavy outline a 2dp border produced.
                            .border(2.dp, ZillitTheme.colors.surface, CircleShape)
                            .widthIn(min = 24.dp)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (project.unreadCount > UNREAD_DISPLAY_CAP) {
                                stringResource(R.string.badge_overflow, UNREAD_DISPLAY_CAP)
                            } else {
                                project.unreadCount.toString()
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (typeLine.isNotEmpty()) {
                    Text(
                        text = typeLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                project.deletionHoursRemaining?.let { hours ->
                    Text(
                        text = pluralStringResource(
                            R.plurals.project_deleting_in_hours,
                            hours.toInt(),
                            hours.toInt(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = ZillitTheme.colors.danger,
                    )
                }
            }
        }

        if (project.isAdmin) {
            Text(
                text = stringResource(R.string.project_badge_admin),
                style = MaterialTheme.typography.labelSmall,
                color = ZillitTheme.colors.accent,
                modifier = Modifier
                    .background(
                        color = ZillitTheme.colors.accentSoft,
                        shape = RoundedCornerShape(ZillitTheme.shapes.pill),
                    )
                    .padding(
                        horizontal = ZillitTheme.spacing.sm,
                        vertical = ZillitTheme.spacing.xs,
                    ),
            )
        }

        // Info and favourite in their own Row with zero spacing — as siblings of the
        // outer Row they inherited its gap and drifted apart.
        Row(
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onInfoClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.cd_project_info, project.name),
                    tint = ZillitTheme.colors.textTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }

            IconButton(onClick = onFavouriteToggle, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (project.isFavourite) {
                        Icons.Filled.Star
                    } else {
                        Icons.Outlined.StarBorder
                    },
                    contentDescription = favouriteDescription,
                    tint = if (project.isFavourite) {
                        ZillitTheme.colors.warning
                    } else {
                        ZillitTheme.colors.textTertiary
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
