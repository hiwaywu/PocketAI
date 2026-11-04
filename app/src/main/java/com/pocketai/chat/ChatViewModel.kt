package com.pocketai.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketai.chat.api.ApiException
import com.pocketai.chat.api.ChatClient
import com.pocketai.chat.api.StreamResult
import com.pocketai.chat.api.ToolCall
import com.pocketai.chat.api.WebTools
import com.pocketai.chat.api.str
import com.pocketai.chat.data.ApiProfile
import com.pocketai.chat.data.AppSettings
import com.pocketai.chat.data.ChatMessage
import com.pocketai.chat.data.ChatRepository
import com.pocketai.chat.data.Conversation
import com.pocketai.chat.data.ImageUtil
import com.pocketai.chat.data.SettingsStore
import com.pocketai.chat.data.Source
import com.pocketai.chat.data.ToolEvent
import com.pocketai.chat.data.hostOf
import com.pocketai.chat.data.normalizeBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class PageContext(val title: String, val url: String, val text: String)

data class UiState(
    val settings: AppSettings,
    val current: Conversation = Conversation(),
    val conversations: List<Conversation> = emptyList(),
    val streaming: Boolean = false,
    val status: String? = null,
    val pendingImages: List<String> = emptyList(),
    val pendingPage: PageContext? = null,
    val notice: String? = null,
    val composerPrefill: String? = null,
)

class ChatViewModel(private val app: Application) : AndroidViewModel(app) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()
    private val api = ChatClient(http)
    private val web = WebTools(http, app)
    private val repo = ChatRepository(app)
    private val store = SettingsStore(app)

    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _openUrl = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val openUrl = _openUrl.asSharedFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repo.list()
            _state.update { it.copy(conversations = list) }
        }
    }

    // ---------------------------------------------------------------- settings & API profiles

    private fun setSettings(s: AppSettings) {
        store.save(s)
        _state.update { it.copy(settings = s) }
    }

    /** Saves the non-connection settings (prompt, web tools, search). */
    fun saveGeneral(systemPrompt: String, webTools: Boolean, engine: com.pocketai.chat.data.SearchEngine, searchKeys: Map<String, String>) {
        setSettings(
            state.value.settings.copy(
                systemPrompt = systemPrompt, webTools = webTools, searchEngine = engine, searchKeys = searchKeys,
            )
        )
    }

    /** Makes this address/key/model the one chats use, without saving it as a named API. */
    fun applyConnection(baseUrl: String, apiKey: String, model: String, models: List<String>) {
        setSettings(
            state.value.settings.copy(
                baseUrl = normalizeBaseUrl(baseUrl), apiKey = apiKey.trim(), model = model.trim(), models = models,
            )
        )
    }

    /** Saves the current connection as a named API (updates the active one unless [asNew]). */
    fun saveProfile(name: String, baseUrl: String, apiKey: String, model: String, models: List<String>, asNew: Boolean) {
        val s = state.value.settings
        val trimmed = name.trim().ifBlank { hostOf(baseUrl).ifBlank { "My API" } }
        val existing = if (asNew) s.profiles.firstOrNull { it.name.equals(trimmed, true) }
        else s.activeProfile ?: s.profiles.firstOrNull { it.name.equals(trimmed, true) }
        val profile = ApiProfile(
            id = existing?.id ?: ApiProfile(name = "", baseUrl = "", apiKey = "", model = "").id,
            name = trimmed,
            baseUrl = normalizeBaseUrl(baseUrl),
            apiKey = apiKey.trim(),
            model = model.trim(),
            models = models,
        )
        setSettings(
            s.copy(
                profiles = s.profiles.filterNot { it.id == profile.id } + profile,
                activeProfileId = profile.id,
                baseUrl = profile.baseUrl, apiKey = profile.apiKey, model = profile.model, models = models,
            )
        )
        notice("Saved API “${profile.name}”")
    }

    fun selectProfile(id: String) {
        val s = state.value.settings
        val p = s.profiles.firstOrNull { it.id == id } ?: return
        setSettings(s.copy(activeProfileId = p.id, baseUrl = p.baseUrl, apiKey = p.apiKey, model = p.model, models = p.models))
    }

    fun renameProfile(id: String, name: String) {
        val s = state.value.settings
        if (name.isBlank()) return
        setSettings(s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(name = name.trim()) else it }))
    }

    fun deleteProfile(id: String) {
        val s = state.value.settings
        setSettings(
            s.copy(
                profiles = s.profiles.filterNot { it.id == id },
                activeProfileId = if (s.activeProfileId == id) "" else s.activeProfileId,
            )
        )
    }

    /** Switch model from the chat screen. The active saved API remembers the choice. */
    fun selectModel(model: String) {
        val s = state.value.settings
        val profiles = if (s.matchesSavedProfile()) s.profiles.map { if (it.id == s.activeProfileId) it.copy(model = model) else it } else s.profiles
        setSettings(s.copy(model = model, profiles = profiles))
    }

    suspend fun loadModels(baseUrl: String, apiKey: String): List<String> = api.listModels(normalizeBaseUrl(baseUrl), apiKey)

    /** Loads models for the current connection and caches them (used by the chat screen's model menu). */
    fun refreshModels() {
        val s = state.value.settings
        viewModelScope.launch {
            try {
                val models = loadModels(s.baseUrl, s.apiKey)
                val cur = state.value.settings
                val profiles = if (cur.matchesSavedProfile()) cur.profiles.map { if (it.id == cur.activeProfileId) it.copy(models = models) else it } else cur.profiles
                setSettings(cur.copy(models = models, profiles = profiles))
                notice("Loaded ${models.size} models")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice(e.message ?: "Could not load models")
            }
        }
    }

    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): String = try {
        val body = buildJsonObject {
            put("model", model.trim())
            put("stream", true)
            putJsonArray("messages") {
                addJsonObject { put("role", "user"); put("content", "Reply with just the word OK.") }
            }
        }
        val r = api.streamChat(normalizeBaseUrl(baseUrl), apiKey, body, {}, {})
        "✅ Connected — $model replied “${r.content.trim().take(40)}”"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "❌ ${e.message}"
    }

    // ---------------------------------------------------------------- conversations

    fun notice(text: String) = _state.update { it.copy(notice = text) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun openInBrowser(url: String) {
        _openUrl.tryEmit(url)
    }

    /** Stops any running reply and saves it before switching chats. */
    private fun stopForSwitch() {
        if (state.value.streaming) {
            job?.cancel()
            val c = state.value.current
            val msgs = c.messages.map { m ->
                if (m.role == "assistant" && m.id == c.messages.lastOrNull()?.id && m.text.isBlank()) m.copy(text = "_(stopped)_") else m
            }
            _state.update { it.copy(current = c.copy(messages = msgs), streaming = false, status = null) }
            persist()
        }
    }

    fun newChat() {
        stopForSwitch()
        _state.update { it.copy(current = Conversation(), pendingImages = emptyList(), pendingPage = null) }
    }

    fun openChat(id: String) {
        stopForSwitch()
        val c = state.value.conversations.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(current = c) }
    }

    fun deleteChat(id: String) {
        val c = state.value.conversations.firstOrNull { it.id == id } ?: return
        if (state.value.current.id == id) stopForSwitch()
        viewModelScope.launch(Dispatchers.IO) { repo.delete(c) }
        _state.update { st ->
            st.copy(
                conversations = st.conversations.filterNot { it.id == id },
                current = if (st.current.id == id) Conversation() else st.current,
            )
        }
    }

    fun addImages(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val files = uris.mapNotNull { ImageUtil.importImage(app, it, repo.imageDir)?.absolutePath }
            if (files.size < uris.size) notice("Couldn't read ${uris.size - files.size} photo(s)")
            _state.update { it.copy(pendingImages = (it.pendingImages + files).take(MAX_IMAGES)) }
        }
    }

    fun removePendingImage(path: String) {
        File(path).delete()
        _state.update { it.copy(pendingImages = it.pendingImages - path) }
    }

    fun attachPage(title: String, url: String, text: String, summarize: Boolean) {
        if (text.isBlank()) {
            notice("Couldn't read any text on this page yet — wait for it to load and try again.")
            return
        }
        _state.update { it.copy(pendingPage = PageContext(title.ifBlank { url }, url, text.take(MAX_PAGE_CHARS))) }
        if (summarize) send("Summarize this page.")
    }

    fun clearPage() = _state.update { it.copy(pendingPage = null) }

    fun consumePrefill() = _state.update { it.copy(composerPrefill = null) }

    /** Something was shared to Pocket AI from another app (Firefox, YouTube, Gallery…). */
    fun handleShare(text: String?, subject: String?, uris: List<Uri>) {
        newChat()
        if (uris.isNotEmpty()) addImages(uris)
        val shared = text?.trim().orEmpty()
        val url = Regex("""https?://\S+""").find(shared)?.value?.trimEnd('.', ',', ')', '"', '\'')
        when {
            url != null -> {
                val isVideo = Regex("""youtube\.com|youtu\.be""", RegexOption.IGNORE_CASE).containsMatchIn(url)
                val prompt = if (isVideo) "Summarize this YouTube video: $url" else "Summarize this page: $url"
                if (!send(prompt)) _state.update { it.copy(composerPrefill = prompt) }
            }
            shared.isNotEmpty() -> _state.update {
                it.copy(composerPrefill = (subject?.let { s -> "$s\n\n" } ?: "") + shared)
            }
        }
    }

    /** Returns false when the message couldn't be sent (e.g. no API set up). */
    fun send(text: String): Boolean {
        val st = state.value
        if (st.streaming) return false
        if (!st.settings.isConfigured) {
            notice("Set up an API first: Settings → API address, key, and model.")
            return false
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty() && st.pendingImages.isEmpty() && st.pendingPage == null) return false
        val page = st.pendingPage
        val user = ChatMessage(
            role = "user", text = trimmed, images = st.pendingImages,
            pageTitle = page?.title, pageUrl = page?.url, pageText = page?.text,
        )
        val conv = st.current.copy(
            messages = st.current.messages + user,
            title = if (st.current.messages.isEmpty()) makeTitle(user) else st.current.title,
            updatedAt = System.currentTimeMillis(),
        )
        _state.update { it.copy(pendingImages = emptyList(), pendingPage = null) }
        runAssistant(conv)
        return true
    }

    fun stop() {
        job?.cancel()
    }

    fun retry() {
        val st = state.value
        if (st.streaming) return
        val msgs = st.current.messages
        if (msgs.lastOrNull()?.role == "assistant") runAssistant(st.current.copy(messages = msgs.dropLast(1)))
        else if (msgs.lastOrNull()?.role == "user") runAssistant(st.current)
    }

    private fun makeTitle(m: ChatMessage): String {
        val base = m.text.ifBlank { m.pageTitle ?: if (m.images.isNotEmpty()) "Photo chat" else "New chat" }
        val line = base.lineSequence().first().trim()
        return if (line.length > 42) line.take(40).trimEnd() + "…" else line
    }

    private fun updateConv(convId: String, f: (Conversation) -> Conversation) =
        _state.update { st -> if (st.current.id == convId) st.copy(current = f(st.current)) else st }

    private fun updateMsg(convId: String, msgId: String, f: (ChatMessage) -> ChatMessage) =
        updateConv(convId) { c -> c.copy(messages = c.messages.map { if (it.id == msgId) f(it) else it }) }

    private fun setStatus(convId: String, s: String?) =
        _state.update { st -> if (st.current.id == convId) st.copy(status = s) else st }

    private fun persist() {
        val c = state.value.current
        if (c.messages.isEmpty()) return
        _state.update { st ->
            st.copy(conversations = (listOf(c) + st.conversations.filterNot { it.id == c.id }).sortedByDescending { it.updatedAt })
        }
        viewModelScope.launch(Dispatchers.IO) { repo.save(c) }
    }

    // ---------------------------------------------------------------- the reply loop

    private fun runAssistant(conv: Conversation) {
        val s = state.value.settings
        val assistant = ChatMessage(role = "assistant", model = s.model)
        val convId = conv.id
        _state.update {
            it.copy(
                current = conv.copy(messages = conv.messages + assistant, updatedAt = System.currentTimeMillis()),
                streaming = true, status = null,
            )
        }
        persist()
        job = viewModelScope.launch {
            try {
                val history = withContext(Dispatchers.IO) { buildApiMessages(conv.messages, s) }
                agentLoop(convId, assistant.id, history, s)
            } catch (e: CancellationException) {
                updateMsg(convId, assistant.id) { if (it.text.isBlank()) it.copy(text = "_(stopped)_") else it }
                throw e
            } catch (e: Exception) {
                updateMsg(convId, assistant.id) { it.copy(error = e.message ?: e.toString()) }
            } finally {
                if (state.value.current.id == convId) {
                    _state.update { it.copy(streaming = false, status = null) }
                    persist()
                }
            }
        }
    }

    /**
     * Sends the chat to the model. If the model asks to search or read a page, the app runs that
     * tool, sends the result back, and asks again — until the model writes its final answer.
     */
    private suspend fun agentLoop(convId: String, msgId: String, history: List<JsonObject>, s: AppSettings) {
        val trail = mutableListOf<JsonObject>()
        val sources = mutableListOf<Source>()
        var useTools = s.webTools
        var includeUsage = s.baseUrl !in s.noStreamUsage
        var round = 0
        var finalContent = ""
        var promptTokens = 0L
        var completionTokens = 0L

        while (true) {
            round++
            val allowTools = useTools && round <= MAX_TOOL_ROUNDS
            val body = buildJsonObject {
                put("model", s.model)
                put("stream", true)
                if (includeUsage) putJsonObject("stream_options") { put("include_usage", true) }
                putJsonArray("messages") {
                    history.forEach { add(it) }
                    trail.forEach { add(it) }
                }
                if (allowTools) put("tools", WebTools.TOOL_DEFS)
            }
            updateMsg(convId, msgId) { m ->
                if (m.text.isNotBlank() && !m.text.endsWith("\n\n")) m.copy(text = m.text.trimEnd() + "\n\n") else m
            }
            if (round > 1) setStatus(convId, "Reading results…")

            val res: StreamResult = try {
                api.streamChat(
                    s.baseUrl, s.apiKey, body,
                    onText = { t ->
                        updateMsg(convId, msgId) { it.copy(text = it.text + t) }
                        if (state.value.status != null) setStatus(convId, null)
                    },
                    onReasoning = { if (state.value.status == null) setStatus(convId, "Thinking…") },
                )
            } catch (e: ApiException) {
                val msg = e.message.orEmpty()
                if (includeUsage && e.code in 400..422 && msg.contains("stream_options", true)) {
                    includeUsage = false
                    val cur = state.value.settings
                    setSettings(cur.copy(noStreamUsage = (cur.noStreamUsage + s.baseUrl).distinct()))
                    round--
                    continue
                }
                if (allowTools && trail.isEmpty() && e.code in 400..422 && (msg.contains("tool", true) || msg.contains("function", true))) {
                    useTools = false
                    notice("This model can't use web tools, so it's answering without them.")
                    round--
                    continue
                }
                throw e
            }
            promptTokens += res.promptTokens
            completionTokens += res.completionTokens

            if (res.toolCalls.isEmpty() || !allowTools) {
                finalContent = res.content
                break
            }

            val calls = res.toolCalls.mapIndexed { i, tc -> if (tc.id.isBlank()) tc.copy(id = "call_${round}_$i") else tc }
            trail += buildJsonObject {
                put("role", "assistant")
                if (res.content.isNotEmpty()) put("content", res.content) else put("content", JsonNull)
                putJsonArray("tool_calls") {
                    calls.forEach { tc ->
                        addJsonObject {
                            put("id", tc.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tc.name)
                                put("arguments", tc.arguments.ifBlank { "{}" })
                            }
                        }
                    }
                }
            }
            val results = coroutineScope { calls.map { tc -> async { runTool(convId, msgId, tc, s, sources) } }.awaitAll() }
            calls.zip(results).forEach { (tc, result) ->
                trail += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", tc.id)
                    put("content", result)
                }
            }
        }

        updateMsg(convId, msgId) {
            it.copy(apiTrail = trail.toList(), finalContent = finalContent, sources = sources.distinctBy { src -> src.url }.take(12))
        }
        updateConv(convId) {
            it.copy(promptTokens = it.promptTokens + promptTokens, completionTokens = it.completionTokens + completionTokens)
        }
    }

    private suspend fun runTool(convId: String, msgId: String, tc: ToolCall, s: AppSettings, sources: MutableList<Source>): String {
        val args = runCatching { Json.parseToJsonElement(tc.arguments.ifBlank { "{}" }).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        return when (tc.name) {
            "web_search" -> {
                val q = args.str("query") ?: return "Error: missing 'query'."
                val ev = ToolEvent(kind = "search", label = q)
                updateMsg(convId, msgId) { it.copy(toolEvents = it.toolEvents + ev) }
                setStatus(convId, "Searching: $q")
                try {
                    val hits = web.search(q, s.searchEngine, s.searchKey)
                    sources += hits.take(3).map { Source(it.title, it.url) }
                    hits.mapIndexed { i, h -> "${i + 1}. ${h.title}\nURL: ${h.url}\n${h.snippet}" }.joinToString("\n\n")
                        .ifBlank { "No results." }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    markFailed(convId, msgId, ev.id)
                    "Search failed: ${e.message}"
                }
            }
            "fetch_url" -> {
                val url = args.str("url") ?: return "Error: missing 'url'."
                val ev = ToolEvent(kind = "fetch", label = hostOf(url).ifBlank { url })
                updateMsg(convId, msgId) { it.copy(toolEvents = it.toolEvents + ev) }
                setStatus(convId, "Reading ${ev.label}")
                try {
                    val page = web.fetch(url)
                    sources += Source(page.title, page.url)
                    "Title: ${page.title}\nURL: ${page.url}\n\n${page.text}"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    markFailed(convId, msgId, ev.id)
                    "Could not open $url: ${e.message}"
                }
            }
            else -> "Error: unknown tool ${tc.name}."
        }
    }

    private fun markFailed(convId: String, msgId: String, evId: String) =
        updateMsg(convId, msgId) { m -> m.copy(toolEvents = m.toolEvents.map { if (it.id == evId) it.copy(failed = true) else it }) }

    private fun buildApiMessages(msgs: List<ChatMessage>, s: AppSettings): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        val now = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a z", Locale.US).format(Date())
        var system = s.systemPrompt.trim() + "\n\nThe current date and time is $now."
        if (s.webTools) {
            system += "\nYou can use the web_search and fetch_url tools. Use them whenever a question needs current " +
                "or specific information (news, scores, prices, schedules) or the user shares a link. " +
                "When you use information from the web, cite the sources as Markdown links."
        }
        out += buildJsonObject { put("role", "system"); put("content", system) }

        for (m in msgs) {
            when (m.role) {
                "user" -> {
                    val text = buildString {
                        if (m.pageText != null) {
                            append("[The user is sharing a web page from the in-app browser]\n")
                            append("Title: ${m.pageTitle}\nURL: ${m.pageUrl}\n<page_content>\n${m.pageText}\n</page_content>\n\n")
                        }
                        append(m.text.ifBlank { if (m.images.isNotEmpty()) "(see the attached photo)" else "" })
                    }
                    val images = m.images.mapNotNull { ImageUtil.toDataUrl(it) }
                    out += buildJsonObject {
                        put("role", "user")
                        if (images.isEmpty()) put("content", text)
                        else putJsonArray("content") {
                            addJsonObject { put("type", "text"); put("text", text) }
                            images.forEach { url ->
                                addJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", url) } }
                            }
                        }
                    }
                }
                "assistant" -> {
                    if (m.text.isBlank() && m.apiTrail.isEmpty()) continue
                    out.addAll(m.apiTrail)
                    val content = (m.finalContent ?: m.text).ifBlank { "(no reply)" }
                    out += buildJsonObject { put("role", "assistant"); put("content", content) }
                }
            }
        }
        return out
    }

    companion object {
        const val MAX_IMAGES = 4
        const val MAX_PAGE_CHARS = 30_000
        const val MAX_TOOL_ROUNDS = 6
    }
}
