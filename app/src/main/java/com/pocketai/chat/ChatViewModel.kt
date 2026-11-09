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
import com.pocketai.chat.data.ModelRates
import com.pocketai.chat.data.ReplyUsage
import com.pocketai.chat.data.SearchEngine
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Text sent along with a message: another app's screen or selected text. */
data class PageContext(val title: String, val url: String?, val text: String, val source: String, val app: String? = null)

data class UiState(
    val settings: AppSettings,
    val current: Conversation = Conversation(),
    val conversations: List<Conversation> = emptyList(),
    /** Chats with a reply being written right now (replies keep going when you switch chats or leave the app). */
    val running: Set<String> = emptySet(),
    /** Progress line ("Searching: …") per running chat. */
    val statuses: Map<String, String> = emptyMap(),
    val pendingImages: List<String> = emptyList(),
    val notice: String? = null,
    val composerPrefill: String? = null,
) {
    /** True while the open chat's reply is being written. */
    val streaming: Boolean get() = current.id in running
    val status: String? get() = statuses[current.id]
}

class ChatViewModel(private val app: Application) : AndroidViewModel(app) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()
    /** Pocket AI identifies itself honestly (OpenCode asks clients to send their own app name). */
    private val api = ChatClient(http, "PocketAI/${BuildConfig.VERSION_NAME} (Android)")
    private val web = WebTools(http, app)
    private val repo = ChatRepository(app)
    private val store = SettingsStore(app)

    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /** Completed once the saved chats are read from disk (declared before init, which completes it). */
    private val chatsLoaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repo.list()
            _state.update { it.copy(conversations = list) }
            chatsLoaded.complete(Unit)
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
        val r = api.streamChat(normalizeBaseUrl(baseUrl), apiKey, body, "test-" + java.util.UUID.randomUUID(), {}, {})
        "✅ Connected — $model replied “${r.content.trim().take(40)}”"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "❌ ${e.message}"
    }

    // ---------------------------------------------------------------- conversations

    fun notice(text: String) = _state.update { it.copy(notice = text) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * Puts the open chat into the chat list before switching away. A reply still being written
     * keeps going and keeps updating that chat in the list.
     */
    private fun stashCurrent() {
        val c = state.value.current
        if (c.messages.isEmpty()) return
        _state.update { st ->
            st.copy(conversations = (listOf(c) + st.conversations.filterNot { it.id == c.id }).sortedByDescending { it.updatedAt })
        }
    }

    fun newChat() {
        stashCurrent()
        _state.update { it.copy(current = Conversation(), pendingImages = emptyList()) }
    }

    fun openChat(id: String) {
        if (state.value.current.id == id) return
        val c = state.value.conversations.firstOrNull { it.id == id } ?: return
        stashCurrent()
        _state.update { it.copy(current = c) }
    }

    fun deleteChat(id: String) {
        jobs.remove(id)?.cancel()
        val c = state.value.conversations.firstOrNull { it.id == id } ?: state.value.current.takeIf { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) { repo.delete(c) }
        _state.update { st ->
            st.copy(
                conversations = st.conversations.filterNot { it.id == id },
                current = if (st.current.id == id) Conversation() else st.current,
            )
        }
    }

    fun renameChat(id: String, title: String) {
        val t = title.trim().ifBlank { return }
        editChat(id) { it.copy(title = t) }
    }

    /** Pinned chats stay at the top of the chat list. */
    fun togglePin(id: String) = editChat(id) { it.copy(pinned = !it.pinned) }

    /** Changes a chat's details (not its messages or date) and saves it. */
    private fun editChat(id: String, f: (Conversation) -> Conversation) {
        if (convOf(id) == null) return
        updateConv(id, f)
        val c = convOf(id) ?: return
        _state.update { st -> st.copy(conversations = st.conversations.map { if (it.id == id) c else it }) }
        if (c.messages.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { repo.save(c) }
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

    fun consumePrefill() = _state.update { it.copy(composerPrefill = null) }

    /** Something was shared to Pocket AI from another app (Firefox, YouTube, Gallery…). */
    fun handleShare(text: String?, subject: String?, uris: List<Uri>) {
        newChat()
        if (uris.isNotEmpty()) addImages(uris)
        val shared = text?.trim().orEmpty()
        val url = Regex("""https?://\S+""").find(shared)?.value?.trimEnd('.', ',', ')', '"', '\'')
        when {
            url != null -> {
                val prompt = summarizePrompt(url)
                if (!send(prompt)) _state.update { it.copy(composerPrefill = prompt) }
            }
            shared.isNotEmpty() -> _state.update {
                it.copy(composerPrefill = (subject?.let { s -> "$s\n\n" } ?: "") + shared)
            }
        }
    }

    /**
     * Returns false when the message couldn't be sent (e.g. no API set up). [page] attaches another app's
     * screen or selected text; [title] names a new chat.
     */
    fun send(text: String, page: PageContext? = null, title: String? = null): Boolean {
        val st = state.value
        if (st.streaming) return false
        if (!st.settings.isConfigured) {
            notice("Set up an API first: Settings → API address, key, and model.")
            return false
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty() && st.pendingImages.isEmpty() && page == null) return false
        val user = ChatMessage(
            role = "user", text = trimmed, images = st.pendingImages,
            pageTitle = page?.title, pageUrl = page?.url, pageText = page?.text, pageSource = page?.source,
            pageApp = page?.app,
        )
        val conv = st.current.copy(
            messages = st.current.messages + user,
            title = if (st.current.messages.isEmpty()) (title ?: makeTitle(user)) else st.current.title,
            updatedAt = System.currentTimeMillis(),
        )
        _state.update { it.copy(pendingImages = emptyList()) }
        runAssistant(conv)
        return true
    }

    /**
     * The panel over another app: starts a new chat with what the Pocket AI button read (or the selected text)
     * and [question]. Returns the chat's id, or null if it couldn't be sent.
     */
    fun askAbout(capture: com.pocketai.chat.screen.ScreenCapture?, question: String): String? {
        newChat()
        val page = pageOf(capture)
        val name = capture?.appName?.let { app ->
            val q = question.trim().lineSequence().firstOrNull().orEmpty()
            "$app: " + if (q.length > 40) q.take(38).trimEnd() + "…" else q
        }
        return if (send(question, page, name)) state.value.current.id else null
    }

    private fun pageOf(capture: com.pocketai.chat.screen.ScreenCapture?): PageContext? =
        capture?.takeIf { it.text.isNotBlank() }?.let {
            PageContext(it.label, it.url, listOfNotNull(it.note, it.text).joinToString("\n\n"), it.kind, it.packageName)
        }

    /** What [chatId] was last about (its newest screen or selection), for [com.pocketai.chat.screen.SameTopic]. */
    fun lastScreen(chatId: String, at: Long): com.pocketai.chat.screen.SameTopic.Last? {
        val m = convOf(chatId)?.messages?.lastOrNull { it.role == "user" && it.pageSource != null && it.pageText != null } ?: return null
        return com.pocketai.chat.screen.SameTopic.Last(m.pageApp, m.pageUrl, m.pageText.orEmpty(), at)
    }


    /** Waits until the saved chats are loaded (right after the app starts they're still being read). */
    suspend fun awaitChats() = chatsLoaded.await()

    /**
     * A question in the panel over another app. Continues [chatId] when it still exists (tapping the Pocket AI
     * button again keeps the conversation going), attaching [capture] if it hasn't been sent yet ([attach]);
     * otherwise starts a new chat with it. Returns the chat's id, or null if it couldn't be sent.
     */
    fun askInPanel(chatId: String?, capture: com.pocketai.chat.screen.ScreenCapture?, attach: Boolean, question: String): String? {
        val existing = chatId?.let { convOf(it) } ?: return askAbout(capture, question)
        if (state.value.current.id != existing.id) openChat(existing.id)
        if (state.value.current.id != existing.id) return askAbout(capture, question)
        return if (send(question, if (attach) pageOf(capture) else null)) existing.id else null
    }

    /** For the Pocket AI button in the YouTube app: finds the video on YouTube by the title read from the screen. */
    /**
     * For the Pocket AI button in the YouTube app: finds the video by the title on screen and reads it like a shared
     * link (details + transcript). Null if it can't be found.
     */
    suspend fun readYouTubeVideo(titles: List<String>): com.pocketai.chat.api.FetchedPage? {
        val link = web.findYouTubeVideo(titles) ?: return null
        val s = state.value.settings
        return web.fetch(link, s.deepSeekKey.takeIf { s.searchEngine == SearchEngine.DEEPSEEK })
    }

    /**
     * For the Pocket AI button in the Reddit app: finds the thread on reddit.com by the title on screen and reads
     * it like a shared link (post, linked article, top comments). Null if it can't be found.
     */
    suspend fun readRedditThread(subreddit: String?, titles: List<String>): com.pocketai.chat.api.FetchedPage? {
        val link = web.findRedditThread(subreddit, titles) ?: return null
        val s = state.value.settings
        return web.fetch(link, s.deepSeekKey.takeIf { s.searchEngine == SearchEngine.DEEPSEEK })
    }

    /** A chat by id, whether it's open in the main app or in the list. */
    fun chat(id: String): Conversation? = convOf(id)

    /**
     * Replaces one of your messages with [newText] and answers again from there; everything after it
     * is dropped (like editing a message in ChatGPT). Photos on that message are kept.
     */
    fun editAndResend(msgId: String, newText: String): Boolean {
        val st = state.value
        if (st.streaming) {
            notice("Wait for the current reply to finish (or stop it) first.")
            return false
        }
        if (!st.settings.isConfigured) {
            notice("Set up an API first: Settings → API address, key, and model.")
            return false
        }
        val msgs = st.current.messages
        val i = msgs.indexOfFirst { it.id == msgId && it.role == "user" }
        if (i < 0) return false
        val old = msgs[i]
        val trimmed = newText.trim()
        if (trimmed.isEmpty() && old.images.isEmpty() && old.pageText == null) return false
        val edited = old.copy(id = java.util.UUID.randomUUID().toString(), text = trimmed)
        val conv = st.current.copy(
            messages = msgs.take(i) + edited,
            title = if (i == 0) makeTitle(edited) else st.current.title,
            updatedAt = System.currentTimeMillis(),
        )
        runAssistant(conv)
        return true
    }

    fun stop() {
        jobs[state.value.current.id]?.cancel()
    }

    fun retry() {
        val st = state.value
        if (st.streaming) return
        val msgs = st.current.messages
        if (msgs.lastOrNull()?.role == "assistant") runAssistant(st.current.copy(messages = msgs.dropLast(1)))
        else if (msgs.lastOrNull()?.role == "user") runAssistant(st.current)
    }

    private fun summarizePrompt(url: String): String = when {
        Regex("""youtube\.com|youtu\.be""", RegexOption.IGNORE_CASE).containsMatchIn(url) -> "Summarize this YouTube video: $url"
        com.pocketai.chat.api.Reddit.isReddit(url) -> "Summarize this Reddit thread: $url"
        else -> "Summarize this page: $url"
    }

    private fun makeTitle(m: ChatMessage): String {
        val base = m.text.ifBlank { m.pageTitle ?: if (m.images.isNotEmpty()) "Photo chat" else "New chat" }
        val line = base.lineSequence().first().trim()
        return if (line.length > 42) line.take(40).trimEnd() + "…" else line
    }

    /** Updates a chat whether it's the open one or one in the list (a reply running in the background). */
    private fun updateConv(convId: String, f: (Conversation) -> Conversation) =
        _state.update { st ->
            if (st.current.id == convId) st.copy(current = f(st.current))
            else {
                val i = st.conversations.indexOfFirst { it.id == convId }
                if (i < 0) st else st.copy(conversations = st.conversations.toMutableList().also { it[i] = f(it[i]) })
            }
        }

    private fun updateMsg(convId: String, msgId: String, f: (ChatMessage) -> ChatMessage) =
        updateConv(convId) { c -> c.copy(messages = c.messages.map { if (it.id == msgId) f(it) else it }) }

    private fun statusOf(convId: String): String? = state.value.statuses[convId]

    private fun setStatus(convId: String, s: String?) =
        _state.update { st -> st.copy(statuses = if (s == null) st.statuses - convId else st.statuses + (convId to s)) }

    private fun convOf(convId: String): Conversation? =
        state.value.let { st -> st.current.takeIf { it.id == convId } ?: st.conversations.firstOrNull { it.id == convId } }

    /** Saves a chat to disk and moves it to the top of the list. */
    private fun persist(convId: String = state.value.current.id) {
        val c = convOf(convId) ?: return
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
                running = it.running + convId, statuses = it.statuses - convId,
            )
        }
        persist(convId)
        // Keeps the app alive and online if the user leaves it before the reply is done.
        ReplyService.start(app)
        jobs[convId] = viewModelScope.launch {
            var stopped = false
            try {
                val history = withContext(Dispatchers.IO) { buildApiMessages(conv.messages, s) }
                agentLoop(convId, assistant.id, history, s)
            } catch (e: CancellationException) {
                stopped = true
                updateMsg(convId, assistant.id) { if (it.text.isBlank()) it.copy(text = "_(stopped)_") else it }
                throw e
            } catch (e: Exception) {
                updateMsg(convId, assistant.id) { it.copy(error = e.message ?: e.toString()) }
            } finally {
                jobs.remove(convId)
                _state.update { it.copy(running = it.running - convId, statuses = it.statuses - convId) }
                persist(convId)
                if (!stopped) announceDone(convId, assistant.id)
            }
        }
    }

    /** Lets the user know a reply finished while they were in another app or another chat. */
    private fun announceDone(convId: String, msgId: String) {
        val c = convOf(convId) ?: return
        val m = c.messages.firstOrNull { it.id == msgId } ?: return
        val pocket = app as PocketApp
        if (!pocket.visible) {
            val preview = m.error ?: (m.finalContent ?: m.text).replace(Regex("""[*_#`>|]"""), "").trim().take(300)
            ReplyService.notifyDone(app, convId, c.title, preview.ifBlank { "Reply ready" }, failed = m.error != null)
        } else if (state.value.current.id != convId) {
            notice(if (m.error != null) "Reply failed in “${c.title}”" else "Reply ready in “${c.title}”")
        }
    }

    /**
     * Sends the chat to the model. If the model asks to search or read a page, the app runs that
     * tool, sends the result back, and asks again — until the model writes its final answer.
     * Each step re-sends the conversation, so the usage of every step is added up.
     */
    private suspend fun agentLoop(convId: String, msgId: String, history: BuiltHistory, s: AppSettings) {
        val trail = mutableListOf<JsonObject>()
        val sources = mutableListOf<Source>()
        var useTools = s.webTools
        var includeUsage = s.baseUrl !in s.noStreamUsage
        var round = 0
        var finalContent = ""
        var input = 0L
        var output = 0L
        var cached = 0L
        var outputChars = 0L
        var searches = 0
        var pages = 0
        // Rough per-step estimate (about 4 characters per token, about 1,200 tokens per photo)
        // of what each step's input was made of.
        var estHistory = 0L
        var estWeb = 0L
        var estPhotos = 0L
        var estShared = 0L
        val started = System.currentTimeMillis()

        while (true) {
            round++
            val allowTools = useTools && round <= MAX_TOOL_ROUNDS
            val body = buildJsonObject {
                put("model", s.model)
                put("stream", true)
                if (includeUsage) putJsonObject("stream_options") { put("include_usage", true) }
                putJsonArray("messages") {
                    history.messages.forEach { add(it) }
                    trail.forEach { add(it) }
                    // Out of web steps: without this, some models (DeepSeek) still try to search and print the
                    // raw tool-call markup as their answer.
                    if (useTools && !allowTools && trail.isNotEmpty()) addJsonObject {
                        put("role", "user")
                        put("content", "[Pocket AI: you've used all your web steps for this answer. Answer now with what you have; don't call any tools.]")
                    }
                }
                if (allowTools) put("tools", WebTools.TOOL_DEFS)
            }
            if (round > 1) setStatus(convId, "Reading results…")

            val res: StreamResult = try {
                streamWithRetry(convId, msgId, s, body)
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
            input += res.promptTokens
            output += res.completionTokens
            cached += res.cachedTokens
            outputChars += res.content.length + res.toolCalls.sumOf { it.arguments.length }
            estHistory += history.textChars / 4 + (if (allowTools) TOOL_DEF_TOKENS else 0)
            estShared += history.sharedChars / 4
            estPhotos += history.images * PHOTO_TOKENS
            estWeb += (history.webChars + trail.sumOf { it.toString().length }) / 4

            if (res.toolCalls.isEmpty() || !allowTools) {
                finalContent = res.content
                break
            }

            // Text written before a tool call is narration ("I'll open that page for you."); the status line
            // already says what's happening, so it's taken off the screen (the model still gets it back).
            updateMsg(convId, msgId) { it.copy(text = "") }
            val calls = res.toolCalls.mapIndexed { i, tc -> if (tc.id.isBlank()) tc.copy(id = "call_${round}_$i") else tc }
            searches += calls.count { it.name == "web_search" }
            pages += calls.count { it.name == "fetch_url" }
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

        if (finalContent.isBlank() && convOf(convId)?.messages?.firstOrNull { it.id == msgId }?.text.isNullOrBlank()) {
            updateMsg(convId, msgId) { it.copy(text = "_(The model sent an empty reply. Tap ↻ to try again.)_") }
        }
        // Scale the estimate so the parts add up to what the provider actually billed.
        val estTotal = (estHistory + estWeb + estPhotos + estShared).coerceAtLeast(1)
        val reported = input > 0
        val realInput = if (reported) input else estTotal
        fun part(x: Long) = x * realInput / estTotal
        val usage = ReplyUsage(
            input = realInput,
            output = if (output > 0) output else outputChars / 4,
            cached = cached,
            steps = round,
            searches = searches,
            pages = pages,
            estHistory = part(estHistory),
            estWeb = part(estWeb),
            estPhotos = part(estPhotos),
            estShared = part(estShared),
            estimated = !reported,
            seconds = ((System.currentTimeMillis() - started) / 1000).toInt(),
            multiplier = ModelRates.multiplier(s.baseUrl, s.model),
            apiName = s.connectionName,
            apiHost = hostOf(s.baseUrl),
        )
        updateMsg(convId, msgId) {
            it.copy(
                apiTrail = trail.toList(), finalContent = finalContent, usage = usage,
                sources = sources.distinctBy { src -> src.url }.take(12),
            )
        }
        updateConv(convId) {
            it.copy(promptTokens = it.promptTokens + usage.input, completionTokens = it.completionTokens + usage.output)
        }
    }

    /**
     * One streamed step. If the connection drops (Wi-Fi ↔ mobile data, a tunnel, a provider hiccup)
     * the half-written text of this step is rolled back and the step is sent again, a few times.
     */
    private suspend fun streamWithRetry(convId: String, msgId: String, s: AppSettings, body: JsonObject): StreamResult {
        val before = convOf(convId)?.messages?.firstOrNull { it.id == msgId }?.text.orEmpty()
        var attempt = 0
        while (true) {
            try {
                return api.streamChat(
                    s.baseUrl, s.apiKey, body, convId,
                    onText = { t ->
                        updateMsg(convId, msgId) { it.copy(text = it.text + t) }
                        if (statusOf(convId) != null) setStatus(convId, null)
                    },
                    onReasoning = { if (statusOf(convId) == null) setStatus(convId, "Thinking…") },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val transient = e is IOException || (e is ApiException && e.code in RETRY_HTTP_CODES)
                if (!transient || attempt >= NET_RETRY_DELAYS.size) throw e
                updateMsg(convId, msgId) { it.copy(text = before) }
                setStatus(convId, "Connection lost — retrying…")
                delay(NET_RETRY_DELAYS[attempt++])
            }
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
                    val hits = try {
                        web.search(q, s.searchEngine, s.searchKey)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Keep answering even if the chosen engine fails (no key, out of balance…).
                        if (s.searchEngine == SearchEngine.DUCKDUCKGO) throw e
                        notice("${s.searchEngine.label.substringBefore(" ")} search failed (${e.message?.take(80)}); used DuckDuckGo instead.")
                        web.search(q, SearchEngine.DUCKDUCKGO, "")
                    }
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
                    val page = web.fetch(url, s.deepSeekKey.takeIf { s.searchEngine == SearchEngine.DEEPSEEK }) { setStatus(convId, it) }
                    sources += Source(page.title, page.url)
                    nameAfterPage(convId, page.title)
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

    /**
     * A chat started with "Summarize this page: <link>" is renamed after the page it read, so the chat
     * list shows "Teaching how to split a McDonald's breakfast…" instead of the link.
     */
    private fun nameAfterPage(convId: String, pageTitle: String) {
        val t = pageTitle.replace(Regex("""\s+"""), " ").trim()
        if (t.isEmpty() || t.startsWith("http")) return
        updateConv(convId) { c ->
            val auto = c.title.startsWith("Summarize this ") && c.messages.count { it.role == "user" } == 1
            if (auto) c.copy(title = if (t.length > 60) t.take(58).trimEnd() + "…" else t) else c
        }
    }

    private fun markFailed(convId: String, msgId: String, evId: String) =
        updateMsg(convId, msgId) { m -> m.copy(toolEvents = m.toolEvents.map { if (it.id == evId) it.copy(failed = true) else it }) }

    /** The request history plus character counts used for the usage breakdown. */
    private class BuiltHistory(val messages: List<JsonObject>, val textChars: Int, val sharedChars: Int, val webChars: Int, val images: Int)

    /**
     * Builds the messages sent to the API. All chat text is always included, so the chat keeps its
     * memory. To avoid paying for the same bulky content again and again:
     *  - photos and shared browser pages are only re-sent for the last [KEEP_MEDIA_TURNS] user messages;
     *  - raw web pages and search results are only re-sent for the latest answer
     *    (older answers keep their text plus the source links).
     * The system prompt has the date only (no clock time) so it stays identical between messages,
     * which lets providers that cache repeated input bill it cheaper.
     */
    private fun buildApiMessages(msgs: List<ChatMessage>, s: AppSettings): BuiltHistory {
        val out = mutableListOf<JsonObject>()
        var textChars = 0
        var sharedChars = 0
        var webChars = 0
        var images = 0
        val today = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())
        val zone = java.util.TimeZone.getDefault().id
        var system = s.systemPrompt.trim() + "\n\nToday's date is $today (the user's time zone is $zone)."
        // Language: without the explicit "one-word request" wording, a Chinese article + "Summary" got a Chinese
        // reply 2 of 2 times, and foreign quotes leaked untranslated. Summaries: 2.5 dropped all templates at the
        // owner's request ("like you'd do it as a chatbot"). 2.6 rewrote the rest in the same plain style.
        system += "\nReply in the language the user writes in (even a one-word request like 'Summary' or 'Explain' " +
            "means English), not the language of a shared page, video or search result. Translate foreign-language " +
            "text rather than quoting it; when the original wording matters (a name, a title, a key quote), give the " +
            "translation with the original in brackets, and don't guess the English spelling of a name you're unsure of." +
            "\nWhen the user shares a link or asks for a summary (a link on its own means \"summarize this\"), explain " +
            "it the way a thoughtful friend would in a chat: what it's about and why it matters, then the details " +
            "worth knowing, in whatever shape fits (a couple of short paragraphs, or a few bullets when there are " +
            "several separate points). Keep the real specifics (names, numbers, dates, quotes), skip filler, and " +
            "match the length to the source. Write naturally: no fixed template or labels like 'TL;DR:' or 'The " +
            "gist:'. Stick to what the source says; if part of it couldn't be read (a paywall, a video, no " +
            "transcript), say so briefly. It's read on a phone, so no wide tables."
        if (s.webTools) {
            // Freshness (1.9, tested 4/4): models answered "latest/last" questions from old training or an old
            // article, or searched for the answer they already believed. Keep the open, dated query and the example.
            system += "\nYou can search the web (web_search) and read pages (fetch_url). Use them when a question " +
                "needs current or specific information (news, scores, prices, schedules) or the user shares a link. " +
                "Work like someone who knows where to look: turn relative dates ('last night', 'this weekend') into " +
                "real dates, start with one precise search, answer from the result summaries when they're enough, and " +
                "read at most a few pages, preferring official or major sources. Don't repeat near-identical searches. " +
                "Your own knowledge stops well before today, so for anything that changes over time (the latest, last " +
                "or next result or event, records, prices, versions, who holds a role) assume there may be newer news: " +
                "search with an open query that adds the current month and year instead of the answer you remember " +
                "(e.g. 'Islam Makhachev last fight November 2026', not the fight you remember), and when the results' " +
                "dates disagree with your memory, trust the newest. Pages the user shares are snapshots and may be out " +
                "of date too. Cite what you use as Markdown links. Just call the tools; don't announce them first."
        }
        out += buildJsonObject { put("role", "system"); put("content", system) }
        textChars += system.length

        val userIdx = msgs.indices.filter { msgs[it].role == "user" }
        val mediaFrom = userIdx.takeLast(KEEP_MEDIA_TURNS).firstOrNull() ?: 0
        val lastAssistant = msgs.indexOfLast { it.role == "assistant" && (it.text.isNotBlank() || it.apiTrail.isNotEmpty()) }

        msgs.forEachIndexed { i, m ->
            when (m.role) {
                "user" -> {
                    val keepMedia = i >= mediaFrom
                    val shared = StringBuilder()
                    val note = StringBuilder()
                    if (m.pageText != null) {
                        if (keepMedia) when (m.pageSource) {
                            "screen" -> {
                                shared.append(
                                    "[What's on the user's screen right now (${m.pageTitle}" + (m.pageUrl?.let { ", page: $it" } ?: "") +
                                        "). It was read from the screen, so it also has buttons and menus, and in apps only what " +
                                        "has loaded; go by the main content. If the question needs more than this (the rest of an " +
                                        "article, all of a Reddit thread's comments, a video's transcript) and there's a page link, " +
                                        "call fetch_url on it.]\n<screen>\n${m.pageText}\n</screen>\n\n"
                                )
                            }
                            "selection" -> shared.append("[${m.pageTitle}: text the user selected]\n<selected_text>\n${m.pageText}\n</selected_text>\n\n")
                            else -> {
                                shared.append("[The user is sharing a web page]\n")
                                shared.append("Title: ${m.pageTitle}\nURL: ${m.pageUrl}\n<page_content>\n${m.pageText}\n</page_content>\n\n")
                            }
                        } else {
                            note.append("[Earlier the user shared \"${m.pageTitle}\"" + (m.pageUrl?.let { " ($it)" } ?: "") + "; its text isn't re-sent.]\n")
                        }
                    }
                    if (m.images.isNotEmpty() && !keepMedia) {
                        note.append("[The user shared ${m.images.size} photo(s) here earlier; they aren't re-sent.]\n")
                    }
                    val body = m.text.ifBlank {
                        when {
                            m.images.isNotEmpty() -> "(see the attached photo)"
                            m.pageSource != null -> "What's this about?"
                            else -> ""
                        }
                    }
                    val text = shared.toString() + note.toString() + body
                    sharedChars += shared.length
                    textChars += note.length + body.length
                    val photos = if (keepMedia) m.images.mapNotNull { ImageUtil.toDataUrl(it) } else emptyList()
                    images += photos.size
                    out += buildJsonObject {
                        put("role", "user")
                        if (photos.isEmpty()) put("content", text)
                        else putJsonArray("content") {
                            addJsonObject { put("type", "text"); put("text", text) }
                            photos.forEach { url ->
                                addJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", url) } }
                            }
                        }
                    }
                }
                "assistant" -> {
                    if (m.text.isBlank() && m.apiTrail.isEmpty()) return@forEachIndexed
                    var content = (m.finalContent ?: m.text).ifBlank { "(no reply)" }
                    if (i == lastAssistant && m.apiTrail.isNotEmpty()) {
                        out.addAll(m.apiTrail)
                        // Re-sent web results count as "web" in the breakdown.
                        webChars += m.apiTrail.sumOf { it.toString().length }
                    } else if (m.sources.isNotEmpty()) {
                        content += "\n\n(Sources used: " + m.sources.joinToString(", ") { it.url } + ")"
                    }
                    textChars += content.length
                    out += buildJsonObject { put("role", "assistant"); put("content", content) }
                }
            }
        }
        return BuiltHistory(out, textChars, sharedChars, webChars, images)
    }

    companion object {
        const val MAX_IMAGES = 4
        const val MAX_PAGE_CHARS = 30_000
        const val MAX_TOOL_ROUNDS = 4
        /** Photos and shared pages are re-sent only for this many of the latest user messages. */
        const val KEEP_MEDIA_TURNS = 4
        private const val PHOTO_TOKENS = 1200L
        private const val TOOL_DEF_TOKENS = 250
        /** Waits before re-sending a step after the connection dropped. */
        private val NET_RETRY_DELAYS = longArrayOf(2_000, 5_000, 10_000)
        private val RETRY_HTTP_CODES = setOf(408, 500, 502, 503, 504, 520, 522, 524, 529)
    }
}
