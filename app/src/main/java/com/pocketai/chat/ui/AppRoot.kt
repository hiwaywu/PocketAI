package com.pocketai.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.chat.ChatViewModel
import com.pocketai.chat.UiState
import kotlinx.coroutines.launch

private enum class Tab { CHAT, BROWSER }

/**
 * Folded (cover screen): Chat and Browser are tabs.
 * Unfolded (inner screen, ≥600dp wide): Chat and Browser side by side.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppRoot(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val browser = remember { BrowserController(context) }
    DisposableEffect(Unit) { onDispose { browser.destroy() } }

    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    var showSettings by rememberSaveable { mutableStateOf(!state.settings.isConfigured && state.settings.profiles.isEmpty()) }
    var browserPane by rememberSaveable { mutableStateOf(true) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val wideNow by rememberUpdatedState(wide)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.openUrl.collect { url ->
            browser.openFromChat(url)
            if (wideNow) browserPane = true else tab = Tab.BROWSER
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            vm.dismissNotice()
            snackbar.showSnackbar(it)
        }
    }
    // A share from another app always lands on the chat.
    LaunchedEffect(state.current.id) { if (!wideNow && state.current.messages.isNotEmpty()) tab = Tab.CHAT }

    // The browser's message bar. The page text is read asynchronously, so report "sent" right away
    // unless it obviously can't be sent; problems show up as a notice.
    val askAi: (String) -> Boolean = ask@{ question ->
        if (state.streaming) { vm.notice("Wait for the current reply to finish (or stop it) first."); return@ask false }
        if (browser.url.isBlank()) {
            val ok = vm.askFromBrowser("", "", null, question)
            if (ok && !wideNow) tab = Tab.CHAT
            return@ask ok
        }
        browser.extractPage { text ->
            if (vm.askFromBrowser(browser.title, browser.url, text, question) && !wideNow) tab = Tab.CHAT
        }
        true
    }

    if (showSettings) {
        SettingsScreen(vm, onClose = { showSettings = false })
        return
    }
    if (showUsage) {
        UsageScreen(vm, onOpenChat = { vm.openChat(it); tab = Tab.CHAT; showUsage = false }, onClose = { showUsage = false })
        return
    }

    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(!wide && tab == Tab.BROWSER && !browser.canGoBack) { tab = Tab.CHAT }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ChatDrawer(
                state = state,
                onNew = { vm.newChat(); tab = Tab.CHAT; scope.launch { drawerState.close() } },
                onOpen = { vm.openChat(it); tab = Tab.CHAT; scope.launch { drawerState.close() } },
                onDelete = vm::deleteChat,
                onSettings = { showSettings = true; scope.launch { drawerState.close() } },
                onUsage = { showUsage = true; scope.launch { drawerState.close() } },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            val chat: @Composable (Modifier) -> Unit = { m ->
                ChatScreen(
                    state = state,
                    vm = vm,
                    wide = wide,
                    browserVisible = browserPane,
                    onMenu = { scope.launch { drawerState.open() } },
                    onSettings = { showSettings = true },
                    onToggleBrowser = { browserPane = !browserPane },
                    onLink = { vm.openInBrowser(it) },
                    modifier = m,
                )
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    chat(Modifier.weight(1f))
                    if (browserPane) {
                        VerticalDivider()
                        BrowserPane(browser, busy = state.streaming, onAsk = askAi, onStop = vm::stop, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                val imeVisible = WindowInsets.isImeVisible
                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        if (!imeVisible) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = tab == Tab.CHAT,
                                    onClick = { tab = Tab.CHAT },
                                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, null) },
                                    label = { Text("Chat") },
                                )
                                NavigationBarItem(
                                    selected = tab == Tab.BROWSER,
                                    onClick = { tab = Tab.BROWSER },
                                    icon = { Icon(Icons.Filled.Public, null) },
                                    label = { Text("Browser") },
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
                        when (tab) {
                            Tab.CHAT -> chat(Modifier.fillMaxSize())
                            Tab.BROWSER -> BrowserPane(browser, busy = state.streaming, onAsk = askAi, onStop = vm::stop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 76.dp),
            )
        }
    }
}

@Composable
private fun ChatDrawer(
    state: UiState,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
    onUsage: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
        Text("Pocket AI", style = MaterialTheme.typography.titleLarge, modifier = Modifier.statusBarsPadding().padding(16.dp))
        NavigationDrawerItem(
            label = { Text("New chat") },
            icon = { Icon(Icons.Filled.Add, null) },
            selected = false,
            onClick = onNew,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (state.conversations.isEmpty()) {
                item {
                    Text(
                        "No saved chats yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(state.conversations, key = { it.id }) { c ->
                NavigationDrawerItem(
                    label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = c.id == state.current.id,
                    onClick = { onOpen(c.id) },
                    icon = if (c.id in state.running) {
                        { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                    } else null,
                    badge = {
                        IconButton(onClick = { confirmDelete = c.id }) { Icon(Icons.Outlined.Delete, "Delete chat") }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        HorizontalDivider()
        NavigationDrawerItem(
            label = { Text("Token usage") },
            icon = { Icon(Icons.Filled.BarChart, null) },
            selected = false,
            onClick = onUsage,
            modifier = Modifier.padding(horizontal = 12.dp).padding(top = 8.dp),
        )
        NavigationDrawerItem(
            label = { Text("Settings") },
            icon = { Icon(Icons.Filled.Settings, null) },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.navigationBarsPadding().padding(12.dp),
        )
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this chat?") },
            text = { Text("It will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { onDelete(id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
