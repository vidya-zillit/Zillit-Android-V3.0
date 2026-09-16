package com.zillit.zillitapp.feature.email.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import com.zillit.zillitapp.feature.email.domain.EmailFolder
import com.zillit.zillitapp.feature.email.ui.drawer.FolderDrawer
import com.zillit.zillitapp.feature.email.ui.drawer.FolderDrawerState
import kotlinx.coroutines.launch

/**
 * The email shell — v2's `new_email_landing_page`.
 *
 * The folder drawer and the folder currently being read. v2 makes this a `DrawerLayout`
 * hosting its own nested `NavHostFragment` with a one-destination graph, and changing
 * folder means popping that destination and re-navigating to it with a different argument.
 * Here changing folder is a state change, so the list keeps its scroll position and its
 * selection across a drawer open and close.
 *
 * The drawer slides over the content on every window size. A wide window splits the
 * content itself into the list and the mail being read — see `EmailRoute` — but the folders
 * stay behind the hamburger, so the two panes keep their room.
 *
 * The content is a slot rather than the list itself: the folder picker and search both need
 * to appear over this shell, and a slot keeps the shell from knowing about either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailLandingScreen(
    drawerState: FolderDrawerState,
    onFolderClick: (EmailFolder) -> Unit,
    onFolderMore: (EmailFolder) -> Unit,
    onCreateFolder: () -> Unit,
    onSwitchMailbox: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun close() = scope.launch { drawer.close() }

    // Back closes the drawer before it leaves the screen — without this, opening the drawer
    // and pressing back drops the user out of email entirely.
    BackHandler(enabled = drawer.isOpen) { close() }

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = ZillitTheme.colors.surface,
                // v2's 300dp, kept: wide enough for a qualified folder name, narrow enough
                // that the list behind stays visible and tappable to dismiss.
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                FolderDrawer(
                    state = drawerState,
                    onFolderClick = { folder ->
                        close()
                        onFolderClick(folder)
                    },
                    onFolderMore = onFolderMore,
                    onCreateFolder = onCreateFolder,
                    onSwitchMailbox = onSwitchMailbox,
                    onOpenCalendar = {
                        close()
                        onOpenCalendar()
                    },
                    onOpenContacts = {
                        close()
                        onOpenContacts()
                    },
                    onOpenSettings = {
                        close()
                        onOpenSettings()
                    },
                )
            }
        },
    ) {
        content { scope.launch { drawer.open() } }
    }
}
