package com.pocketai.chat.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
fun BrowserPane(b: BrowserController, onAsk: (summarize: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    var field by remember { mutableStateOf(b.url) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(b.url, editing) { if (!editing) field = b.url }
    BackHandler(enabled = b.canGoBack) { b.webView.goBack() }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)) {
            IconButton(onClick = { b.webView.goBack() }, enabled = b.canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            IconButton(onClick = { b.webView.goForward() }, enabled = b.canGoForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                placeholder = { Text("Search or type a web address") },
                shape = RoundedCornerShape(24.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { b.go(field); focus.clearFocus() }),
                modifier = Modifier.weight(1f).onFocusChanged { editing = it.isFocused },
            )
            IconButton(onClick = { b.webView.reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
        }
        if (b.progress in 1..99) LinearProgressIndicator(progress = { b.progress / 100f }, modifier = Modifier.fillMaxWidth())
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
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { onAsk(true) }, enabled = b.url.isNotBlank(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Summarize page")
            }
            OutlinedButton(onClick = { onAsk(false) }, enabled = b.url.isNotBlank(), modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ask about page")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartPage(onGo: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Public, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(10.dp))
        Text("Browse the web", style = MaterialTheme.typography.titleLarge)
        Text(
            "Open any page, then tap Summarize or Ask about page.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            listOf(
                "Google" to "https://www.google.com",
                "News" to "https://news.google.com",
                "ESPN" to "https://www.espn.com",
                "YouTube" to "https://m.youtube.com",
                "Wikipedia" to "https://en.m.wikipedia.org",
                "Reddit" to "https://www.reddit.com",
            ).forEach { (label, link) -> AssistChip(onClick = { onGo(link) }, label = { Text(label) }) }
        }
    }
}
