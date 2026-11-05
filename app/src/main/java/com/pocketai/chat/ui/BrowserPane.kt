package com.pocketai.chat.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/** Owns the in-app browser's WebView so it survives tab switches and folding/unfolding. */
@SuppressLint("SetJavaScriptEnabled")
class BrowserController(context: Context) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        // The default WebView user agent says "; wv" and "Version/4.0", which makes Google and others
        // serve stripped-down pages. Present as regular mobile Chrome (the same engine) instead.
        settings.userAgentString = settings.userAgentString
            .replace("; wv)", ")")
            .replace(Regex("""Version/\d+(\.\d+)* """), "")
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                pageUrl(u.orEmpty())
            }

            override fun onPageFinished(view: WebView, u: String?) {
                pageUrl(u.orEmpty()); pageTitle(view.title.orEmpty())
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                u?.let { pageUrl(it) }
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
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { this@BrowserController.progress = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { pageTitle(t.orEmpty()) }
        }
    }

    private fun pageUrl(u: String) { url = u; updateNav() }
    private fun pageTitle(t: String) { title = t }

    private fun updateNav() {
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        url = target
        webView.loadUrl(target)
    }

    /** Reads the visible text of the open page (works on JavaScript-heavy pages too). */
    fun extractPage(onResult: (String) -> Unit) {
        webView.evaluateJavascript("(function(){return document.body ? document.body.innerText : ''})()") { res ->
            onResult(runCatching { Json.decodeFromString<String>(res) }.getOrDefault(""))
        }
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
    BackHandler(enabled = b.canGoBack) { b.webView.goBack() }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        AddressBar(b)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The WebView is only attached once a page is open; an empty, never-loaded
            // WebView can stop the rest of the screen from drawing.
            if (b.url.isBlank()) {
                StartPage(onGo = { b.go(it) })
            } else {
                AndroidView(
                    factory = {
                        (b.webView.parent as? ViewGroup)?.removeView(b.webView)
                        b.webView
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (b.progress in 1..99) {
                LinearProgressIndicator(progress = { b.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
        AskBar(hasPage = b.url.isNotBlank(), busy = busy, onAsk = onAsk, onStop = onStop)
    }
}

/** Compact Chrome-style bar: back/forward, a slim address pill showing the site, reload/stop. */
@Composable
private fun AddressBar(b: BrowserController) {
    val focus = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue(b.url)) }
    val host = remember(b.url) { runCatching { Uri.parse(b.url).host.orEmpty().removePrefix("www.") }.getOrDefault("") }
    val secure = b.url.startsWith("https://")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 2.dp),
    ) {
        IconButton(onClick = { b.webView.goBack() }, enabled = b.canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        if (b.canGoForward) {
            IconButton(onClick = { b.webView.goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f).height(40.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
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
                        modifier = Modifier.fillMaxWidth().onFocusChanged {
                            if (it.isFocused && !editing) field = TextFieldValue(b.url, TextRange(0, b.url.length))
                            editing = it.isFocused
                        },
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
            }
        }
        if (b.progress in 1..99) {
            IconButton(onClick = { b.webView.stopLoading() }) { Icon(Icons.Filled.Close, "Stop loading") }
        } else {
            IconButton(onClick = { b.webView.reload() }, enabled = b.url.isNotBlank()) { Icon(Icons.Filled.Refresh, "Reload") }
        }
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

/** Shown before any page is open: site shortcuts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartPage(onGo: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            maxItemsInEachRow = 4,
        ) {
            listOf(
                "Google" to "https://www.google.com",
                "YouTube" to "https://m.youtube.com",
                "News" to "https://news.google.com",
                "ESPN" to "https://www.espn.com",
                "Wikipedia" to "https://en.m.wikipedia.org",
                "Reddit" to "https://www.reddit.com",
            ).forEach { (label, link) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(68.dp).clip(RoundedCornerShape(12.dp)).clickable { onGo(link) }.padding(vertical = 6.dp),
                ) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label.take(1),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}
