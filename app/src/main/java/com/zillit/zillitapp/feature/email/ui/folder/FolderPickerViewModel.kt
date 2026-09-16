package com.zillit.zillitapp.feature.email.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zillit.zillitapp.feature.email.data.EmailRepository
import com.zillit.zillitapp.feature.email.data.toDomain
import com.zillit.zillitapp.feature.email.domain.EmailFolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The folder list, and nothing else.
 *
 * Deliberately not [com.zillit.zillitapp.feature.email.ui.list.EmailListViewModel]: the picker
 * is its own navigation destination, so `hiltViewModel()` there would build a **second** list
 * view model scoped to the picker's own back-stack entry — one with an empty selection, which
 * is how the move silently did nothing. It would also re-run that view model's `init`, kicking
 * off a folder sync and a full inbox refresh every time the picker opened.
 *
 * The picker therefore only reads folders and hands the chosen name back to whoever opened it.
 */
@HiltViewModel
class FolderPickerViewModel @Inject constructor(
    repository: EmailRepository,
) : ViewModel() {

    val folders: StateFlow<List<EmailFolder>> = repository.observeFolders()
        .map { entities -> entities.map { it.toDomain(unreadCount = 0) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
