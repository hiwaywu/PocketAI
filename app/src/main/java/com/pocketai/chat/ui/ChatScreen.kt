package com.pocketai.chat.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.chat.ChatViewModel
import com.pocketai.chat.PocketApp
import com.pocketai.chat.UiState
import com.pocketai.chat.api.Reddit
import com.pocketai.chat.data.ChatMessage
import com.pocketai.chat.data.Conversation
import com.pocketai.chat.data.ImageUtil
import com.pocketai.chat.data.ModelRates
import com.pocketai.chat.data.ToolEvent
import com.pocketai.chat.phone.PhoneTools
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.text.style.TextDecoration
import com.pocketai.chat.data.hostOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    vm: ChatViewModel,
    onMenu: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val conv = state.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speaker = (context.applicationContext as PocketApp).speaker
    val speaking by speaker.speaking.collectAsStateWithLifecycle()
    var viewer by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    val onLink: (String) -> Unit = { openLink(context, it) }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { ModelPicker(state, vm, onSettings) },
            navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Chats") } },
            actions = {
                IconButton(onClick = vm::newChat) { Icon(Icons.Filled.Add, "New chat") }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
            },
        )

        val listState = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (conv.messages.isEmpty()) {
                EmptyState(needsSetup = !state.settings.isConfigured, onSettings = onSettings)
            } else {
                val msgs = conv.messages
                val lastId = msgs.last().id
                val suggestions = remember(conv.id, msgs.size) { followUps(conv) }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(msgs.asReversed(), key = { it.id }) { m ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            val isLast = m.id == lastId
                            if (m.role == "user") {
                                UserBubble(
                                    m,
                                    onImage = { viewer = it },
                                    onEdit = { if (state.streaming) vm.notice("Wait for the reply to finish (or stop it) first.") else editing = m },
                                    modifier = Modifier.widthIn(max = 780.dp),
                                )
                            } else {
                                AssistantMessage(
                                    m = m,
                                    isLast = isLast,
                                    working = isLast && state.streaming,
                                    status = state.status,
                                    speaking = speaking == m.id,
                                    followUps = if (isLast && !state.streaming && m.error == null) suggestions else emptyList(),
                                    onLink = onLink,
                                    onRetry = vm::retry,
                                    onSpeak = { speaker.toggle(m.id, m.finalContent?.ifBlank { null } ?: m.text) },
                                    onShare = { shareText(context, m.finalContent?.ifBlank { null } ?: m.text, conv.title) },
                                    onFollowUp = { vm.send(it) },
                                    modifier = Modifier.widthIn(max = 780.dp),
                                    onUndo = { vm.undoPhoneAction(conv.id, m.id, it) },
                                )
                            }
                        }
                    }
                }
                LaunchedEffect(msgs.size) { listState.animateScrollToItem(0) }
                // Jump back to the newest message after scrolling up through a long chat.
                val scrolledUp by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 600 } }
                if (scrolledUp) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) { Icon(Icons.Filled.KeyboardArrowDown, "Scroll to the newest message") }
                }
            }
        }
        Composer(state, vm)
    }
    viewer?.let { path -> ImageViewer(path) { viewer = null } }
    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit message") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth())
                    Text(
                        "The AI answers again from here; later messages in this chat are replaced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { if (vm.editAndResend(m.id, text)) editing = null }, enabled = text.isNotBlank() || m.images.isNotEmpty()) {
                    Text("Send")
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

/**
 * One-tap follow-ups after a link summary. Free (no extra tokens until tapped) and shown only under
 * that first summary, so ordinary chats stay clean.
 */
private fun followUps(c: Conversation): List<String> {
    val users = c.messages.filter { it.role == "user" }
    if (users.size != 1) return emptyList()
    val url = Regex("""https?://\S+""").find(users[0].text)?.value ?: return emptyList()
    return when {
        Reddit.isReddit(url) -> listOf("What else are people saying?", "What's the pushback?", "Is this true?")
        Regex("""youtube\.com|youtu\.be""", RegexOption.IGNORE_CASE).containsMatchIn(url) -> listOf("More detail", "Is this accurate?")
        else -> listOf("More detail", "Key quotes", "Is this reliable?")
    }
}

/** Title area: shows the API + model; tap it to switch saved API or model. */
@Composable
private fun ModelPicker(state: UiState, vm: ChatViewModel, onSettings: () -> Unit) {
    val s = state.settings
    var open by remember { mutableStateOf(false) }
    val tokens = state.current.messages.sumOf { it.usage?.billed ?: 0L }
        .takeIf { it > 0 } ?: (state.current.promptTokens + state.current.completionTokens)
    val billedChat = state.current.messages.any { it.usage?.multiplier != null }
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
                s.connectionName +
                    (ModelRates.multiplier(s.baseUrl, s.model)?.let { " · ${ModelRates.label(it)}" } ?: "") +
                    (if (tokens > 0) " · ${formatTokens(tokens)} ${if (billedChat) "billed" else "tokens"}" else ""),
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
                    val rate = ModelRates.multiplier(s.baseUrl, m)
                    DropdownMenuItem(
                        text = { Text(m, fontWeight = if (m == s.model) FontWeight.Bold else null) },
                        trailingIcon = rate?.let { r ->
                            { Text(ModelRates.label(r), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        },
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
private fun EmptyState(needsSetup: Boolean, onSettings: () -> Unit) {
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
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun UserBubble(m: ChatMessage, onImage: (String) -> Unit, onEdit: (() -> Unit)?, modifier: Modifier) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
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
                Box {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                        modifier = Modifier.clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).combinedClickable(
                            onClick = {},
                            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                        ),
                    ) {
                        Text(
                            m.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = { clipboard.setText(AnnotatedString(m.text)); menu = false },
                        )
                        if (onEdit != null) DropdownMenuItem(
                            text = { Text("Edit and resend") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; onEdit() },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantMessage(
    m: ChatMessage,
    isLast: Boolean,
    working: Boolean,
    status: String?,
    speaking: Boolean,
    followUps: List<String>,
    onLink: (String) -> Unit,
    onRetry: () -> Unit,
    onSpeak: () -> Unit,
    onShare: () -> Unit,
    onFollowUp: (String) -> Unit,
    modifier: Modifier,
    onUndo: (ToolEvent) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    var showUsage by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.toolEvents.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { m.toolEvents.forEach { ToolEventRow(it, onUndo) } }
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
            FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                val reply = m.finalContent?.ifBlank { null } ?: m.text
                IconButton(onClick = { clipboard.setText(AnnotatedString(reply)) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "Copy", Modifier.size(18.dp))
                }
                IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Share, "Share", Modifier.size(18.dp))
                }
                IconButton(onClick = onSpeak, modifier = Modifier.size(36.dp)) {
                    if (speaking) Icon(Icons.Filled.StopCircle, "Stop reading", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    else Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Read aloud", Modifier.size(18.dp))
                }
                if (isLast) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Refresh, "Regenerate", Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(4.dp))
                m.model?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                m.usage?.let { u ->
                    Text(
                        " · ${if (u.estimated) "≈" else ""}${formatTokens(u.total)} tokens" +
                            (u.multiplier?.let { " ${ModelRates.label(it)} = ${formatTokens(u.billed)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showUsage = true }.padding(4.dp),
                    )
                }
            }
            if (showUsage) m.usage?.let { UsageDialog(it) { showUsage = false } }
        }
        if (followUps.isNotEmpty() && !working) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                followUps.forEach { q ->
                    SuggestionChip(onClick = { onFollowUp(q) }, label = { Text(q) })
                }
            }
        }
    }
}

@Composable
private fun ToolEventRow(e: ToolEvent, onUndo: (ToolEvent) -> Unit) {
    if (e.kind in PHONE_KINDS) return PhoneActionCard(e, onUndo)
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
    // After sending, the keyboard goes down so the reply has the whole screen.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
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
    // Voice typing with the phone's speech recognizer; the words land in the box so they can be fixed before sending.
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!heard.isNullOrEmpty()) text = if (text.isBlank()) heard else text.trimEnd() + " " + heard
    }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 6.dp, vertical = 6.dp)) {
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
                when {
                    state.streaming -> FilledIconButton(onClick = vm::stop, modifier = Modifier.padding(bottom = 4.dp)) {
                        Icon(Icons.Filled.Stop, "Stop")
                    }
                    text.isBlank() && state.pendingImages.isEmpty() -> FilledTonalIconButton(
                        onClick = {
                            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
                            try {
                                voice.launch(i)
                            } catch (_: ActivityNotFoundException) {
                                vm.notice("No voice typing app found on this phone")
                            }
                        },
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) { Icon(Icons.Filled.Mic, "Voice typing") }
                    else -> FilledIconButton(
                        onClick = {
                            if (vm.send(text)) {
                                text = ""
                                keyboard?.hide()
                                focus.clearFocus()
                            }
                        },
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

private val PHONE_KINDS = setOf("alarm", "alarm_off", "timer", "calendar", "calendar_deleted", "agenda")

/** A phone action the AI took (2.20): tap opens it in Clock/Calendar; calendar events have Undo. */
@Composable
private fun PhoneActionCard(e: ToolEvent, onUndo: (ToolEvent) -> Unit) {
    val context = LocalContext.current
    val open = PhoneTools.openIntent(e)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = open != null && !e.undone) {
            open?.let { runCatching { context.startActivity(it) } }
        },
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (e.kind) {
                    "alarm" -> Icons.Outlined.Alarm
                    "alarm_off" -> Icons.Outlined.AlarmOff
                    "calendar_deleted" -> Icons.Outlined.EventBusy
                    "timer" -> Icons.Outlined.Timer
                    else -> Icons.Outlined.Event
                },
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when (e.kind) {
                    "calendar" -> (if (e.undone) "Removed: " else "Added: ") + e.label
                    "calendar_deleted" -> (if (e.undone) "Put back: " else "Deleted: ") + e.label
                    "agenda" -> "Checked calendar: " + e.label
                    else -> e.label
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                textDecoration = if (e.undone && e.kind == "calendar") TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 6.dp),
            )
            if (!e.undone && ((e.kind == "calendar" && e.ref != null) || (e.kind == "calendar_deleted" && e.data != null))) {
                TextButton(onClick = { onUndo(e) }) { Text("Undo") }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}
