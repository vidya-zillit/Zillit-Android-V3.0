package com.zillit.zillitapp.feature.cnc.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.components.SearchField
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.cnc.ui.list.ContactRow
import com.zillit.zillitapp.feature.cnc.ui.list.PresenceAvatar
import com.zillit.zillitapp.core.labels.asLabelIfKey

/**
 * Naming a group and choosing who is in it, on one screen.
 *
 * v2 splits this in two — pick people, then name the group — which means you cannot see
 * the name while choosing or change your mind about a member while typing it. Both halves
 * are here, and the chip strip keeps the choices visible while the list scrolls under it.
 */
@Composable
fun CreateGroupScreen(
    name: String,
    onNameChange: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    contacts: List<ContactRow>,
    selected: List<ContactRow>,
    onToggle: (ContactRow) -> Unit,
    onPickPhoto: () -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    isSaving: Boolean = false,
    /** True when adding people to a group that already exists and already has a name. */
    isAddingToExistingGroup: Boolean = false,
) {
    val selectedIds = selected.map { it.id }.toSet()
    // Both halves must be filled before the group is anything — an unnamed group with no
    // one in it is not a draft worth posting.
    // An existing group already has a name, so only the picking matters.
    val canCreate = selected.isNotEmpty() && !isSaving &&
        (isAddingToExistingGroup || name.isNotBlank())

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background),
    ) {
        ZillitTopBar(
            title = stringResource(
                if (isAddingToExistingGroup) {
                    R.string.cnc_add_members
                } else {
                    R.string.cnc_create_group_title
                },
            ),
            onBackClick = onBack,
            onHelpClick = null,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surface)
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            // Naming and the photo belong to creating a group. Adding people to one that
            // exists is only the picking, and an editable name here would look like a
            // rename that silently was not one.
            if (!isAddingToExistingGroup) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GroupPhotoButton(onClick = onPickPhoto)

                    FormTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = stringResource(R.string.cnc_group_name_label),
                        placeholder = stringResource(R.string.cnc_group_name_hint),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // The chosen people stay on screen while the list scrolls, so removing someone
            // never means scrolling back to find where they were.
            if (selected.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    items(items = selected, key = { it.id }) { contact ->
                        MemberChip(contact = contact, onRemove = { onToggle(contact) })
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ZillitTheme.spacing.lg,
                    vertical = ZillitTheme.spacing.sm,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.cnc_select_members),
                style = MaterialTheme.typography.labelLarge,
                color = ZillitTheme.colors.textSecondary,
            )
            if (selected.isNotEmpty()) {
                Text(
                    text = pluralStringResource(
                        R.plurals.cnc_selected_count,
                        selected.size,
                        selected.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = ZillitTheme.colors.brand,
                )
            }
        }

        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg),
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items = contacts, key = { it.id }) { contact ->
                MemberPickRow(
                    contact = contact,
                    checked = contact.id in selectedIds,
                    onToggle = { onToggle(contact) },
                )
                HorizontalDivider(color = ZillitTheme.colors.divider)
            }
        }

        PrimaryButton(
            text = stringResource(
                if (isAddingToExistingGroup) R.string.cnc_add_action else R.string.cnc_create_action,
            ),
            onClick = onCreate,
            enabled = canCreate,
            modifier = Modifier
                .fillMaxWidth()
                .padding(ZillitTheme.spacing.lg),
        )
    }
}

/** The camera square that stands in for a photo nobody has chosen yet. */
@Composable
private fun GroupPhotoButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(ZillitTheme.colors.brandSoft)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.PhotoCamera,
            contentDescription = stringResource(R.string.cnc_group_photo),
            tint = ZillitTheme.colors.brand,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** One chosen member, removable without leaving the strip. */
@Composable
private fun MemberChip(contact: ContactRow, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(ZillitTheme.colors.brandSoft)
            .clickable(onClick = onRemove)
            .padding(
                start = ZillitTheme.spacing.xs,
                end = ZillitTheme.spacing.sm,
                top = ZillitTheme.spacing.xs,
                bottom = ZillitTheme.spacing.xs,
            ),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(
            initials = contact.initials,
            pictureKey = contact.pictureKey,
            thumbnailKey = contact.thumbnailKey,
            size = 24.dp,
        )
        Text(
            // The full name, capped rather than shortened to the first name: a crew has two
            // Sahils and three Singhs, and two chips both reading "Sahil" say nothing about
            // who is actually in the group.
            text = contact.name,
            style = MaterialTheme.typography.labelMedium,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 132.dp),
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.cnc_remove_member, contact.name),
            tint = ZillitTheme.colors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** One person in the picker, with the tick that says whether they are in. */
@Composable
private fun MemberPickRow(
    contact: ContactRow,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .clickable(onClick = onToggle)
            .padding(
                horizontal = ZillitTheme.spacing.lg,
                vertical = ZillitTheme.spacing.md,
            ),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceAvatar(
            initials = contact.initials,
            online = contact.online,
            pictureKey = contact.pictureKey,
            thumbnailKey = contact.thumbnailKey,
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (contact.isProjectAdmin) {
                    Text(
                        text = stringResource(R.string.cnc_admin_suffix),
                        style = MaterialTheme.typography.labelMedium,
                        color = ZillitTheme.colors.textSecondary,
                        modifier = Modifier.padding(start = ZillitTheme.spacing.xs),
                    )
                }
            }
            Text(
                text = contact.designation.asLabelIfKey(),
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Icon(
            imageVector = if (checked) {
                Icons.Filled.CheckCircle
            } else {
                Icons.Outlined.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = if (checked) ZillitTheme.colors.brand else ZillitTheme.colors.textTertiary,
            modifier = Modifier.size(22.dp),
        )
    }
}
