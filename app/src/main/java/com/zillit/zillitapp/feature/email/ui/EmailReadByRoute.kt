package com.zillit.zillitapp.feature.email.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zillit.zillitapp.core.directory.ProjectDirectory
import com.zillit.zillitapp.core.network.ApiResult
import com.zillit.zillitapp.core.ui.chat.ReadByPerson
import com.zillit.zillitapp.core.ui.chat.ReadByUiState
import com.zillit.zillitapp.core.ui.chat.ReadByUserScreen
import com.zillit.zillitapp.feature.email.data.EmailApi
import com.zillit.zillitapp.feature.email.data.ReadByEntryDto
import com.zillit.zillitapp.navigation.EmailReadBy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Who has opened a mail this user sent.
 *
 * Reuses the chat module's [ReadByUserScreen] — same two sections, same rows — but not its
 * view model: mail read receipts come from the send log (`email-sent-log/read-by`), which is
 * a different endpoint from any chat's `…/readby`, and `ReadByRequest` is built around a
 * chat module and scope that mail has no equivalent of.
 *
 * Notify is not offered. There is no mail equivalent of nudging a thread, and a recipient
 * outside the project could not be notified in-app anyway.
 */
@HiltViewModel
class EmailReadByViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: EmailApi,
    private val directory: ProjectDirectory,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<EmailReadBy>()

    private val _state = MutableStateFlow(ReadByUiState(isLoading = true))
    val state: StateFlow<ReadByUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = api.readBy(route.emailId)) {
                is ApiResult.Success -> _state.value = ReadByUiState(
                    isLoading = false,
                    read = result.data.read.map { it.toPerson(read = true) },
                    unread = result.data.unread.map { it.toPerson(read = false) },
                    canNotify = false,
                )

                is ApiResult.Failure -> _state.value = ReadByUiState(isLoading = false)
            }
        }
    }

    /**
     * Names come from the local directory, not the response.
     *
     * The endpoint returns bare user ids, and the crew list is already in Realm — so a
     * recipient who is not on this project shows as their id rather than as nothing.
     */
    private fun ReadByEntryDto.toPerson(read: Boolean): ReadByPerson {
        val user = directory.findUser(userId)
        return ReadByPerson(
            userId = userId,
            name = user?.displayName ?: userId,
            role = user?.designationName,
            time = if (readAt > 0) STAMP.format(Date(readAt)) else "",
            isRead = read,
        )
    }

    private companion object {
        val STAMP = SimpleDateFormat("d MMM 'at' HH:mm", Locale.getDefault())
    }
}

@Composable
fun EmailReadByRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmailReadByViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ReadByUserScreen(
        state = state,
        onClose = onBack,
        onNotify = { },
        modifier = modifier,
    )
}
