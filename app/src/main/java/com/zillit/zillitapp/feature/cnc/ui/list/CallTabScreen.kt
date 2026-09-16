package com.zillit.zillitapp.feature.cnc.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallMissed
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.SearchField
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.labels.asLabelIfKey

/**
 * The Call tab.
 *
 * Calling itself is not wired in this phase; the log, its filters and the controls are here
 * so the layout is settled and nothing moves when the calling half lands.
 */
@Composable
fun CallTabScreen(
    state: CncUiState,
    onFilter: (CallFilter) -> Unit,
    onQuery: (String) -> Unit,
    onClearLog: () -> Unit,
    onJoinViaLink: () -> Unit,
    onCallDetails: (CallRow) -> Unit,
    onCallBack: (CallRow) -> Unit,
    onNewCall: () -> Unit,
    modifier: Modifier = Modifier,
    /** Fired as the list nears its end, to fetch the next page. */
    onLoadMore: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            CncChipRow(modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm)) {
                CncChip(
                    label = stringResource(R.string.cnc_call_recent),
                    selected = state.callFilter == CallFilter.RECENT,
                    onClick = { onFilter(CallFilter.RECENT) },
                )
                CncChip(
                    label = stringResource(R.string.cnc_call_missed),
                    selected = state.callFilter == CallFilter.MISSED,
                    onClick = { onFilter(CallFilter.MISSED) },
                    count = state.missedTotal,
                )
            }

            Row(
                modifier = Modifier.padding(
                    horizontal = ZillitTheme.spacing.lg,
                    vertical = ZillitTheme.spacing.xs,
                ),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(
                    query = state.query,
                    onQueryChange = onQuery,
                    placeholder = stringResource(R.string.action_search),
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onClearLog),
                    shape = RoundedCornerShape(12.dp),
                    color = ZillitTheme.colors.danger,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.cnc_clear_call_log),
                            tint = ZillitTheme.colors.textOnBrand,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.xs),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onJoinViaLink),
                    shape = CircleShape,
                    color = ZillitTheme.colors.surface,
                    border = BorderStroke(1.5.dp, ZillitTheme.colors.brand),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.PhoneInTalk,
                            contentDescription = null,
                            tint = ZillitTheme.colors.brand,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = stringResource(R.string.cnc_join_via_link),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ZillitTheme.colors.brand,
                        )
                    }
                }
            }

            when {
                state.loading && state.calls.isEmpty() -> LoadingState()

                state.calls.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Call,
                    title = stringResource(
                        if (state.hasQuery) R.string.cnc_no_match else R.string.cnc_no_calls,
                    ),
                    description = "",
                )

                else -> {
                    val listState = rememberLazyListState()

                    // Ask for the next page a few rows before the end, so the list fills as
                    // the user scrolls rather than stopping and then jumping.
                    LaunchedEffect(listState, state.calls.size) {
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                            .collect { last ->
                                if (last != null && last >= state.calls.size - LOAD_MORE_MARGIN) {
                                    onLoadMore()
                                }
                            }
                    }

                    LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(state.calls, key = { it.id }) { call ->
                        CallRowItem(
                            call = call,
                            onDetails = { onCallDetails(call) },
                            onCallBack = { onCallBack(call) },
                        )
                        HorizontalDivider(
                            color = ZillitTheme.colors.divider,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 68.dp),
                        )
                    }
                }
                }
            }
        }

        FloatingActionButton(
            onClick = onNewCall,
            containerColor = ZillitTheme.colors.brand,
            contentColor = ZillitTheme.colors.textOnBrand,
            modifier = Modifier.align(Alignment.BottomEnd).padding(ZillitTheme.spacing.lg),
        ) {
            Icon(Icons.Outlined.PhoneInTalk, contentDescription = stringResource(R.string.cnc_action_audio))
        }
    }
}

@Composable
private fun CallRowItem(
    call: CallRow,
    onDetails: () -> Unit,
    onCallBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceAvatar(
            initials = call.initials,
            online = false,
            pictureKey = call.pictureKey,
            thumbnailKey = call.thumbnailKey,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = call.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = call.subtitle.asLabelIfKey(),
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The arrow carries the direction and the colour carries the outcome, so a
                // missed call reads as missed without reading the words.
                Icon(
                    imageVector = when (call.direction) {
                        CallDirection.INCOMING -> Icons.AutoMirrored.Outlined.CallReceived
                        CallDirection.OUTGOING -> Icons.AutoMirrored.Outlined.CallMade
                        CallDirection.MISSED -> Icons.AutoMirrored.Outlined.CallMissed
                    },
                    contentDescription = null,
                    tint = when (call.direction) {
                        CallDirection.MISSED -> ZillitTheme.colors.danger
                        CallDirection.OUTGOING -> ZillitTheme.colors.success
                        CallDirection.INCOMING -> ZillitTheme.colors.accent
                    },
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = if (call.direction == CallDirection.MISSED) {
                        stringResource(R.string.cnc_call_missed_at, call.timestamp)
                    } else {
                        call.timestamp
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (call.direction == CallDirection.MISSED) {
                        ZillitTheme.colors.danger
                    } else {
                        ZillitTheme.colors.textTertiary
                    },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            RowAction(
                icon = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.cnc_action_info),
                onClick = onDetails,
                quiet = true,
            )
            RowAction(
                icon = Icons.Outlined.Call,
                contentDescription = stringResource(R.string.cnc_action_audio),
                onClick = onCallBack,
            )
        }
    }
}

/** How many rows from the end to start fetching. Enough to hide the round trip. */
private const val LOAD_MORE_MARGIN = 5
