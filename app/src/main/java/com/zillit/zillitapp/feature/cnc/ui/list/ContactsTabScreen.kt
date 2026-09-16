package com.zillit.zillitapp.feature.cnc.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The Contacts tab: everyone on the project, with what you can do to them on the row.
 *
 * Five actions is a lot for one row, so the star is drawn quiet — it is the one that changes
 * a list rather than starting something, and five equally loud buttons read as a toolbar.
 */
@Composable
fun ContactsTabScreen(
    state: CncUiState,
    onQuery: (String) -> Unit,
    onOpenProfile: (ContactRow) -> Unit,
    onMessage: (ContactRow) -> Unit,
    onAudioCall: (ContactRow) -> Unit,
    onVideoCall: (ContactRow) -> Unit,
    onShareLocation: (ContactRow) -> Unit,
    onToggleFavourite: (ContactRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SearchField(
            query = state.query,
            onQueryChange = onQuery,
            placeholder = stringResource(R.string.action_search),
            modifier = Modifier.padding(
                horizontal = ZillitTheme.spacing.lg,
                vertical = ZillitTheme.spacing.xs,
            ),
        )

        when {
            state.loading && state.contacts.isEmpty() -> LoadingState()

            state.contacts.isEmpty() -> EmptyState(
                icon = Icons.Outlined.People,
                title = stringResource(
                    if (state.hasQuery) R.string.cnc_no_match else R.string.cnc_no_contacts,
                ),
                description = "",
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = ZillitTheme.spacing.xl),
            ) {
                items(state.contacts, key = { it.id }) { contact ->
                    ContactRowItem(
                        contact = contact,
                        onOpenProfile = { onOpenProfile(contact) },
                        onMessage = { onMessage(contact) },
                        onAudioCall = { onAudioCall(contact) },
                        onVideoCall = { onVideoCall(contact) },
                        onShareLocation = { onShareLocation(contact) },
                        onToggleFavourite = { onToggleFavourite(contact) },
                    )
                    HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun ContactRowItem(
    contact: ContactRow,
    onOpenProfile: () -> Unit,
    onMessage: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onShareLocation: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        // The avatar and name open the profile; the buttons below do their own thing. Making
        // the whole row tappable would swallow taps meant for the actions.
        PresenceAvatar(
            initials = contact.initials,
            online = contact.online,
            pictureKey = contact.pictureKey,
            thumbnailKey = contact.thumbnailKey,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenProfile),
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (contact.isProjectAdmin) {
                    Text(
                        text = stringResource(R.string.cnc_admin_suffix),
                        style = MaterialTheme.typography.labelMedium,
                        color = ZillitTheme.colors.textTertiary,
                    )
                }
            }
            Text(
                text = contact.designation.asLabelIfKey(),
                style = MaterialTheme.typography.labelMedium,
                color = ZillitTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowAction(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.cnc_action_chat), onMessage)
                RowAction(Icons.Outlined.Call, stringResource(R.string.cnc_action_audio), onAudioCall)
                RowAction(Icons.Outlined.Videocam, stringResource(R.string.cnc_action_video), onVideoCall)
                RowAction(Icons.Outlined.Place, stringResource(R.string.cnc_action_location), onShareLocation)
                FavouriteButton(
                    favourite = contact.favourite,
                    onClick = onToggleFavourite,
                    contentDescription = stringResource(R.string.cnc_action_favourite),
                )
            }
        }
    }
}
