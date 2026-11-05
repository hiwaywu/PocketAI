package com.pocketai.chat.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.net.URLEncoder

@Serializable
data class Bookmark(val title: String, val url: String)

/** One browser tab: its own GeckoSession (Firefox engine) and page state. */
class BrowserTab(val id: Long, val session: GeckoSession) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    /** Restored tabs load their page only when first shown. */
    var pendingUrl: String? = null
    /** Connection to the Pocket AI bridge add-on's script in this tab's current page. */
    var port: WebExtension.Port? = null
}

fun hostLabel(url: String): String = runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")

/**
 * The in-app browser, on GeckoView (Firefox's engine) so it runs Firefox add-ons. uBlock Origin (from
 * addons.mozilla.org) and Bypass Paywalls Clean (bundled .xpi) are installed on first use; more add-ons can be
 * added from the Add-ons screen. A small built-in
 * add-on (assets/extensions/pocketai) reads the page's text for the AI and clears stuck popups.
 * Tabs (one GeckoSession each) and bookmarks are saved on the phone.
 */
class BrowserController(private val context: Context, private val runtime: GeckoRuntime) {
    private val prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1L
    private var nextMessageId = 1
    private val replies = mutableMapOf<Int, (JSONObject?) -> Unit>()

    val tabs = mutableStateListOf<BrowserTab>()
    var currentIndex by mutableIntStateOf(0)
        private set
    val current: BrowserTab get() = tabs[currentIndex.coerceIn(0, tabs.lastIndex)]

    // The current tab's page, for the rest of the app.
    val url: String get() = current.url
    val title: String get() = current.title
    val progress: Int get() = current.progress
    val canGoBack: Boolean get() = current.canGoBack
    val canGoForward: Boolean get() = current.canGoForward

    val bookmarks = mutableStateListOf<Bookmark>()
    /** Installed (non-built-in) add-ons, e.g. uBlock Origin. */
    val addons = mutableStateListOf<WebExtension>()
    var addonBusy by mutableStateOf<String?>(null)
        private set
    private var bridge: WebExtension? = null

    var desktop by mutableStateOf(prefs.getBoolean("desktop", false))
        private set
    /** Page text size in percent (Firefox's font size factor; the phone's font size isn't applied on top). */
    var textZoom by mutableIntStateOf(prefs.getInt("textZoom", 100))
        private set

    var findMatches by mutableIntStateOf(0)
        private set
    var findIndex by mutableIntStateOf(0)
        private set

    /** The single GeckoView on screen; the current tab's session is attached to it. */
    var view: GeckoView? = null
        set(v) { field = v; attachCurrent() }

    val adBlock: Boolean get() = addons.firstOrNull { it.id == UBLOCK_ID }?.metaData?.enabled == true

    init {
        runtime.settings.setAutomaticFontSizeAdjustment(false)
        runtime.settings.setFontSizeFactor(textZoom / 100f)
        setUpAddons()
        val saved = prefs.getString("bookmarks", null)?.let { runCatching { json.decodeFromString<List<Bookmark>>(it) }.getOrNull() }
        bookmarks.addAll(saved ?: DEFAULT_BOOKMARKS)
        val savedTabs = prefs.getString("tabs", null)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()
        savedTabs.forEach { u -> tabs += createTab().also { t -> if (u.isNotBlank()) { t.pendingUrl = u; t.url = u } } }
        if (tabs.isEmpty()) tabs += createTab()
        currentIndex = prefs.getInt("tab", 0).coerceIn(0, tabs.lastIndex)
        loadPending(current)
    }

    // ------------------------------------------------------------------ add-ons

    private fun setUpAddons() {
        val controller = runtime.webExtensionController
        // Add-ons are only installed when the user asks (or the first-run ones), so accept their prompts.
        controller.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollection: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> =
                GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, false, false))

            override fun onUpdatePrompt(
                extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollection: Array<String>,
            ): GeckoResult<AllowOrDeny> = GeckoResult.allow()

            override fun onOptionalPrompt(
                extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollection: Array<String>,
            ): GeckoResult<AllowOrDeny> = GeckoResult.allow()
        })
        controller.ensureBuiltIn(BRIDGE_URL, BRIDGE_ID).accept(
            { ext -> if (ext != null) { bridge = ext; tabs.forEach { connectBridge(it) } } },
            { e -> Log.w(TAG, "Bridge add-on failed", e) },
        )
        refreshAddons(installFirstRun = true)
    }

    /** [installFirstRun]: install the [FIRST_RUN_ADDONS] that were never installed (a removed one stays removed). */
    fun refreshAddons(installFirstRun: Boolean = false) {
        runtime.webExtensionController.list().accept({ list ->
            val all = list.orEmpty()
            addons.clear()
            addons.addAll(all.filter { !it.isBuiltIn }.sortedBy { it.metaData.name.orEmpty().lowercase() })
            for (a in FIRST_RUN_ADDONS) {
                val markInstalled = { prefs.edit().putBoolean(a.pref, true).apply() }
                when {
                    all.any { it.id == a.id } -> markInstalled()
                    installFirstRun && !prefs.getBoolean(a.pref, false) -> install(a.xpi, quiet = true, onDone = markInstalled)
                }
            }
        }, { e -> Log.w(TAG, "Listing add-ons failed", e) })
    }

    /** Installs from an addons.mozilla.org page link, an add-on name, or a direct .xpi link. */
    fun installFromInput(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val xpi = when {
            t.endsWith(".xpi", ignoreCase = true) -> t
            t.contains("addons.mozilla.org") -> {
                val slug = Regex("""/addon/([^/?#]+)""").find(t)?.groupValues?.get(1)
                if (slug == null) { toast("That link doesn't look like an add-on page."); return }
                "https://addons.mozilla.org/firefox/downloads/latest/$slug/latest.xpi"
            }
            else -> "https://addons.mozilla.org/firefox/downloads/latest/" +
                t.lowercase().replace(Regex("""\s+"""), "-") + "/latest.xpi"
        }
        install(xpi, quiet = false) {}
    }

    private fun install(xpi: String, quiet: Boolean, onDone: () -> Unit) {
        addonBusy = "Installing…"
        runtime.webExtensionController.install(xpi).accept({ ext ->
            addonBusy = null
            if (!quiet) toast("Installed ${ext?.metaData?.name ?: "add-on"}")
            onDone()
            refreshAddons()
        }, { e ->
            val asset = xpi.removePrefix(ASSETS_URL)
            if (asset != xpi) {
                // A bundled .xpi: if Gecko can't read it from inside the APK, copy it out and install that file.
                Log.w(TAG, "Installing $asset from the APK failed; trying a copy", e)
                val copy = runCatching {
                    File(context.cacheDir, asset.substringAfterLast('/')).also { f ->
                        context.assets.open(asset).use { input -> f.outputStream().use { input.copyTo(it) } }
                    }
                }.getOrNull()
                if (copy != null) { install(Uri.fromFile(copy).toString(), quiet, onDone); return@accept }
            }
            addonBusy = null
            toast("Couldn't install the add-on: ${e?.message ?: "unknown error"}")
        })
    }

    fun setAddonEnabled(ext: WebExtension, on: Boolean) {
        val c = runtime.webExtensionController
        val r = if (on) c.enable(ext, WebExtensionController.EnableSource.USER) else c.disable(ext, WebExtensionController.EnableSource.USER)
        r.accept({ refreshAddons(); if (url.isNotBlank()) current.session.reload() }, { e -> toast("Failed: ${e?.message}") })
    }

    fun uninstallAddon(ext: WebExtension) {
        runtime.webExtensionController.uninstall(ext).accept({ refreshAddons() }, { e -> toast("Failed: ${e?.message}") })
    }

    /** The add-on's own settings page (e.g. uBlock Origin's dashboard), opened in a new tab. */
    fun openAddonSettings(ext: WebExtension) {
        val page = ext.metaData.optionsPageUrl
        if (page.isNullOrBlank()) toast("${ext.metaData.name} has no settings page.") else newTab(page)
    }

    /** ⋮ → Block ads: turns uBlock Origin on/off (installs it if it was removed). */
    fun setAdBlocking(on: Boolean) {
        val ubo = addons.firstOrNull { it.id == UBLOCK_ID }
        if (ubo == null) { if (on) install(UBLOCK_XPI, quiet = false) {} } else setAddonEnabled(ubo, on)
    }

    private fun toast(text: String) = main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }

    private fun connectBridge(tab: BrowserTab) {
        val ext = bridge ?: return
        tab.session.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                tab.port = port
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        val obj = message as? JSONObject ?: return
                        replies.remove(obj.optInt("id"))?.invoke(obj)
                    }

                    override fun onDisconnect(port: WebExtension.Port) {
                        if (tab.port === port) tab.port = null
                    }
                })
            }
        }, "browser")
    }

    /** Sends a command to the bridge script in the current page; [onReply] gets null if it doesn't answer. */
    private fun ask(cmd: String, onReply: (JSONObject?) -> Unit) {
        val port = current.port ?: return onReply(null)
        val id = nextMessageId++
        replies[id] = onReply
        runCatching { port.postMessage(JSONObject().put("cmd", cmd).put("id", id)) }.onFailure { replies.remove(id)?.invoke(null) }
        main.postDelayed({ replies.remove(id)?.invoke(null) }, 6000)
    }

    // ------------------------------------------------------------------ tabs

    private fun sessionSettings(): GeckoSessionSettings = GeckoSessionSettings.Builder()
        .userAgentMode(if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
        .viewportMode(if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        .build()

    /** [open] = false for sessions Gecko opens itself (pages opening a new window). */
    private fun createTab(open: Boolean = true): BrowserTab {
        val session = GeckoSession(sessionSettings())
        val tab = BrowserTab(nextId++, session)
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                s: GeckoSession, u: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean,
            ) {
                tab.url = if (u == null || u == "about:blank") "" else u
                saveTabs()
            }

            override fun onCanGoBack(s: GeckoSession, canGoBack: Boolean) { tab.canGoBack = canGoBack }
            override fun onCanGoForward(s: GeckoSession, canGoForward: Boolean) { tab.canGoForward = canGoForward }

            override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val scheme = Uri.parse(request.uri).scheme?.lowercase()
                if (scheme in WEB_SCHEMES) return GeckoResult.allow()
                // intent:, market:, tel:, mailto: etc. go to the matching app.
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return GeckoResult.deny()
            }

            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // Links that open a new window become a new tab.
                val t = createTab(open = false)
                tabs += t
                // Gecko opens the returned session itself; show it once that has happened.
                main.post { tabs.indexOf(t).takeIf { it >= 0 }?.let { switchTo(it) } }
                return GeckoResult.fromValue(t.session)
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, u: String) { tab.progress = 5 }
            override fun onProgressChange(s: GeckoSession, p: Int) { tab.progress = p }
            override fun onPageStop(s: GeckoSession, success: Boolean) { tab.progress = 100; saveTabs() }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, t: String?) { tab.title = t.orEmpty() }

            override fun onExternalResponse(s: GeckoSession, response: WebResponse) {
                // Downloads go to the phone's browser/download manager.
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(response.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }

            override fun onContextMenu(s: GeckoSession, x: Int, y: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                val link = element.linkUri ?: element.srcUri ?: return
                linkMenu(link)
            }

            override fun onCloseRequest(s: GeckoSession) {
                tabs.indexOf(tab).takeIf { it >= 0 }?.let { closeTab(it) }
            }

            override fun onCrash(s: GeckoSession) = reopen(tab)
            override fun onKill(s: GeckoSession) = reopen(tab)
        }
        session.promptDelegate = Prompts()
        connectBridge(tab)
        if (open) session.open(runtime)
        return tab
    }

    private fun reopen(tab: BrowserTab) {
        if (!tab.session.isOpen) tab.session.open(runtime)
        if (tab.url.isNotBlank()) tab.session.loadUri(tab.url)
    }

    private fun attachCurrent() {
        val v = view ?: return
        val tab = tabs.getOrNull(currentIndex) ?: return
        if (v.session !== tab.session) {
            v.releaseSession()
            v.setSession(tab.session)
        }
        tabs.forEach { if (it.session.isOpen) it.session.setActive(it === tab) }
    }

    private fun loadPending(tab: BrowserTab) {
        tab.pendingUrl?.let { tab.pendingUrl = null; tab.session.loadUri(it) }
    }

    private fun saveTabs() {
        prefs.edit()
            .putString("tabs", json.encodeToString(tabs.map { it.pendingUrl ?: it.url }))
            .putInt("tab", currentIndex)
            .apply()
    }

    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            Regex("""^[a-z][a-z0-9+.-]*://""", RegexOption.IGNORE_CASE).containsMatchIn(t) || t.startsWith("about:") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        current.pendingUrl = null
        current.url = target
        current.session.loadUri(target)
    }

    /** Links from the chat open in a new tab, unless the current tab is empty. */
    fun openFromChat(link: String) {
        if (url.isBlank()) go(link) else newTab(link)
    }

    fun newTab(link: String? = null) {
        tabs += createTab()
        switchTo(tabs.lastIndex)
        link?.let { go(it) }
        saveTabs()
    }

    fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        clearFind()
        currentIndex = index
        loadPending(current)
        attachCurrent()
        saveTabs()
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs.removeAt(index)
        if (view?.session === tab.session) view?.releaseSession()
        tab.session.close()
        if (tabs.isEmpty()) tabs += createTab()
        currentIndex = if (currentIndex > index) currentIndex - 1 else currentIndex.coerceAtMost(tabs.lastIndex)
        loadPending(current)
        attachCurrent()
        saveTabs()
    }

    fun goBack() = current.session.goBack()
    fun goForward() = current.session.goForward()
    fun reload() = current.session.reload()
    fun stop() = current.session.stop()

    // ------------------------------------------------------------------ bookmarks & settings

    val isBookmarked: Boolean get() = url.isNotBlank() && bookmarks.any { it.url == url }

    fun toggleBookmark() {
        if (url.isBlank()) return
        val existing = bookmarks.firstOrNull { it.url == url }
        if (existing != null) bookmarks.remove(existing)
        else bookmarks += Bookmark(title.ifBlank { hostLabel(url) }.take(80), url)
        saveBookmarks()
    }

    fun removeBookmark(b: Bookmark) {
        bookmarks.remove(b)
        saveBookmarks()
    }

    private fun saveBookmarks() = prefs.edit().putString("bookmarks", json.encodeToString(bookmarks.toList())).apply()

    fun setDesktopMode(on: Boolean) {
        desktop = on
        prefs.edit().putBoolean("desktop", on).apply()
        tabs.forEach {
            it.session.settings.setUserAgentMode(if (on) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            it.session.settings.setViewportMode(if (on) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        }
        if (url.isNotBlank()) reload()
    }

    fun changeTextZoom(percent: Int) {
        textZoom = percent
        prefs.edit().putInt("textZoom", percent).apply()
        runtime.settings.setFontSizeFactor(percent / 100f)
    }

    fun find(query: String) {
        if (query.isEmpty()) { clearFind(); return }
        current.session.finder.find(query, 0).accept { r -> if (r != null) { findIndex = (r.current - 1).coerceAtLeast(0); findMatches = r.total } }
    }

    fun findNext(forward: Boolean) {
        current.session.finder.find(null, if (forward) 0 else GeckoSession.FINDER_FIND_BACKWARDS)
            .accept { r -> if (r != null) { findIndex = (r.current - 1).coerceAtLeast(0); findMatches = r.total } }
    }

    fun clearFind() {
        tabs.getOrNull(currentIndex)?.session?.finder?.clear()
        findMatches = 0
        findIndex = 0
    }

    /** ⋮ → Unblock page: removes stuck popups/backdrops and turns scrolling back on. */
    fun unblockPage() {
        if (url.isBlank()) return
        ask("unblock") { r -> if (r == null) toast("This page can't be changed (it may still be loading).") }
    }

    fun share(ctx: Context) {
        if (url.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, title)
        runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
    }

    fun copyLink(ctx: Context, link: String = url) {
        if (link.isBlank()) return
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Link", link))
    }

    fun openExternally(ctx: Context) {
        if (url.isBlank()) return
        runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), null)) }
    }

    private fun linkMenu(link: String) {
        AlertDialog.Builder(context)
            .setTitle(link.take(120))
            .setItems(arrayOf("Open in new tab", "Copy link", "Share link")) { _, which ->
                when (which) {
                    0 -> newTab(link)
                    1 -> copyLink(context, link)
                    2 -> runCatching {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
                    }
                }
            }
            .show()
    }

    /**
     * Text of the open page for the AI, from the bridge add-on. On article pages only the article itself
     * (no menus, sidebars or "other headlines", which got mixed into summaries before, and far fewer tokens);
     * on other pages (search results, feeds, forums) all visible text.
     */
    fun extractPage(onResult: (String) -> Unit) {
        ask("extract") { obj ->
            if (obj == null) return@ask onResult("")
            val body = obj.optString("text")
            val published = obj.optString("published")
            onResult(
                if (!obj.optBoolean("article")) body
                else "(Main article text only: menus, sidebars and other headlines on the page were left out.)\n" +
                    (if (published.isNotBlank()) "Published: $published\n" else "") + "\n" + body
            )
        }
    }

    fun destroy() {
        view?.releaseSession()
        view = null
        tabs.forEach { runCatching { it.session.close() } }
        tabs.clear()
    }

    // ------------------------------------------------------------------ page dialogs (alert, confirm, <select>…)

    private inner class Prompts : PromptDelegate {
        override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            AlertDialog.Builder(context)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ -> r.complete(prompt.dismiss()) }
                .setOnCancelListener { r.complete(prompt.dismiss()) }
                .show()
            return r
        }

        override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            AlertDialog.Builder(context)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ -> r.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> r.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                .setOnCancelListener { r.complete(prompt.dismiss()) }
                .show()
            return r
        }

        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            val input = EditText(context).apply { setText(prompt.defaultValue.orEmpty()) }
            AlertDialog.Builder(context)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> r.complete(prompt.confirm(input.text.toString())) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> r.complete(prompt.dismiss()) }
                .setOnCancelListener { r.complete(prompt.dismiss()) }
                .show()
            return r
        }

        /** Drop-down lists (<select>) and similar menus. */
        override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
            val r = GeckoResult<PromptResponse>()
            var answered = false
            fun answer(resp: PromptResponse) { if (!answered) { answered = true; r.complete(resp) } }
            val choices = prompt.choices.flatMap { c -> if (c.items != null) c.items!!.toList() else listOf(c) }
                .filter { !it.separator && !it.disabled }
            val labels = choices.map { it.label }.toTypedArray()
            val b = AlertDialog.Builder(context).setTitle(prompt.title ?: prompt.message)
            if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
                val checked = choices.map { it.selected }.toBooleanArray()
                b.setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        answer(prompt.confirm(choices.filterIndexed { i, _ -> checked[i] }.toTypedArray()))
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> answer(prompt.dismiss()) }
            } else {
                b.setSingleChoiceItems(labels, choices.indexOfFirst { it.selected }) { d, i ->
                    answer(prompt.confirm(choices[i]))
                    d.dismiss()
                }
            }
            b.setOnCancelListener { answer(prompt.dismiss()) }.show()
            return r
        }

        override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

        override fun onRepostConfirmPrompt(session: GeckoSession, prompt: PromptDelegate.RepostConfirmPrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
    }

    companion object {
        private const val TAG = "PocketBrowser"
        private const val BRIDGE_ID = "pocketai-bridge@pocketai"
        private const val BRIDGE_URL = "resource://android/assets/extensions/pocketai/"
        const val UBLOCK_ID = "uBlock0@raymondhill.net"
        const val BPC_ID = "magnolia@12.34"
        private const val UBLOCK_XPI = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
        private const val ASSETS_URL = "resource://android/assets/"

        /** An add-on installed once on first run; [pref] remembers it was, so one the user removes stays removed. */
        private class FirstRunAddon(val id: String, val xpi: String, val pref: String)

        private val FIRST_RUN_ADDONS = listOf(
            FirstRunAddon(UBLOCK_ID, UBLOCK_XPI, "uBlockInstalled"),
            // Bypass Paywalls Clean (MIT, signed by Mozilla for self-distribution; not on addons.mozilla.org), bundled
            // in assets/extensions. Newer versions: paste a direct .xpi link from its gitflic page in Add-ons.
            FirstRunAddon(BPC_ID, ASSETS_URL + "extensions/bypass_paywalls_clean.xpi", "bpcInstalled"),
        )
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "file", "moz-extension", "resource", "javascript", "view-source")

        private val DEFAULT_BOOKMARKS = listOf(
            Bookmark("Google", "https://www.google.com"),
            Bookmark("YouTube", "https://m.youtube.com"),
            Bookmark("News", "https://news.google.com"),
            Bookmark("ESPN", "https://www.espn.com"),
            Bookmark("Wikipedia", "https://en.m.wikipedia.org"),
            Bookmark("Reddit", "https://www.reddit.com"),
        )
    }
}

@Composable
fun BrowserPane(
    b: BrowserController,
    busy: Boolean,
    onAsk: (question: String) -> Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var finding by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showTextSize by remember { mutableStateOf(false) }
    var showAddons by remember { mutableStateOf(false) }

    BackHandler(enabled = finding) { finding = false; b.clearFind() }
    BackHandler(enabled = !finding && b.canGoBack) { b.goBack() }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        if (finding) {
            FindBar(b, onClose = { finding = false; b.clearFind() })
        } else {
            AddressBar(
                b,
                onTabs = { showTabs = true },
                onBookmarks = { showBookmarks = true },
                onFind = { finding = true },
                onTextSize = { showTextSize = true },
                onAddons = { b.refreshAddons(); showAddons = true },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // One GeckoView for all tabs; the current tab's session is attached to it.
            AndroidView(
                factory = { ctx -> GeckoView(ctx).also { b.view = it } },
                onRelease = { if (b.view === it) b.view = null },
                modifier = Modifier.fillMaxSize(),
            )
            LaunchedEffect(b.currentIndex, b.tabs.size) { b.view?.let { b.view = it } }
            if (b.current.url.isBlank()) StartPage(b)
            if (b.progress in 1..99) {
                LinearProgressIndicator(progress = { b.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
        AskBar(hasPage = b.url.isNotBlank(), busy = busy, onAsk = onAsk, onStop = onStop)
    }

    if (showTabs) TabsDialog(b, onDismiss = { showTabs = false })
    if (showBookmarks) BookmarksDialog(b, onDismiss = { showBookmarks = false })
    if (showTextSize) TextSizeDialog(b, onDismiss = { showTextSize = false })
    if (showAddons) AddonsDialog(b, onDismiss = { showAddons = false })
}

/** Chrome-style bar: back, address pill (tap selects the whole address), tab count, menu. */
@Composable
private fun AddressBar(
    b: BrowserController,
    onTabs: () -> Unit,
    onBookmarks: () -> Unit,
    onFind: () -> Unit,
    onTextSize: () -> Unit,
    onAddons: () -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var menu by remember { mutableStateOf(false) }
    val host = hostLabel(b.url)
    val secure = b.url.startsWith("https://")

    // Select the whole address once the tap that focused the field has placed its cursor,
    // so typing replaces it.
    LaunchedEffect(editing) {
        if (editing) {
            field = TextFieldValue(b.url, TextRange(0, b.url.length))
            delay(60)
            field = field.copy(selection = TextRange(0, field.text.length))
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 2.dp),
    ) {
        IconButton(onClick = { b.goBack() }, enabled = b.canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f).height(40.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
                Icon(
                    when {
                        editing || b.url.isBlank() -> Icons.Filled.Search
                        secure -> Icons.Filled.Lock
                        else -> Icons.Filled.Public
                    },
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        // Shows just the site while browsing, the full address while editing.
                        value = if (editing) field else TextFieldValue(host),
                        onValueChange = { field = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { b.go(field.text); focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { editing = it.isFocused },
                    )
                    if (!editing && host.isEmpty()) {
                        Text(
                            "Search or type a web address",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (editing && field.text.isNotEmpty()) {
                    IconButton(onClick = { field = TextFieldValue("") }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, "Clear", Modifier.size(18.dp))
                    }
                }
            }
        }
        // Tab count, like Chrome.
        Box(
            Modifier.padding(start = 6.dp).size(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onTabs),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(22.dp).border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    b.tabs.size.let { if (it > 99) ":D" else it.toString() },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // Top row like Chrome: forward, bookmark, reload/stop.
                Row(Modifier.padding(horizontal = 8.dp)) {
                    IconButton(onClick = { b.goForward(); menu = false }, enabled = b.canGoForward) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
                    }
                    IconButton(onClick = { b.toggleBookmark() }, enabled = b.url.isNotBlank()) {
                        if (b.isBookmarked) Icon(Icons.Filled.Star, "Remove bookmark", tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.Outlined.StarBorder, "Add bookmark")
                    }
                    if (b.progress in 1..99) {
                        IconButton(onClick = { b.stop(); menu = false }) { Icon(Icons.Filled.Stop, "Stop loading") }
                    } else {
                        IconButton(onClick = { b.reload(); menu = false }, enabled = b.url.isNotBlank()) {
                            Icon(Icons.Filled.Refresh, "Reload")
                        }
                    }
                }
                HorizontalDivider()
                MenuItem("New tab", Icons.Filled.Add) { menu = false; b.newTab() }
                MenuItem("Bookmarks", Icons.Filled.Bookmarks) { menu = false; onBookmarks() }
                MenuItem("Add-ons", Icons.Filled.Extension) { menu = false; onAddons() }
                MenuItem("Find in page", Icons.Filled.Search, enabled = b.url.isNotBlank()) { menu = false; onFind() }
                MenuItem("Share", Icons.Filled.Share, enabled = b.url.isNotBlank()) { menu = false; b.share(context) }
                MenuItem("Copy link", Icons.Filled.ContentCopy, enabled = b.url.isNotBlank()) { menu = false; b.copyLink(context) }
                MenuItem("Open in another browser", Icons.AutoMirrored.Filled.OpenInNew, enabled = b.url.isNotBlank()) {
                    menu = false; b.openExternally(context)
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Desktop site") },
                    leadingIcon = { Icon(Icons.Filled.DesktopWindows, null) },
                    trailingIcon = { Checkbox(checked = b.desktop, onCheckedChange = null) },
                    onClick = { b.setDesktopMode(!b.desktop) },
                )
                DropdownMenuItem(
                    text = { Text("Block ads (uBlock Origin)") },
                    leadingIcon = { Icon(Icons.Filled.Block, null) },
                    trailingIcon = { Checkbox(checked = b.adBlock, onCheckedChange = null) },
                    onClick = { b.setAdBlocking(!b.adBlock) },
                )
                MenuItem("Text size (${b.textZoom}%)", Icons.Filled.FormatSize) { menu = false; onTextSize() }
                MenuItem("Unblock page (stuck popup)", Icons.Filled.LockOpen, enabled = b.url.isNotBlank()) { menu = false; b.unblockPage() }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, enabled = enabled, onClick = onClick)
}

@Composable
private fun FindBar(b: BrowserController, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f).height(40.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it; b.find(it) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { b.findNext(true) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    if (query.isEmpty()) {
                        Text("Find in page", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (query.isNotEmpty()) Text(
                    if (b.findMatches == 0) "0/0" else "${b.findIndex + 1}/${b.findMatches}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { b.findNext(false) }, enabled = b.findMatches > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Previous") }
        IconButton(onClick = { b.findNext(true) }, enabled = b.findMatches > 0) { Icon(Icons.Filled.KeyboardArrowDown, "Next") }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close find") }
    }
}

/** Type a question about the open page (or anything, when no page is open); it goes to the chat. */
@Composable
private fun AskBar(hasPage: Boolean, busy: Boolean, onAsk: (String) -> Boolean, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun submit() {
        if (text.isNotBlank() && onAsk(text.trim())) { text = ""; focus.clearFocus() }
    }
    Surface(tonalElevation = 3.dp) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(if (hasPage) "Ask about this page…" else "Ask anything…") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            if (busy) {
                FilledIconButton(onClick = onStop, modifier = Modifier.padding(bottom = 4.dp)) { Icon(Icons.Filled.Stop, "Stop") }
            } else {
                FilledIconButton(onClick = ::submit, enabled = text.isNotBlank(), modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

/** New-tab page: bookmarks as tiles. Long-press a tile to open it in a new tab or remove it. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun StartPage(b: BrowserController) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (b.bookmarks.isEmpty()) {
            Text(
                "Search or type an address above. Bookmarks you add (⋮ → ☆) show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            maxItemsInEachRow = 4,
        ) {
            b.bookmarks.forEach { bm ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(onClick = { b.go(bm.url) }, onLongClick = { menu = true })
                            .padding(vertical = 6.dp),
                    ) {
                        LetterIcon(bm.title.ifBlank { hostLabel(bm.url) }, 48)
                        Spacer(Modifier.height(6.dp))
                        Text(bm.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Open in new tab") }, onClick = { menu = false; b.newTab(bm.url) })
                        DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; b.removeBookmark(bm) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LetterIcon(label: String, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.trim().take(1).uppercase().ifEmpty { "•" },
            style = if (sizeDp >= 40) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun TabsDialog(b: BrowserController, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${b.tabs.size} tab${if (b.tabs.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { b.newTab(); onDismiss() }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New tab")
                    }
                }
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(b.tabs, key = { _, t -> t.id }) { i, t ->
                        val selected = i == b.currentIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent)
                                .clickable { b.switchTo(i); onDismiss() }
                                .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            LetterIcon(t.title.ifBlank { hostLabel(t.url) }, 32)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.title.ifBlank { if (t.url.isBlank()) "New tab" else hostLabel(t.url) },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else null,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (t.url.isNotBlank()) Text(
                                    hostLabel(t.url),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { b.closeTab(i) }) { Icon(Icons.Filled.Close, "Close tab") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksDialog(b: BrowserController, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks") },
        text = {
            if (b.bookmarks.isEmpty()) {
                Text("No bookmarks yet. Open a page, then ⋮ → ☆ to add it.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(b.bookmarks, key = { _, bm -> bm.url }) { _, bm ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { b.go(bm.url); onDismiss() }.padding(vertical = 6.dp),
                        ) {
                            LetterIcon(bm.title.ifBlank { hostLabel(bm.url) }, 32)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(bm.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(hostLabel(bm.url), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { b.removeBookmark(bm) }) { Icon(Icons.Outlined.Delete, "Remove bookmark") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Firefox add-ons: on/off, settings, remove, and install more from addons.mozilla.org. */
@Composable
private fun AddonsDialog(b: BrowserController, onDismiss: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add-ons") },
        text = {
            Column {
                if (b.addons.isEmpty()) {
                    Text(b.addonBusy ?: "No add-ons yet.", style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(b.addons, key = { _, e -> e.id }) { _, ext ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(ext.metaData.name ?: ext.id, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "v" + ext.metaData.version + when (ext.id) {
                                        BrowserController.UBLOCK_ID -> " · ad blocker"
                                        BrowserController.BPC_ID -> " · paywalls"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { b.openAddonSettings(ext); onDismiss() }) { Icon(Icons.Filled.Settings, "Add-on settings") }
                            IconButton(onClick = { b.uninstallAddon(ext) }) { Icon(Icons.Outlined.Delete, "Remove add-on") }
                            Switch(checked = ext.metaData.enabled, onCheckedChange = { b.setAddonEnabled(ext, it) })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Add from addons.mozilla.org: paste the add-on's page link, or type its name (e.g. \"dark reader\"). " +
                        "A direct .xpi link works too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text("Add-on link or name") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { b.installFromInput(input); input = "" }, enabled = input.isNotBlank() && b.addonBusy == null) {
                        Text("Add")
                    }
                }
                b.addonBusy?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TextSizeDialog(b: BrowserController, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(b.textZoom.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text size: ${value.toInt()}%") },
        text = {
            Column {
                Text("Changes the size of text on web pages.", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { b.changeTextZoom(value.toInt()) },
                    valueRange = 50f..200f,
                    steps = 14,
                )
            }
        },
        confirmButton = { TextButton(onClick = { b.changeTextZoom(value.toInt()); onDismiss() }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { value = 100f; b.changeTextZoom(100) }) { Text("Reset") } },
    )
}
