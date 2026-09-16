package com.zillit.zillitapp.feature.cnc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.cnc.ui.list.CallFilter
import com.zillit.zillitapp.feature.cnc.ui.list.CallRow
import com.zillit.zillitapp.feature.cnc.ui.list.CallTabScreen
import com.zillit.zillitapp.feature.cnc.ui.list.ChatFilter
import com.zillit.zillitapp.feature.cnc.ui.list.ChatTabScreen
import com.zillit.zillitapp.feature.cnc.ui.list.CncTab
import com.zillit.zillitapp.feature.cnc.ui.list.CncTabs
import com.zillit.zillitapp.feature.cnc.ui.list.CncUiState
import com.zillit.zillitapp.feature.cnc.ui.list.ContactRow
import com.zillit.zillitapp.feature.cnc.ui.list.ContactsTabScreen
import com.zillit.zillitapp.feature.cnc.ui.list.ConversationRow

/**
 * The C&C tab: Chat, Call and Contacts.
 *
 * Three destinations that never change, so they are a segmented control rather than a pager
 * — and the search text is deliberately **not** shared between them, because "Sahil" in
 * Contacts and "Sahil" in the call log are different questions.
 */
@Composable
fun CncScreen(
    state: CncUiState,
    onTab: (CncTab) -> Unit,
    onChatFilter: (ChatFilter) -> Unit,
    onCallFilter: (CallFilter) -> Unit,
    onQuery: (String) -> Unit,
    onOpenConversation: (ConversationRow) -> Unit,
    onToggleConversationFavourite: (ConversationRow) -> Unit,
    onCreateGroup: () -> Unit,
    onClearCallLog: () -> Unit,
    onJoinViaLink: () -> Unit,
    onCallDetails: (CallRow) -> Unit,
    onCallBack: (CallRow) -> Unit,
    onNewCall: () -> Unit,
    onLoadMoreCalls: () -> Unit,
    onOpenProfile: (ContactRow) -> Unit,
    onMessageContact: (ContactRow) -> Unit,
    onAudioCallContact: (ContactRow) -> Unit,
    onVideoCallContact: (ContactRow) -> Unit,
    onShareLocation: (ContactRow) -> Unit,
    onToggleContactFavourite: (ContactRow) -> Unit,
    modifier: Modifier = Modifier,
    /** Long press on a group row. */
    onConversationActions: (ConversationRow) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.background),
    ) {
        CncTabs(
            selected = state.tab,
            onSelect = onTab,
            labels = { tab ->
                stringResource(
                    when (tab) {
                        CncTab.CHAT -> R.string.cnc_tab_chat
                        CncTab.CALL -> R.string.cnc_tab_call
                        CncTab.CONTACTS -> R.string.cnc_tab_contacts
                    },
                )
            },
            modifier = Modifier.padding(vertical = ZillitTheme.spacing.sm),
        )

        when (state.tab) {
            CncTab.CHAT -> ChatTabScreen(
                state = state,
                onFilter = onChatFilter,
                onQuery = onQuery,
                onOpen = onOpenConversation,
                onToggleFavourite = onToggleConversationFavourite,
                onCreateGroup = onCreateGroup,
                onRowActions = onConversationActions,
            )

            CncTab.CALL -> CallTabScreen(
                state = state,
                onFilter = onCallFilter,
                onQuery = onQuery,
                onClearLog = onClearCallLog,
                onJoinViaLink = onJoinViaLink,
                onCallDetails = onCallDetails,
                onCallBack = onCallBack,
                onNewCall = onNewCall,
                onLoadMore = onLoadMoreCalls,
            )

            CncTab.CONTACTS -> ContactsTabScreen(
                state = state,
                onQuery = onQuery,
                onOpenProfile = onOpenProfile,
                onMessage = onMessageContact,
                onAudioCall = onAudioCallContact,
                onVideoCall = onVideoCallContact,
                onShareLocation = onShareLocation,
                onToggleFavourite = onToggleContactFavourite,
            )
        }
    }
}
