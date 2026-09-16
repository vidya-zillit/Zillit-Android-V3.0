package com.zillit.zillitapp.feature.cnc.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.components.UserAvatar
import com.zillit.zillitapp.core.ui.components.ZillitTopBar
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.core.labels.asLabelIfKey
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.fillMaxWidth

/** A person, as their profile screen shows them. */
@Immutable
data class PersonProfile(
    val id: String,
    val name: String,
    val designation: String,
    val department: String,
    val initials: String,
    val pictureKey: String? = null,
    val thumbnailKey: String? = null,
    val online: Boolean = false,
    val mediaCount: Int = 0,
    val fileCount: Int = 0,
    val blocked: Boolean = false,
    /**
     * Whether to offer "where are they".
     *
     * v2 shows the control only when the person shares their position and the viewer is a
     * full member of the project. Hiding it rather than disabling it is the point: the
     * control's presence is the consent signal.
     */
    val canSeeLocation: Boolean = false,
)

/**
 * One person.
 *
 * v2's version is the header and nothing else — two thirds of the screen is empty. The
 * space below now carries what you would otherwise leave the screen to find.
 */
@Composable
fun PersonProfileScreen(
    profile: PersonProfile,
    onBack: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onShareLocation: () -> Unit,
    onMessage: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenFiles: () -> Unit,
    onToggleBlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background),
    ) {
        ZillitTopBar(title = "", onBackClick = onBack, onHelpClick = null)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ZillitTheme.spacing.xl),
        ) {
            item {
                ProfileHeader(
                    initials = profile.initials,
                    title = profile.name,
                    subtitles = listOf(
                        profile.designation.asLabelIfKey(),
                        profile.department.asLabelIfKey(),
                    ),
                    pictureKey = profile.pictureKey,
                    thumbnailKey = profile.thumbnailKey,
                    online = profile.online,
                    actions = listOf(
                        ProfileAction(Icons.Outlined.Call, stringResource(R.string.cnc_action_audio), onAudioCall),
                        ProfileAction(Icons.Outlined.Videocam, stringResource(R.string.cnc_action_video), onVideoCall),
                        *(
                            if (profile.canSeeLocation) {
                                arrayOf(
                                    ProfileAction(
                                        Icons.Outlined.Place,
                                        stringResource(R.string.cnc_action_location),
                                        onShareLocation,
                                    ),
                                )
                            } else {
                                emptyArray()
                            }
                            ),
                        ProfileAction(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.cnc_action_chat), onMessage),
                    ),
                )
            }

            item { SectionLabel(stringResource(R.string.cnc_shared)) }
            item {
                DetailRow(
                    icon = Icons.Outlined.Image,
                    label = stringResource(R.string.cnc_media),
                    value = stringResource(R.string.cnc_item_count, profile.mediaCount),
                    onClick = onOpenMedia,
                )
                HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)
                DetailRow(
                    icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
                    label = stringResource(R.string.cnc_files),
                    value = stringResource(R.string.cnc_file_count, profile.fileCount),
                    onClick = onOpenFiles,
                )
            }

            // No Mute row. v2 has the field and every line that would use it is commented
            // out, so there is no endpoint behind it: a switch here would flip a local
            // boolean and change nothing about what arrives.
            item { SectionLabel(stringResource(R.string.cnc_conversation)) }
            item {
                DetailRow(
                    icon = Icons.Outlined.Block,
                    label = stringResource(
                        if (profile.blocked) R.string.cnc_unblock else R.string.cnc_block,
                        profile.name.substringBefore(' '),
                    ),
                    destructive = true,
                    onClick = onToggleBlock,
                )
            }
        }
    }
}

/** A member of a group, as the info screen lists them. */
@Immutable
data class GroupMemberRow(
    val id: String,
    val name: String,
    val designation: String,
    val initials: String,
    val pictureKey: String? = null,
    val thumbnailKey: String? = null,
    val isGroupAdmin: Boolean = false,
)

@Immutable
data class GroupProfile(
    val id: String,
    val name: String,
    val initials: String,
    val pictureKey: String? = null,
    val thumbnailKey: String? = null,
    val members: List<GroupMemberRow> = emptyList(),
    /** Whether this user may rename the group, change its photo, or add and remove people. */
    val canAdminister: Boolean = false,
    /** The owner's alone: removes the room for everybody, not just for you. */
    val canDelete: Boolean = false,
    val mediaCount: Int = 0,
    val fileCount: Int = 0,
)

/**
 * One group.
 *
 * The camera badge and the add/remove controls appear only for an admin, so the screen
 * shows what you may do rather than offering everything and refusing on tap.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GroupInfoScreen(
    group: GroupProfile,
    onBack: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onMessage: () -> Unit,
    onChangePhoto: () -> Unit,
    onMember: (GroupMemberRow) -> Unit,
    onAddMembers: () -> Unit,
    onLeave: () -> Unit,
    onOpenMedia: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onDelete: () -> Unit = {},
    /** Admins only; the screen hides the control rather than refusing the tap. */
    onRename: (String) -> Unit = {},
    onSetMemberAdmin: (GroupMemberRow, Boolean) -> Unit = { _, _ -> },
    onRemoveMember: (GroupMemberRow) -> Unit = {},
    /** This user, so the member menu is never offered on yourself. */
    currentUserId: String = "",
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf(false) }
    var actingOn by remember { mutableStateOf<GroupMemberRow?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background),
    ) {
        ZillitTopBar(title = "", onBackClick = onBack, onHelpClick = null)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ZillitTheme.spacing.xl),
        ) {
            item {
                ProfileHeader(
                    initials = group.initials,
                    title = group.name,
                    subtitles = listOf(
                        stringResource(R.string.cnc_group) + " · " +
                            stringResource(R.string.cnc_members_count, group.members.size),
                    ),
                    pictureKey = group.pictureKey,
                    thumbnailKey = group.thumbnailKey,
                    onChangePhoto = onChangePhoto.takeIf { group.canAdminister },
                    onEditTitle = { renaming = true }.takeIf { group.canAdminister },
                    actions = listOf(
                        ProfileAction(Icons.Outlined.Call, stringResource(R.string.cnc_action_audio), onAudioCall),
                        ProfileAction(Icons.Outlined.Videocam, stringResource(R.string.cnc_action_video), onVideoCall),
                        ProfileAction(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.cnc_action_chat), onMessage),
                    ),
                )
            }

            // Above the member list on purpose: a large group pushes anything below it off
            // the screen, and what was shared is looked for more often than the roster.
            item { SectionLabel(stringResource(R.string.cnc_shared)) }
            item {
                DetailRow(
                    icon = Icons.Outlined.Image,
                    label = stringResource(R.string.cnc_media),
                    value = stringResource(R.string.cnc_item_count, group.mediaCount),
                    onClick = onOpenMedia,
                )
                HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)
                DetailRow(
                    icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
                    label = stringResource(R.string.cnc_files),
                    value = stringResource(R.string.cnc_file_count, group.fileCount),
                    onClick = onOpenFiles,
                )
            }

            item { SectionLabel(stringResource(R.string.cnc_members_count, group.members.size)) }
            items(group.members, key = { it.id }) { member ->
                DetailRow(
                    icon = null,
                    label = member.name,
                    value = member.designation.asLabelIfKey().takeIf { !member.isGroupAdmin },
                    onClick = { onMember(member) },
                    // The admin actions are a long press, not a row of buttons: opening a
                    // member's profile is what a tap is for and by far the commoner one,
                    // and promoting or removing somebody should take a deliberate gesture.
                    onLongClick = {
                        if (group.canAdminister && member.id != currentUserId) actingOn = member
                    },
                    leading = {
                        UserAvatar(
                            initials = member.initials,
                            pictureKey = member.pictureKey,
                            thumbnailKey = member.thumbnailKey,
                            size = 34.dp,
                        )
                    },
                    trailing = {
                        if (member.isGroupAdmin) {
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                color = ZillitTheme.colors.accentSoft,
                            ) {
                                Text(
                                    text = stringResource(R.string.cnc_group_admin),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ZillitTheme.colors.accent,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                )
                            }
                        }
                    },
                )
                HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)
            }

            if (group.canAdminister) {
                item {
                    DetailRow(
                        icon = Icons.Outlined.PersonAdd,
                        label = stringResource(R.string.cnc_add_members),
                        onClick = onAddMembers,
                    )
                }
            }

            item {
                SectionLabel("")
                DetailRow(
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    label = stringResource(R.string.cnc_leave_group),
                    destructive = true,
                    onClick = onLeave,
                )

                // Only the owner's. Leaving removes you; deleting removes the room for
                // everyone, so it is not offered to people who cannot mean it.
                if (group.canDelete) {
                    HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)
                    DetailRow(
                        icon = Icons.Outlined.DeleteOutline,
                        label = stringResource(R.string.cnc_delete_group),
                        destructive = true,
                        onClick = onDelete,
                    )
                }
            }
        }
    }

    if (renaming) {
        RenameGroupDialog(
            current = group.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                onRename(name)
            },
        )
    }

    // What an admin may do to one member. A sheet rather than inline controls: these are
    // rare, consequential actions and they should not sit one mis-tap away on every row.
    actingOn?.let { member ->
        ModalBottomSheet(
            onDismissRequest = { actingOn = null },
            containerColor = ZillitTheme.colors.surface,
        ) {
            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                SectionLabel(member.name)
                DetailRow(
                    icon = Icons.Outlined.Shield,
                    label = stringResource(
                        if (member.isGroupAdmin) {
                            R.string.cnc_remove_admin
                        } else {
                            R.string.cnc_make_admin
                        },
                    ),
                    onClick = {
                        actingOn = null
                        onSetMemberAdmin(member, !member.isGroupAdmin)
                    },
                )
                HorizontalDivider(color = ZillitTheme.colors.divider, thickness = 0.5.dp)
                DetailRow(
                    icon = Icons.Outlined.PersonRemove,
                    label = stringResource(R.string.cnc_remove_from_group),
                    destructive = true,
                    onClick = {
                        actingOn = null
                        onRemoveMember(member)
                    },
                )
            }
        }
    }
}

/**
 * Renaming a group, in a dialog rather than in place.
 *
 * Editing the title where it sits would mean the header changing size as the text does, on
 * a screen that is mostly a scrolling list. A dialog also gives Cancel somewhere to live,
 * which matters because the rename posts as soon as it is confirmed.
 */
@Composable
private fun RenameGroupDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(current) { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZillitTheme.colors.surface,
        title = { Text(stringResource(R.string.cnc_rename_group)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.cnc_group_name)) },
            )
        },
        confirmButton = {
            TextButton(
                // A group with no name renders as an empty row for everybody in it.
                enabled = text.isNotBlank() && text.trim() != current,
                onClick = { onConfirm(text) },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
