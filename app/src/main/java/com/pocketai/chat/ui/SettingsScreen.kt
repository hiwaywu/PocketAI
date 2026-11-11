package com.pocketai.chat.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.chat.screen.AskService
import com.pocketai.chat.screen.ButtonTile
import com.pocketai.chat.screen.FloatingButton
import com.pocketai.chat.ChatViewModel
import com.pocketai.chat.data.ADDRESS_SUGGESTIONS
import com.pocketai.chat.data.DEFAULT_SYSTEM_PROMPT
import com.pocketai.chat.data.SearchEngine
import com.pocketai.chat.data.hostOf
import com.pocketai.chat.data.normalizeBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * API setup modeled on NovelAI's settings: saved named APIs, an address + key,
 * "Load models" to fetch what the provider offers, and a prompt to save changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onClose: () -> Unit) {
    val start = remember { vm.state.value.settings }
    val scope = rememberCoroutineScope()

    var profileId by remember { mutableStateOf(if (start.matchesSavedProfile()) start.activeProfileId else "") }
    var baseUrl by remember { mutableStateOf(start.baseUrl) }
    var apiKey by remember { mutableStateOf(start.apiKey) }
    var model by remember { mutableStateOf(start.model) }
    var models by remember { mutableStateOf(start.models) }
    // What the connection looked like when the screen opened or a saved API was picked.
    var baseline by remember { mutableStateOf(Triple(start.baseUrl, start.apiKey, start.model)) }

    var webTools by remember { mutableStateOf(start.webTools) }
    var phoneTools by remember { mutableStateOf(start.phoneTools) }
    var engine by remember { mutableStateOf(start.searchEngine) }
    var searchKeys by remember { mutableStateOf(start.searchKeys) }
    var systemPrompt by remember { mutableStateOf(start.systemPrompt) }

    var showKey by remember { mutableStateOf(false) }
    var showSearchKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askSave by remember { mutableStateOf<Boolean?>(null) } // null = hidden; true = close after
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val live by vm.state.collectAsStateWithLifecycle()
    val profiles = live.settings.profiles
    val selected = profiles.firstOrNull { it.id == profileId }
    val changed = Triple(normalizeBaseUrl(baseUrl), apiKey.trim(), model.trim()) !=
        Triple(normalizeBaseUrl(baseline.first), baseline.second.trim(), baseline.third.trim())
    val hasConnection = baseUrl.isNotBlank() && apiKey.isNotBlank()

    fun finish() {
        vm.applyConnection(baseUrl, apiKey, model, models)
        vm.saveGeneral(systemPrompt, webTools, engine, searchKeys, phoneTools)
        onClose()
    }

    fun attemptClose() {
        if (hasConnection && changed) askSave = true else finish()
    }

    fun loadModels() {
        scope.launch {
            busy = true
            status = "Loading models…"
            status = try {
                val list = vm.loadModels(baseUrl, apiKey)
                models = list
                if (list.isEmpty()) "This provider returned no models for that key."
                else {
                    if (model !in list) model = list.first()
                    "Loaded ${list.size} models — pick one below."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "❌ ${e.message}"
            }
            busy = false
        }
    }

    BackHandler { attemptClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Settings")
                        // Shows which build is installed.
                        Text(
                            "Pocket AI ${com.pocketai.chat.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = ::attemptClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = ::attemptClose) { Text("Done") } },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .consumeWindowInsets(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.widthIn(max = 640.dp).align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("API")

                // ---- saved APIs
                Dropdown(
                    label = "Saved APIs",
                    value = selected?.name ?: if (profiles.isEmpty()) "None saved yet" else "New API (not saved)",
                    options = profiles.map { it.name } + "➕ New API",
                ) { i ->
                    if (i < profiles.size) {
                        val p = profiles[i]
                        vm.selectProfile(p.id)
                        profileId = p.id
                        baseUrl = p.baseUrl; apiKey = p.apiKey; model = p.model; models = p.models
                        baseline = Triple(p.baseUrl, p.apiKey, p.model)
                        status = "Using ${p.name}"
                    } else {
                        profileId = ""
                        baseUrl = ""; apiKey = ""; model = ""; models = emptyList()
                        status = null
                    }
                }
                if (selected != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { renaming = true }) { Text("Rename") }
                        OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                    }
                }

                // ---- address + key
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API address") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ADDRESS_SUGGESTIONS.forEach { (label, url) ->
                        SuggestionChip(onClick = { baseUrl = url; models = emptyList() }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "Hide key" else "Show key")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                // ---- models
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = ::loadModels, enabled = !busy && hasConnection) {
                        Text(if (models.isEmpty()) "Load models" else "Reload models")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                status = "Testing $model…"
                                status = vm.testConnection(baseUrl, apiKey, model)
                                busy = false
                            }
                        },
                        enabled = !busy && hasConnection && model.isNotBlank(),
                    ) { Text("Test") }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                ModelField(model = model, models = models, onChange = { model = it })
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                Button(onClick = { askSave = false }, enabled = hasConnection, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selected != null) "Save changes to “${selected.name}”…" else "Save this API…")
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                AskButtonSection()

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("Phone actions")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Let the AI set alarms, timers and calendar events")
                        Text(
                            "Asks for Calendar access the first time. Each action shows a card in the chat; calendar events can be undone there.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = phoneTools, onCheckedChange = { phoneTools = it })
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("Web tools")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Let the AI search and read the web")
                        Text(
                            "For news, scores, prices and links you share",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = webTools, onCheckedChange = { webTools = it })
                }
                Dropdown(label = "Search engine", value = engine.label, options = SearchEngine.entries.map { it.label }) { i ->
                    engine = SearchEngine.entries[i]
                }
                if (engine != SearchEngine.DUCKDUCKGO) {
                    OutlinedTextField(
                        value = searchKeys[engine.name].orEmpty(),
                        onValueChange = { searchKeys = searchKeys + (engine.name to it) },
                        label = { Text("${engine.label.substringBefore(" (")} key") },
                        supportingText = {
                            Text(
                                when (engine) {
                                    SearchEngine.DEEPSEEK ->
                                        "Key from platform.deepseek.com (about $0.75 per 1,000 searches). Leave empty to use your saved DeepSeek API's key."
                                    SearchEngine.BRAVE -> "Free key: brave.com/search/api"
                                    else -> "Free key: tavily.com"
                                }
                            )
                        },
                        singleLine = true,
                        visualTransformation = if (showSearchKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showSearchKey = !showSearchKey }) {
                                Icon(
                                    if (showSearchKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    if (showSearchKey) "Hide key" else "Show key",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("Assistant")
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("Custom instructions") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { systemPrompt = DEFAULT_SYSTEM_PROMPT }) { Text("Reset to default") }

                Text(
                    "🔒 Saved API keys are encrypted on this phone with the Android Keystore and are only sent to the API address you entered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(24.dp))
            }
        }
    }

    // ---- "Save this API?" prompt
    askSave?.let { closeAfter ->
        var name by remember(closeAfter) {
            mutableStateOf(
                selected?.name ?: ADDRESS_SUGGESTIONS.firstOrNull { normalizeBaseUrl(it.second) == normalizeBaseUrl(baseUrl) }?.first
                ?: hostOf(baseUrl)
            )
        }
        var asNew by remember(closeAfter) { mutableStateOf(selected == null) }
        AlertDialog(
            onDismissRequest = { askSave = null },
            title = { Text("Save this API setting?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${hostOf(baseUrl).ifBlank { baseUrl }} · ${model.ifBlank { "no model" }}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    if (selected != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = asNew, onCheckedChange = { asNew = it })
                            Text("Save as a new API (keep “${selected.name}”)")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.saveProfile(name, baseUrl, apiKey, model, models, asNew)
                    val saved = vm.state.value.settings
                    profileId = saved.activeProfileId
                    baseline = Triple(baseUrl, apiKey, model)
                    askSave = null
                    if (closeAfter) finish()
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askSave = null }) { Text("Cancel") }
                    if (closeAfter) TextButton(onClick = { askSave = null; finish() }) { Text("Don't save") }
                }
            },
        )
    }

    if (renaming && selected != null) {
        var name by remember { mutableStateOf(selected.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename API") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { Button(onClick = { vm.renameProfile(selected.id, name); renaming = false }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${selected.name}”?") },
            text = { Text("The saved address and key will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProfile(selected.id)
                    profileId = ""
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

/** Model box: type any model ID, or pick from the list loaded from the provider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(model: String, models: List<String>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(false) }
    val shown = if (filter && model.isNotBlank()) models.filter { it.contains(model.trim(), ignoreCase = true) } else models
    ExposedDropdownMenuBox(expanded = open && shown.isNotEmpty(), onExpandedChange = { open = it; filter = false }) {
        OutlinedTextField(
            value = model,
            onValueChange = { onChange(it); filter = true; open = true },
            label = { Text("Model") },
            placeholder = { Text("Load models, then pick one") },
            supportingText = { Text(if (models.isEmpty()) "Tap Load models to see what this API offers" else "${models.size} models available — tap to choose") },
            singleLine = true,
            trailingIcon = { if (models.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = open && shown.isNotEmpty(), onDismissRequest = { open = false }) {
            shown.forEach { id ->
                DropdownMenuItem(
                    text = { Text(id, fontWeight = if (id == model) FontWeight.Bold else null) },
                    onClick = { onChange(id); open = false; filter = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(label: String, value: String, options: List<String>, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) })
            }
        }
    }
}

/**
 * Setup for the Pocket AI button in other apps (an accessibility service). Android hides that switch for
 * apps not installed from the Play Store until "Allow restricted settings" is tapped in App info.
 */
@Composable
private fun AskButtonSection() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AskService.isOn(context)) }
    var floating by remember { mutableStateOf(FloatingButton.isShowing(context)) }
    var systemButton by remember { mutableStateOf(AskService.systemButtonAssigned(context)) }
    LifecycleResumeEffect(Unit) {
        on = AskService.isOn(context)
        floating = FloatingButton.isShowing(context)
        systemButton = AskService.systemButtonAssigned(context)
        onPauseOrDispose {}
    }
    SectionTitle("Pocket AI in other apps")
    Text(
        if (on) "✅ The Pocket AI button is on. Tap it in any app (Firefox, Reddit, YouTube…) to ask about what's on the screen."
        else "Add a Pocket AI button to every app: tap it to ask about what's on the screen. Gemini keeps the side button.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!on) {
        Text(
            "1. Tap Accessibility settings → Installed apps → Ask Pocket AI → turn it on. No shortcut needed: " +
                "Pocket AI shows its own button.\n2. If Android says \"Restricted setting\", tap App info → ⋮ → Allow restricted " +
                "settings, then try step 1 again.\nAndroid shows its standard warning for accessibility apps; Pocket AI " +
                "only reads the screen when you tap its button.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text("Accessibility settings") }
        if (!on) OutlinedButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }) { Text("App info") }
    }
    if (on) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Floating Pocket AI button")
                Text(
                    "Tap to ask · hold to tuck it into the side · drag to move",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = floating, onCheckedChange = { FloatingButton.setOn(context, it); floating = it })
        }
        if (systemButton != false) Text(
            "Still seeing Android's own accessibility button too? You don't need it any more: Accessibility settings → " +
                "Installed apps → Ask Pocket AI → turn off its shortcut (keep Ask Pocket AI itself on). Until then, " +
                "tapping Android's button switches Pocket AI off.",
            style = MaterialTheme.typography.bodySmall,
            color = if (systemButton == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            OutlinedButton(onClick = {
                runCatching {
                    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                        ComponentName(context, ButtonTile::class.java),
                        "Pocket AI button",
                        Icon.createWithResource(context, com.pocketai.chat.R.drawable.ic_notification),
                        context.mainExecutor,
                    ) {}
                }
            }) { Text("Add show/hide to Quick Settings") }
        }
    }
    Text(
        "Also: select text in any app → ⋮ → Ask Pocket AI.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
