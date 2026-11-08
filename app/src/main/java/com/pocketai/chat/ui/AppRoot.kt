package com.pocketai.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.chat.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Folded (cover screen): the chat fills the screen; the chat list slides in from the left.
 * Unfolded (inner screen, ≥600dp wide): the chat list stays open beside the chat (☰ hides it).
 */
@Composable
fun AppRoot(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(!state.settings.isConfigured && state.settings.profiles.isEmpty()) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    var sidePanel by rememberSaveable { mutableStateOf(true) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            vm.dismissNotice()
            snackbar.showSnackbar(it)
        }
    }

    if (showSettings) {
        SettingsScreen(vm, onClose = { showSettings = false })
        return
    }
    if (showUsage) {
        UsageScreen(vm, onOpenChat = { vm.openChat(it); showUsage = false }, onClose = { showUsage = false })
        return
    }

    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }

    val closeDrawer: () -> Unit = { if (!wide) scope.launch { drawerState.close() } }
    val list: @Composable () -> Unit = {
        ChatList(
            state = state,
            vm = vm,
            onNew = { vm.newChat(); closeDrawer() },
            onOpen = { vm.openChat(it); closeDrawer() },
            onSettings = { showSettings = true; closeDrawer() },
            onUsage = { showUsage = true; closeDrawer() },
        )
    }
    val chat: @Composable (Modifier) -> Unit = { m ->
        ChatScreen(
            state = state,
            vm = vm,
            onMenu = { if (wide) sidePanel = !sidePanel else scope.launch { drawerState.open() } },
            onSettings = { showSettings = true },
            modifier = m,
        )
    }

    Box(Modifier.fillMaxSize()) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                if (sidePanel) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(320.dp).fillMaxHeight()) {
                        list()
                    }
                    VerticalDivider()
                }
                chat(Modifier.weight(1f))
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = drawerState.isOpen,
                drawerContent = { ModalDrawerSheet(Modifier.widthIn(max = 330.dp)) { list() } },
            ) {
                chat(Modifier.fillMaxSize())
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 76.dp),
        )
    }
}
