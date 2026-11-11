package com.pocketai.chat.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pocketai.chat.api.RedditSession

/** Settings → Reddit: sign in once so shared threads come with their comments. */
@Composable
internal fun RedditSection() {
    var signedIn by remember { mutableStateOf(RedditSession.signedIn()) }
    var open by remember { mutableStateOf(false) }
    Column {
        Text(
            if (signedIn) "Signed in. Shared Reddit threads are read as you, with their comments."
            else "Reddit often hides comments from apps that aren't signed in. Sign in once (on Reddit's own page) so " +
                "threads you share come with their comments. Pocket AI keeps the sign-in on this phone only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (signedIn) OutlinedButton(onClick = { RedditSession.signOut(); signedIn = RedditSession.signedIn() }) { Text("Sign out of Reddit") }
            else Button(onClick = { open = true }) { Text("Sign in to Reddit") }
        }
    }
    if (open) RedditLoginDialog { open = false; signedIn = RedditSession.signedIn() }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RedditLoginDialog(onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Sign in on Reddit's page. Google sign-in may not work here; use your Reddit email and password.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(8.dp),
                    )
                    TextButton(onClick = onDone) { Text("Done") }
                }
                AndroidView(
                    modifier = Modifier.weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    CookieManager.getInstance().flush()
                                    // Back on Reddit after logging in: finished.
                                    if (RedditSession.signedIn() && url?.contains("/login") == false) onDone()
                                }
                            }
                            loadUrl(RedditSession.LOGIN_URL)
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}
