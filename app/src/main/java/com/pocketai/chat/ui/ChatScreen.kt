package com.pocketai.chat.ui

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.pocketai.chat.ChatViewModel
import com.pocketai.chat.UiState
import com.pocketai.chat.data.ChatMessage
import com.pocketai.chat.data.ImageUtil
import com.pocketai.chat.data.ToolEvent
import com.pocketai.chat.data.hostOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    vm: ChatViewModel,
    wide: Boolean,
    browserVisible: Boolean,
    onMenu: () -> Unit,
    onSettings: () -> Unit,
    onToggleBrowser: () -> Unit,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val conv = state.current
    var viewer by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { ModelPicker(state, vm, onSettings) },
            navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Chats") } },
            actions = {
                IconButton(onClick = vm::newChat) { Icon(Icons.Filled.Add, "New chat") }
                if (wide) {
                    IconButton(onClick = onToggleBrowser) {
                        Icon(
                            Icons.Filled.Public,
                            if (browserVisible) "Hide browser" else "Show browser",
                            tint = if (browserVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
            },
        )

        val listState = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (conv.messages.isEmpty()) {
                EmptyState(needsSetup = !state.settings.isConfigured, onSettings = onSettings, onPrompt = { vm.send(it) })
            } else {
                val msgs = conv.messages
                val lastId = msgs.last().id
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(msgs.asReversed(), key = { it.id }) { m ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            MessageItem(
                                m = m,
                                isLast = m.id == lastId,
                                streaming = state.streaming,
                                status = state.status,
                                onLink = onLink,
                                onRetry = vm::retry,
                                onImage = { viewer = it },
                                modifier = Modifier.widthIn(max = 780.dp),
                            )
                        }
                    }
                }
                LaunchedEffect(msgs.size) { listState.animateScrollToItem(0) }
            }
        }
        Composer(state, vm)
    }
    viewer?.let { path -> ImageViewer(path) { viewer = null } }
}

/** Title area: shows the API + model; tap it to switch saved API or model. */
@Composable
private fun ModelPicker(state: UiState, vm: ChatViewModel, onSettings: () -> Unit) {
    val s = state.settings
    var open by remember { mutableStateOf(false) }
    val tokens = state.current.promptTokens + state.current.completionTokens
    Box {
        Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.model.ifBlank { "Choose a model" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            Text(
                s.connectionName + if (tokens > 0) " · ${formatTokens(tokens)} tokens" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 240.dp)) {
            if (s.profiles.size > 1 || (s.profiles.isNotEmpty() && !s.matchesSavedProfile())) {
                MenuHeader("Saved APIs")
                s.profiles.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name, fontWeight = if (p.id == s.activeProfileId && s.matchesSavedProfile()) FontWeight.Bold else null) },
                        onClick = { vm.selectProfile(p.id); open = false },
                    )
                }
                HorizontalDivider()
            }
            MenuHeader("Models")
            if (s.models.isEmpty()) {
                DropdownMenuItem(text = { Text("Load available models") }, onClick = { vm.refreshModels(); open = false })
            } else {
                s.models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m, fontWeight = if (m == s.model) FontWeight.Bold else null) },
                        onClick = { vm.selectModel(m); open = false },
                    )
                }
                DropdownMenuItem(text = { Text("↻ Reload model list") }, onClick = { vm.refreshModels(); open = false })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("API settings…") }, onClick = { open = false; onSettings() })
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
    else -> n.toString()
}

@Composable
private fun EmptyState(needsSetup: Boolean, onSettings: () -> Unit, onPrompt: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.size(12.dp))
        Text("What can I help with?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(20.dp))
        if (needsSetup) {
            Text(
                "First, add your API address and key, then load a model.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Button(onClick = onSettings) { Text("Set up API") }
        } else {
            listOf(
                "What were the NBA scores last night?",
                "Summarize today's top tech news",
                "Explain how foldable phone screens work",
                "Help me write a polite email to my landlord",
            ).forEach { p ->
                OutlinedCard(
                    onClick = { onPrompt(p) },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).padding(vertical = 4.dp),
                ) {
                    Text(p, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun MessageItem(
    m: ChatMessage,
    isLast: Boolean,
    streaming: Boolean,
    status: String?,
    onLink: (String) -> Unit,
    onRetry: () -> Unit,
    onImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (m.role == "user") UserBubble(m, onImage, modifier)
    else AssistantMessage(m, isLast, isLast && streaming, status, onLink, onRetry, modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserBubble(m: ChatMessage, onImage: (String) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (m.images.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                m.images.forEach { path -> Thumb(path, 110.dp, onClick = { onImage(path) }) }
            }
        }
        if (m.pageTitle != null) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(m.pageTitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (m.text.isNotBlank()) {
            Box(Modifier.fillMaxWidth(0.88f), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                ) {
                    SelectionContainer {
                        Text(
                            m.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantMessage(
    m: ChatMessage,
    isLast: Boolean,
    working: Boolean,
    status: String?,
    onLink: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.toolEvents.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { m.toolEvents.forEach { ToolEventRow(it) } }
        }
        if (m.text.isNotBlank()) {
            SelectionContainer { MarkdownText(m.text.trimEnd(), onLink) }
        }
        if (working && (m.text.isBlank() || status != null)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    status ?: "Thinking…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        m.error?.let { err ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    if (isLast) TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
        if (m.sources.isNotEmpty() && !working) {
            Text("Sources", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.sources.forEach { src ->
                    AssistChip(
                        onClick = { onLink(src.url) },
                        label = {
                            Text(
                                hostOf(src.url).ifBlank { src.title },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 180.dp),
                            )
                        },
                    )
                }
            }
        }
        if (!working && m.text.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(m.finalContent?.ifBlank { null } ?: m.text)) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "Copy", Modifier.size(18.dp))
                }
                if (isLast) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Refresh, "Regenerate", Modifier.size(18.dp))
                    }
                }
                m.model?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun ToolEventRow(e: ToolEvent) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (e.kind == "search") Icons.Outlined.Search else Icons.Outlined.Description,
                null,
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                (if (e.kind == "search") "Searched: " else "Read: ") + e.label + if (e.failed) "  (failed)" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Composer(state: UiState, vm: ChatViewModel) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.composerPrefill) {
        state.composerPrefill?.let { text = it; vm.consumePrefill() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ChatViewModel.MAX_IMAGES)) { uris ->
        if (uris.isNotEmpty()) vm.addImages(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = cameraPath
        if (ok && p != null) vm.addImages(listOf(Uri.fromFile(File(p))))
        cameraPath = null
    }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 6.dp, vertical = 6.dp)) {
            state.pendingPage?.let { p ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                ) {
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Page: " + p.title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 260.dp),
                        )
                        IconButton(onClick = vm::clearPage, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, "Remove page", Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (state.pendingImages.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    items(state.pendingImages, key = { it }) { path ->
                        Box {
                            Thumb(path, 68.dp)
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .clickable { vm.removePendingImage(path) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Close, "Remove photo", Modifier.size(14.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Outlined.Image, "Add photo")
                }
                IconButton(onClick = {
                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                    val f = File(dir, "cam_${System.currentTimeMillis()}.jpg")
                    cameraPath = f.absolutePath
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                    try {
                        camera.launch(uri)
                    } catch (_: ActivityNotFoundException) {
                        vm.notice("No camera app found")
                    }
                }) {
                    Icon(Icons.Outlined.PhotoCamera, "Take photo")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Message") },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                if (state.streaming) {
                    FilledIconButton(onClick = vm::stop, modifier = Modifier.padding(bottom = 4.dp)) {
                        Icon(Icons.Filled.Stop, "Stop")
                    }
                } else {
                    FilledIconButton(
                        onClick = { if (vm.send(text)) text = "" },
                        enabled = text.isNotBlank() || state.pendingImages.isNotEmpty() || state.pendingPage != null,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberBitmap(path: String, maxPx: Int): Bitmap? {
    val bmp by produceState<Bitmap?>(null, path, maxPx) {
        value = withContext(Dispatchers.IO) { ImageUtil.loadBitmap(path, maxPx) }
    }
    return bmp
}

@Composable
private fun Thumb(path: String, size: Dp, onClick: (() -> Unit)? = null) {
    val bmp = rememberBitmap(path, 400)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun ImageViewer(path: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val bmp = rememberBitmap(path, 2048)
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            bmp?.let { Image(it.asImageBitmap(), "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
    }
}
