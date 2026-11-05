package com.pocketai.chat.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import java.io.ByteArrayInputStream
import java.net.URLEncoder

@Serializable
data class Bookmark(val title: String, val url: String)

/** One browser tab: its own WebView and page state. */
class BrowserTab(val id: Long, val webView: WebView) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    /** Restored tabs load their page only when first shown. */
    var pendingUrl: String? = null
}

fun hostLabel(url: String): String = runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")

/**
 * The in-app browser: tabs (each its own WebView, kept across tab switches and folding), bookmarks,
 * ad blocking, desktop mode, text size and find-in-page. Tabs and bookmarks are saved on the phone.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserController(private val context: Context) {
    private val prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private var nextId = 1L

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
    val webView: WebView get() = current.webView

    val bookmarks = mutableStateListOf<Bookmark>()
    var adBlock by mutableStateOf(prefs.getBoolean("adBlock", true))
        private set
    @Volatile private var adBlockOn = adBlock
    var desktop by mutableStateOf(prefs.getBoolean("desktop", false))
        private set
    /** Page text size in percent. Fixed at 100 by default: following the phone's font size made text overflow its boxes. */
    var textZoom by mutableIntStateOf(prefs.getInt("textZoom", 100))
        private set

    var findMatches by mutableIntStateOf(0)
        private set
    var findIndex by mutableIntStateOf(0)
        private set

    init {
        AdBlocker.init(context)
        val saved = prefs.getString("bookmarks", null)?.let { runCatching { json.decodeFromString<List<Bookmark>>(it) }.getOrNull() }
        bookmarks.addAll(saved ?: DEFAULT_BOOKMARKS)
        val savedTabs = prefs.getString("tabs", null)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()
        savedTabs.forEach { u -> tabs += createTab().also { t -> if (u.isNotBlank()) { t.pendingUrl = u; t.url = u } } }
        if (tabs.isEmpty()) tabs += createTab()
        currentIndex = prefs.getInt("tab", 0).coerceIn(0, tabs.lastIndex)
        loadPending(current)
    }

    private fun createTab(): BrowserTab {
        val wv = WebView(context)
        val tab = BrowserTab(nextId++, wv)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            textZoom = this@BrowserController.textZoom
            userAgentString = userAgent(desktop)
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                tab.url = u.orEmpty(); updateNav(tab)
            }

            override fun onPageCommitVisible(view: WebView, u: String?) {
                if (adBlockOn) view.evaluateJavascript(AdBlocker.HIDE_ADS_JS, null)
            }

            override fun onPageFinished(view: WebView, u: String?) {
                tab.url = u.orEmpty(); tab.title = view.title.orEmpty(); updateNav(tab)
                if (adBlockOn) view.evaluateJavascript(AdBlocker.HIDE_ADS_JS, null)
                saveTabs()
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                u?.let { tab.url = it }; updateNav(tab)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Never block the page itself, only ads/trackers it loads.
                if (adBlockOn && !request.isForMainFrame && AdBlocker.isBlocked(request.url.host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                if (scheme == "http" || scheme == "https") return false
                // intent:, market:, tel: etc. go to the matching app.
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { tab.title = t.orEmpty() }

            // "Please allow ads on this site" popups (e.g. hk01.com) are dismissed silently; other
            // page popups still show as usual.
            override fun onJsAlert(view: WebView, u: String?, message: String?, result: android.webkit.JsResult): Boolean {
                if (!AdBlocker.isAdNag(message)) return false
                result.confirm(); return true
            }

            override fun onJsConfirm(view: WebView, u: String?, message: String?, result: android.webkit.JsResult): Boolean {
                if (!AdBlocker.isAdNag(message)) return false
                result.cancel(); return true
            }
        }
        wv.setFindListener { active, count, _ -> if (tab === current) { findIndex = active; findMatches = count } }
        return tab
    }

    /**
     * The default WebView user agent says "; wv" and "Version/4.0", which makes Google and others serve
     * stripped-down pages, so present as regular mobile Chrome (the same engine) — or desktop Chrome.
     */
    private fun userAgent(desktop: Boolean): String {
        val mobile = WebSettings.getDefaultUserAgent(context).replace("; wv)", ")").replace(Regex("""Version/\d+(\.\d+)* """), "")
        if (!desktop) return mobile
        val chrome = Regex("""Chrome/[\d.]+""").find(mobile)?.value ?: "Chrome/130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    private fun updateNav(tab: BrowserTab) {
        tab.canGoBack = tab.webView.canGoBack()
        tab.canGoForward = tab.webView.canGoForward()
    }

    private fun loadPending(tab: BrowserTab) {
        tab.pendingUrl?.let { tab.pendingUrl = null; tab.webView.loadUrl(it) }
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
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        current.pendingUrl = null
        current.url = target
        current.webView.loadUrl(target)
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
        saveTabs()
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs.removeAt(index)
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        if (tabs.isEmpty()) tabs += createTab()
        currentIndex = if (currentIndex > index) currentIndex - 1 else currentIndex.coerceAtMost(tabs.lastIndex)
        loadPending(current)
        saveTabs()
    }

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

    fun setAdBlocking(on: Boolean) {
        adBlock = on
        adBlockOn = on
        prefs.edit().putBoolean("adBlock", on).apply()
        if (url.isNotBlank()) webView.reload()
    }

    fun setDesktopMode(on: Boolean) {
        desktop = on
        prefs.edit().putBoolean("desktop", on).apply()
        val ua = userAgent(on)
        tabs.forEach { it.webView.settings.userAgentString = ua }
        if (url.isNotBlank()) webView.reload()
    }

    fun changeTextZoom(percent: Int) {
        textZoom = percent
        prefs.edit().putInt("textZoom", percent).apply()
        tabs.forEach { it.webView.settings.textZoom = percent }
    }

    fun find(query: String) {
        if (query.isEmpty()) clearFind() else webView.findAllAsync(query)
    }

    fun findNext(forward: Boolean) = webView.findNext(forward)

    fun clearFind() {
        webView.clearMatches()
        findMatches = 0
        findIndex = 0
    }

    fun share(ctx: Context) {
        if (url.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, title)
        runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
    }

    fun copyLink(ctx: Context) {
        if (url.isBlank()) return
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Link", url))
    }

    fun openExternally(ctx: Context) {
        if (url.isBlank()) return
        runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), null)) }
    }

    /** Reads the visible text of the open page (works on JavaScript-heavy pages too). */
    fun extractPage(onResult: (String) -> Unit) {
        webView.evaluateJavascript("(function(){return document.body ? document.body.innerText : ''})()") { res ->
            onResult(runCatching { Json.decodeFromString<String>(res) }.getOrDefault(""))
        }
    }

    fun destroy() {
        tabs.forEach { (it.webView.parent as? ViewGroup)?.removeView(it.webView); it.webView.destroy() }
        tabs.clear()
    }

    private companion object {
        val DEFAULT_BOOKMARKS = listOf(
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

    BackHandler(enabled = finding) { finding = false; b.clearFind() }
    BackHandler(enabled = !finding && b.canGoBack) { b.webView.goBack() }

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
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val tab = b.current
            // The WebView is only attached once a page is open; an empty, never-loaded
            // WebView can stop the rest of the screen from drawing.
            if (tab.url.isBlank()) {
                StartPage(b)
            } else {
                key(tab.id) {
                    AndroidView(
                        factory = {
                            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                            tab.webView
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (tab.progress in 1..99) {
                LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
        AskBar(hasPage = b.url.isNotBlank(), busy = busy, onAsk = onAsk, onStop = onStop)
    }

    if (showTabs) TabsDialog(b, onDismiss = { showTabs = false })
    if (showBookmarks) BookmarksDialog(b, onDismiss = { showBookmarks = false })
    if (showTextSize) TextSizeDialog(b, onDismiss = { showTextSize = false })
}

/** Chrome-style bar: back, address pill (tap selects the whole address), tab count, menu. */
@Composable
private fun AddressBar(
    b: BrowserController,
    onTabs: () -> Unit,
    onBookmarks: () -> Unit,
    onFind: () -> Unit,
    onTextSize: () -> Unit,
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
        IconButton(onClick = { b.webView.goBack() }, enabled = b.canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
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
                    IconButton(onClick = { b.webView.goForward(); menu = false }, enabled = b.canGoForward) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
                    }
                    IconButton(onClick = { b.toggleBookmark() }, enabled = b.url.isNotBlank()) {
                        if (b.isBookmarked) Icon(Icons.Filled.Star, "Remove bookmark", tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.Outlined.StarBorder, "Add bookmark")
                    }
                    if (b.progress in 1..99) {
                        IconButton(onClick = { b.webView.stopLoading(); menu = false }) { Icon(Icons.Filled.Stop, "Stop loading") }
                    } else {
                        IconButton(onClick = { b.webView.reload(); menu = false }, enabled = b.url.isNotBlank()) {
                            Icon(Icons.Filled.Refresh, "Reload")
                        }
                    }
                }
                HorizontalDivider()
                MenuItem("New tab", Icons.Filled.Add) { menu = false; b.newTab() }
                MenuItem("Bookmarks", Icons.Filled.Bookmarks) { menu = false; onBookmarks() }
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
                    text = { Text("Block ads") },
                    leadingIcon = { Icon(Icons.Filled.Block, null) },
                    trailingIcon = { Checkbox(checked = b.adBlock, onCheckedChange = null) },
                    onClick = { b.setAdBlocking(!b.adBlock) },
                )
                MenuItem("Text size (${b.textZoom}%)", Icons.Filled.FormatSize) { menu = false; onTextSize() }
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
