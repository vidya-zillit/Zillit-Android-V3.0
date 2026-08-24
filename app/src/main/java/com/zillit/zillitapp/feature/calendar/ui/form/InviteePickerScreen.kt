package com.zillit.zillitapp.feature.calendar.ui.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.directory.ProjectDepartment
import com.zillit.zillitapp.core.directory.ProjectUser
import com.zillit.zillitapp.core.labels.LocalLabels
import com.zillit.zillitapp.core.labels.asLabel
import com.zillit.zillitapp.core.labels.resolveLabel
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.theme.ZillitTheme

/**
 * Choosing who to invite.
 *
 * Three ways in, because a production invites people three ways: **everyone**, **a
 * department** ("the whole camera team"), or **named individuals**. v2 offers the same
 * three, and gates the first on being an admin — someone who cannot see the whole project
 * should not be able to invite it in one tap.
 *
 * Selection is shared across the tabs: picking a department then switching to Select Users
 * shows those people already ticked, because it is one list being built, not three.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteePickerScreen(
    users: List<ProjectUser>,
    departments: List<ProjectDepartment>,
    selectedIds: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    excludeUserIds: Set<String> = emptySet(),
    isAdmin: Boolean = false,
) {
    val labels = LocalLabels.current

    var query by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable(
        saver = listSaver<MutableState<Set<String>>, String>(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) },
        ),
    ) { mutableStateOf(selectedIds.toSet()) }

    // Admins get all three; everyone else gets the two that do not imply seeing the whole
    // project at once.
    val tabs = remember(isAdmin) {
        if (isAdmin) {
            listOf(
                R.string.calendar_tab_all_departments,
                R.string.calendar_tab_select_department,
                R.string.calendar_tab_select_users,
            )
        } else {
            listOf(R.string.calendar_tab_select_department, R.string.calendar_tab_select_users)
        }
    }
    var tabIndex by rememberSaveable { mutableStateOf(0) }

    val candidates = remember(users, excludeUserIds) {
        users.filterNot { it.userId in excludeUserIds }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZillitTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = ZillitTheme.spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.calendar_add_invitees),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm),
            )

            TabRow(
                selectedTabIndex = tabIndex,
                containerColor = ZillitTheme.colors.surface,
                contentColor = ZillitTheme.colors.brand,
            ) {
                tabs.forEachIndexed { index, labelRes ->
                    Tab(
                        selected = index == tabIndex,
                        onClick = { tabIndex = index },
                        text = {
                            // Sized to keep all three labels on one line: wrapped labels sit
                            // at different heights and the row reads as ragged.
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                        },
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.action_search)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = ZillitTheme.colors.textTertiary,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.action_close),
                                tint = ZillitTheme.colors.textTertiary,
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = ZillitTheme.spacing.sm),
            )

            val showingDepartments = when (tabs[tabIndex]) {
                R.string.calendar_tab_select_department -> true
                else -> false
            }

            val everyone = tabs[tabIndex] == R.string.calendar_tab_all_departments

            Column(modifier = Modifier.weight(1f)) {
                when {
                    everyone -> AllDepartmentsTab(
                        users = candidates.filterUsers(query, labels),
                        selection = selection,
                        onToggleAll = { ids, add ->
                            selection = if (add) selection + ids else selection - ids.toSet()
                        },
                        onToggle = { id ->
                            selection = if (id in selection) selection - id else selection + id
                        },
                    )

                    showingDepartments -> DepartmentTab(
                        departments = departments.filterDepartments(query, labels),
                        users = candidates,
                        selection = selection,
                        onToggleDepartment = { ids, add ->
                            selection = if (add) selection + ids else selection - ids.toSet()
                        },
                    )

                    else -> UserList(
                        users = candidates.filterUsers(query, labels),
                        selection = selection,
                        onToggle = { id ->
                            selection = if (id in selection) selection - id else selection + id
                        },
                    )
                }
            }

            PrimaryButton(
                text = stringResource(R.string.calendar_done_count, selection.size),
                onClick = { onConfirm(selection.toList()) },
                modifier = Modifier.padding(vertical = ZillitTheme.spacing.md),
            )
        }
    }
}

/** Everyone on the project, with a select-all over whatever the search left. */
@Composable
private fun AllDepartmentsTab(
    users: List<ProjectUser>,
    selection: Set<String>,
    onToggleAll: (List<String>, Boolean) -> Unit,
    onToggle: (String) -> Unit,
) {
    val ids = users.map { it.userId }
    val allSelected = ids.isNotEmpty() && ids.all { it in selection }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Applies to what is on screen, not the whole project: searching "camera"
                // then tapping this should invite the camera team, not everyone.
                .clickable { onToggleAll(ids, !allSelected) }
                .padding(vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = allSelected, onCheckedChange = null)
            Text(
                text = stringResource(R.string.calendar_select_all),
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.padding(start = ZillitTheme.spacing.sm),
            )
        }
        HorizontalDivider(color = ZillitTheme.colors.divider)
        UserList(users = users, selection = selection, onToggle = onToggle)
    }
}

/**
 * Departments as rows, each ticking or clearing its whole team.
 *
 * The count beside a department is how many of its people are already in — partial
 * selections are common and invisible otherwise.
 */
@Composable
private fun DepartmentTab(
    departments: List<ProjectDepartment>,
    users: List<ProjectUser>,
    selection: Set<String>,
    onToggleDepartment: (List<String>, Boolean) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = ZillitTheme.spacing.sm)) {
        items(departments, key = { it.departmentId }) { department ->
            val members = users.filter { it.departmentId == department.departmentId }
            val ids = members.map { it.userId }
            val allSelected = ids.isNotEmpty() && ids.all { it in selection }
            val someSelected = ids.any { it in selection }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleDepartment(ids, !allSelected) }
                    .padding(vertical = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = allSelected, onCheckedChange = null)
                Column(modifier = Modifier.weight(1f).padding(start = ZillitTheme.spacing.sm)) {
                    Text(
                        text = department.name.asLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (someSelected && !allSelected) {
                            stringResource(
                                R.string.calendar_department_partial,
                                ids.count { it in selection },
                                ids.size,
                            )
                        } else {
                            stringResource(R.string.calendar_department_members, ids.size)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textTertiary,
                    )
                }
            }
            HorizontalDivider(color = ZillitTheme.colors.divider)
        }
    }
}

@Composable
private fun UserList(
    users: List<ProjectUser>,
    selection: Set<String>,
    onToggle: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = ZillitTheme.spacing.sm)) {
        items(users, key = { it.userId }) { user ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(user.userId) }
                    .padding(vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = user.userId in selection, onCheckedChange = null)
                Column(modifier = Modifier.weight(1f).padding(start = ZillitTheme.spacing.sm)) {
                    Text(
                        text = user.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Resolved: the server stores designations as label keys.
                    user.designationName?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it.asLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Matches the name, the department and the designation, resolved as well as raw. */
private fun List<ProjectUser>.filterUsers(
    query: String,
    labels: Map<String, String>,
): List<ProjectUser> {
    if (query.isBlank()) return this
    return filter { user ->
        user.displayName.contains(query, ignoreCase = true) ||
            user.departmentName?.matches(query, labels) == true ||
            user.designationName?.matches(query, labels) == true
    }
}

private fun List<ProjectDepartment>.filterDepartments(
    query: String,
    labels: Map<String, String>,
): List<ProjectDepartment> {
    if (query.isBlank()) return this
    return filter { it.name.matches(query, labels) }
}

private fun String.matches(query: String, labels: Map<String, String>): Boolean =
    contains(query, ignoreCase = true) ||
        labels.resolveLabel(this).contains(query, ignoreCase = true)
