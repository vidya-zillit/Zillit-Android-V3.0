package com.zillit.zillitapp.feature.email.ui.settings

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.EmptyState
import com.zillit.zillitapp.core.ui.components.FormTextField
import com.zillit.zillitapp.core.ui.components.LoadingState
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.components.SettingsGroup
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailGroup

/**
 * Email groups — v2's `fragment_email_groups` and `item_email_group`.
 *
 * Admin-only, gated by the settings screen that opens it. A group is a named set of project
 * users that resolves to **one address** on the wire: sending to it puts the group's own
 * mailbox in the To line and the server fans it out.
 */
@Composable
fun EmailGroupsScreen(
    groups: List<EmailGroup>,
    loading: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (EmailGroup) -> Unit,
    onDelete: (EmailGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ZillitTheme.colors.background,
        topBar = {
            ZillitTopBar(
                title = stringResource(R.string.email_groups),
                onBackClick = onBack,
                onHelpClick = null,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = ZillitTheme.colors.brand,
                contentColor = ZillitTheme.colors.textOnBrand,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.email_group_add),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                loading -> LoadingState()

                groups.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Groups,
                    title = stringResource(R.string.email_group_empty_title),
                    description = stringResource(R.string.email_group_empty_subtitle),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(ZillitTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    items(groups, key = { it.id }) { group ->
                        EmailGroupCard(
                            group = group,
                            onEdit = { onEdit(group) },
                            onDelete = { onDelete(group) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmailGroupCard(
    group: EmailGroup,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    SettingsGroup {
        Column(modifier = Modifier.padding(ZillitTheme.spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.groupName,
                    style = MaterialTheme.typography.titleSmall,
                    color = ZillitTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.edit),
                    tint = ZillitTheme.colors.brand,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onEdit),
                )
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.delete),
                    tint = ZillitTheme.colors.danger,
                    modifier = Modifier
                        .padding(start = ZillitTheme.spacing.md)
                        .size(22.dp)
                        .clickable(onClick = onDelete),
                )
            }

            Text(
                text = pluralStringResource(
                    R.plurals.email_group_member_count,
                    group.memberEmails.size,
                    group.memberEmails.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textTertiary,
                modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
            )

            if (group.memberEmails.isNotEmpty()) {
                Text(
                    text = group.memberEmails.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = ZillitTheme.spacing.xxs),
                )
            }
        }
    }
}

/** One chosen member, as the editor lists them. */
data class GroupMember(
    val userId: String,
    val name: String,
    val email: String,
    val designation: String = "",
    val pictureKey: String? = null,
    val thumbnailKey: String? = null,
) {
    val initials: String
        get() = name.trim().split(' ').filter { it.isNotBlank() }.take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifBlank { "?" }
}

/**
 * The group editor — v2's `fragment_edit_email_group`.
 *
 * The name is **read-only when editing**, which is v2's rule and the server's: a group's
 * address is derived from its name, so renaming it would orphan mail already addressed to it.
 *
 * Members are project users only — there is no free-form address field, by design. Worth
 * knowing about v2's version: it rebuilds the member list by resolving each stored address
 * back to a project user, so a member who has since left the project is **silently dropped
 * and lost on the next save**. Here the members are carried as [GroupMember] records, so a
 * member the directory no longer knows still round-trips.
 */
@Composable
fun EditEmailGroupScreen(
    name: String,
    members: List<GroupMember>,
    isNew: Boolean,
    nameError: String?,
    saving: Boolean,
    onNameChange: (String) -> Unit,
    onSelectMembers: () -> Unit,
    onRemoveMember: (GroupMember) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ZillitTheme.colors.surface,
        topBar = {
            ZillitTopBar(
                title = stringResource(
                    if (isNew) R.string.email_group_add_title else R.string.email_group_edit_title,
                ),
                onBackClick = onBack,
                onHelpClick = null,
                actions = {
                    Text(
                        text = stringResource(R.string.save),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (saving) {
                            ZillitTheme.colors.textTertiary
                        } else {
                            ZillitTheme.colors.brand
                        },
                        modifier = Modifier
                            .clickable(enabled = !saving, onClick = onSave)
                            .padding(ZillitTheme.spacing.sm),
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            FormTextField(
                value = name,
                onValueChange = onNameChange,
                label = stringResource(R.string.email_group_name_hint),
                placeholder = stringResource(R.string.email_group_name_hint),
                errorText = nameError,
                // The address is built from the name, so an existing group's name is fixed.
                enabled = isNew,
                modifier = Modifier.padding(ZillitTheme.spacing.lg),
            )

            SecondaryButton(
                text = stringResource(R.string.email_group_select_members),
                onClick = onSelectMembers,
                modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg),
            )

            if (members.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.email_group_no_members),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textTertiary,
                        modifier = Modifier.padding(ZillitTheme.spacing.xxl),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.email_group_members),
                    style = MaterialTheme.typography.labelMedium,
                    color = ZillitTheme.colors.textTertiary,
                    modifier = Modifier.padding(
                        start = ZillitTheme.spacing.lg,
                        top = ZillitTheme.spacing.lg,
                        bottom = ZillitTheme.spacing.xs,
                    ),
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(members, key = { it.userId }) { member ->
                        MemberRow(member = member, onRemove = { onRemoveMember(member) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(member: GroupMember, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        UserAvatar(
            initials = member.initials,
            pictureKey = member.pictureKey,
            thumbnailKey = member.thumbnailKey,
            size = 36.dp,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.name,
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Designation above address: on a production two people share a first name
                // far more often than they share a job.
                text = listOf(member.designation, member.email)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = stringResource(R.string.action_remove),
            tint = ZillitTheme.colors.danger,
            modifier = Modifier
                .size(22.dp)
                .clickable(onClick = onRemove),
        )
    }
}
