package com.zillit.zillitapp.feature.calendar.ui.form

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Supplies the form with the project's people, its departments and its external guests.
 *
 * Kept apart from [EventFormRoute] so the form itself takes plain lists and stays
 * previewable and testable without the directory or a session behind it.
 */
@Composable
fun EventFormEntry(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EventFormDirectoryViewModel = hiltViewModel(),
) {
    val users by viewModel.projectUsers.collectAsStateWithLifecycle()
    val departments by viewModel.departments.collectAsStateWithLifecycle()
    val external by viewModel.external.collectAsStateWithLifecycle()
    val countries by viewModel.countries.collectAsStateWithLifecycle()

    EventFormRoute(
        onBack = onBack,
        onSaved = onSaved,
        projectUsers = users,
        departments = departments,
        externalUsers = external,
        countries = countries,
        designationsFor = viewModel::designations,
        currentUserId = viewModel.currentUserId,
        isAdmin = viewModel.isAdmin,
        onSaveExternalUser = viewModel::addExternalUser,
        modifier = modifier,
    )
}
