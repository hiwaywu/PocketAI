package com.pocketai.chat.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.chat.ChatViewModel
import com.pocketai.chat.PocketApp
import com.pocketai.chat.screen.ScreenCapture

/**
 * The panel that slides up over another app: shows what Pocket AI read, one-tap questions, and the answer.
 * Tap outside it (or ✕) to go back to the app; ↗ continues the chat in the full Pocket AI.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskPanel(
    vm: ChatViewModel,
    capture: ScreenCapture?,
    captureSent: Boolean,
    chatId: String?,
    lastChatId: String?,
    onContinueLast: () -> Unit,
    onSent: (String) -> Unit,
    onNewChat: () -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val speaker = (context.applicationContext as PocketApp).speaker
    val speaking by speaker.speaking.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val conv = chatId?.let { id -> state.current.takeIf { it.id == id } ?: state.conversations.firstOrNull { it.id == id } }
    val running = chatId != null && chatId in state.running
    var text by remember(capture) { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    val hasText = capture != null && capture.text.isNotBlank()

    // Messages like "Set up an API first" (the main app's bar isn't on screen here).
    LaunchedEffect(state.notice) {
        state.notice?.let {
            vm.dismissNotice()
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    fun ask(question: String) {
        val q = question.trim().ifEmpty { if (hasText && !captureSent) "Summarize this" else return }
        val id = vm.askInPanel(chatId, capture, attach = !captureSent, question = q) ?: return
        onSent(id)
        text = ""
        keyboard?.hide()
        focus.clearFocus()
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!heard.isNullOrEmpty()) ask(heard)
    }

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = noRipple, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 2.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .widthIn(max = 700.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.navigationBarsPadding().imePadding().padding(bottom = 8.dp)) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)
                )
                Row(Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Pocket AI", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                capture == null -> "Ask anything"
                                capture.kind == "selection" -> "About the text you selected in ${capture.appName}"
                                else -> "Looking at " + capture.label.replace(" screen", "")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (conv != null && conv.messages.isNotEmpty() && !running) {
                        IconButton(onClick = onNewChat) { Icon(Icons.Filled.Add, "New chat") }
                    }
                    IconButton(onClick = onOpenApp) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Continue in Pocket AI") }
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
                }

                if (hasText) {
                    AssistChip(
                        onClick = { preview = true },
                        label = {
                            Text(
                                (if (captureSent) "Sent " else "Read ") + "${formatTokens(capture!!.text.length.toLong())} characters" +
                                    if (capture.kind == "selection") " you selected" else " from the screen",
                                maxLines = 1,
                            )
                        },
                        leadingIcon = { Icon(Icons.Outlined.Description, null, Modifier.size(18.dp)) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else if (capture == null || capture.kind == "screen") {
                    Text(
                        "Couldn't read this screen (some apps, like banking apps, block it). You can still ask anything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                    if (capture?.debug != null) TextButton(onClick = { preview = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Details")
                    }
                }

                // A new page started a new chat; one tap goes back to the previous one if they're related after all.
                val last = lastChatId?.let { id -> state.conversations.firstOrNull { it.id == id } ?: state.current.takeIf { it.id == id } }
                if (last != null && (conv == null || conv.messages.isEmpty())) {
                    TextButton(onClick = onContinueLast, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("↩ Continue last chat: ${last.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                if (conv != null && conv.messages.isNotEmpty()) {
                    val listState = rememberLazyListState()
                    val msgs = conv.messages
                    val lastId = msgs.last().id
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        items(msgs.asReversed(), key = { it.id }) { m ->
                            if (m.role == "user") {
                                UserBubble(m, onImage = {}, onEdit = null, modifier = Modifier)
                            } else {
                                val isLast = m.id == lastId
                                AssistantMessage(
                                    m = m,
                                    isLast = isLast,
                                    working = isLast && running,
                                    status = state.statuses[conv.id],
                                    speaking = speaking == m.id,
                                    followUps = emptyList(),
                                    onLink = { openLink(context, it) },
                                    onRetry = vm::retry,
                                    onSpeak = { speaker.toggle(m.id, m.finalContent?.ifBlank { null } ?: m.text) },
                                    onShare = { shareText(context, m.finalContent?.ifBlank { null } ?: m.text, conv.title) },
                                    onFollowUp = {},
                                    modifier = Modifier,
                                )
                            }
                        }
                    }
                    LaunchedEffect(msgs.size) { listState.animateScrollToItem(0) }
                }

                // One-tap questions while there's a fresh screen to ask about (also when continuing a chat).
                val ideas = if (captureSent || running) emptyList() else panelSuggestions(capture)
                if (ideas.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) { ideas.forEach { q -> SuggestionChip(onClick = { ask(q) }, label = { Text(q) }) } }
                }

                Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(if (hasText && !captureSent) "Ask about this…" else "Ask a follow-up…") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    when {
                        running -> FilledIconButton(onClick = vm::stop, modifier = Modifier.padding(bottom = 4.dp)) {
                            Icon(Icons.Filled.Stop, "Stop")
                        }
                        text.isBlank() -> FilledTonalIconButton(
                            onClick = {
                                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about this")
                                try {
                                    voice.launch(i)
                                } catch (_: ActivityNotFoundException) {
                                    vm.notice("No voice typing app found on this phone")
                                }
                            },
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) { Icon(Icons.Filled.Mic, "Ask by voice") }
                        else -> FilledIconButton(onClick = { ask(text) }, modifier = Modifier.padding(bottom = 4.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    }
                }
            }
        }
    }

    if (preview && capture != null) {
        AlertDialog(
            onDismissRequest = { preview = false },
            title = { Text(if (capture.kind == "selection") "Text you selected" else "What Pocket AI read") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    (capture.url ?: capture.site)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                    }
                    SelectionContainer { Text(capture.text, style = MaterialTheme.typography.bodySmall) }
                    capture.debug?.let {
                        Spacer(Modifier.size(10.dp))
                        Text("How it went: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { preview = false }) { Text("OK") } },
            dismissButton = {
                // A technical report to paste to whoever is fixing Pocket AI (no keys or settings in it).
                if (capture.debug != null) TextButton(onClick = {
                    clipboard.setText(AnnotatedString(diagnostics(capture)))
                    Toast.makeText(context, "Details copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy details") }
            },
        )
    }
}

/** One-tap questions for what's on screen. */
private fun panelSuggestions(c: ScreenCapture?): List<String> {
    if (c == null || c.text.isBlank()) return emptyList()
    if (c.kind == "selection") return listOf("Explain this", "Summarize this", "Translate to English")
    val where = (c.packageName.orEmpty() + " " + (c.url ?: c.site).orEmpty()).lowercase()
    return when {
        "reddit" in where -> listOf("Summarize this", "What are people saying?", "Is this true?")
        "youtube" in where -> listOf("Summarize this video", "What are the key moments?")
        else -> listOf("Summarize this", "Explain this", "Is this reliable?")
    }
}

/** "Copy details": the version, the reading report and the start of what was read. */
private fun diagnostics(c: ScreenCapture): String =
    "Pocket AI ${com.pocketai.chat.BuildConfig.VERSION_NAME} · ${c.appName} (${c.packageName}) · ${c.text.length} chars read\n" +
        "${c.debug}\n--- start of text ---\n${c.text.take(600)}"
