package com.zillit.zillitapp.feature.calendar.ui.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.directory.ExternalUser
import com.zillit.zillitapp.core.directory.ProjectDepartment
import com.zillit.zillitapp.core.directory.ProjectDesignation
import com.zillit.zillitapp.core.preset.CountryCode
import com.zillit.zillitapp.core.ui.components.PrimaryButton
import com.zillit.zillitapp.core.ui.components.SecondaryButton
import com.zillit.zillitapp.core.ui.externaluser.ExternalUserFormSheet
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import kotlinx.coroutines.launch
import com.zillit.zillitapp.core.ui.components.SearchField
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * Inviting people from outside the project.
 *
 * Search-first rather than type-an-address-first: external guests are re-invited far more
 * often than they are added, and making someone retype a client's address every shoot is
 * how addresses get typed wrong. Anyone already known is one tap.
 *
 * When the search matches nobody, the screen offers to add them — which is the moment the
 * user has proved the person is new.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalGuestScreen(
    known: List<ExternalUser>,
    selected: List<String>,
    departments: List<ProjectDepartment>,
    countries: List<CountryCode>,
    designationsFor: (String) -> List<ProjectDesignation>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onSaveNew: suspend (ExternalUser) -> ExternalUser? = { null },
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable(
        saver = listSaver<MutableState<Set<String>>, String>(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) },
        ),
    ) { mutableStateOf(selected.toSet()) }
    var addingNew by rememberSaveable { mutableStateOf(false) }
    // Kept here rather than inside the sheet: a failed save has to leave the sheet open with
    // what was typed still in it.
    var saving by rememberSaveable { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val matches = remember(known, query) {
        if (query.isBlank()) {
            known
        } else {
            known.filter {
                it.fullName.contains(query, ignoreCase = true) ||
                    it.email.contains(query, ignoreCase = true)
            }
        }
    }

    // Selected addresses that are not in the known list — added in this session, or
    // belonging to someone since removed. They still have to be listed, or the user
    // cannot see or remove what they have chosen.
    val extras = remember(known, selection) {
        selection.filterNot { address -> known.any { it.email.equals(address, true) } }
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
                text = stringResource(R.string.calendar_add_external_guests),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.padding(bottom = ZillitTheme.spacing.sm),
            )

            SearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.calendar_search_external),
                keyboardType = KeyboardType.Email,
            )

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(contentPadding = PaddingValues(vertical = ZillitTheme.spacing.sm)) {
                    items(matches, key = { it.id.ifBlank { it.email } }) { user ->
                        GuestRow(
                            title = user.displayName,
                            subtitle = user.email.takeIf { it != user.displayName },
                            checked = selection.any { it.equals(user.email, true) },
                            onToggle = {
                                selection = if (selection.any { it.equals(user.email, true) }) {
                                    selection.filterNot { it.equals(user.email, true) }.toSet()
                                } else {
                                    selection + user.email
                                }
                            },
                        )
                    }

                    if (extras.isNotEmpty()) {
                        items(extras, key = { it }) { address ->
                            GuestRow(
                                title = address,
                                subtitle = null,
                                checked = true,
                                onToggle = { selection = selection - address },
                            )
                        }
                    }

                    // Nothing matched, so the person is new — say so and offer the step
                    // that follows, rather than an empty list with no way forward.
                    if (matches.isEmpty() && query.isNotBlank()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = ZillitTheme.spacing.lg),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                            ) {
                                Text(
                                    text = stringResource(R.string.calendar_no_external_match),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ZillitTheme.colors.textSecondary,
                                )
                                SecondaryButton(
                                    text = stringResource(R.string.add_external_user),
                                    onClick = { addingNew = true },
                                    leadingIcon = Icons.Outlined.PersonAdd,
                                )
                            }
                        }
                    }
                }
            }

            PrimaryButton(
                text = stringResource(R.string.calendar_done_count, selection.size),
                onClick = { onConfirm(selection.toList()) },
                modifier = Modifier.padding(vertical = ZillitTheme.spacing.md),
            )
        }
    }

    if (addingNew) {
        ExternalUserFormSheet(
            departments = departments,
            countries = countries,
            designationsFor = designationsFor,
            // Whatever they typed is almost certainly the address they were looking for.
            initialEmail = query.takeIf { it.contains('@') }.orEmpty(),
            initialName = query.takeUnless { it.contains('@') }.orEmpty(),
            saving = saving,
            onSave = { user ->
                saving = true
                scope.launch {
                    val saved = onSaveNew(user)
                    saving = false
                    if (saved != null) {
                        selection = selection + saved.email
                        query = ""
                        addingNew = false
                    }
                }
            },
            onDismiss = { addingNew = false },
        )
    }
}

@Composable
private fun GuestRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(modifier = Modifier.weight(1f).padding(start = ZillitTheme.spacing.sm)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
