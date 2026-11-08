package com.pocketai.chat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pocketai.chat.screen.ScreenCapture
import com.pocketai.chat.screen.ScreenText
import com.pocketai.chat.ui.AppTheme
import com.pocketai.chat.ui.AskPanel

/**
 * The Pocket AI panel over another app. Opened by the Pocket AI accessibility button (with what it read from
 * the screen) or by "Ask Pocket AI" in any app's text-selection menu (with the selected text).
 */
class AskActivity : ComponentActivity() {
    private val app: PocketApp get() = application as PocketApp
    private var capture by mutableStateOf<ScreenCapture?>(null)
    /** True once [capture] went out with a question (follow-ups in the same panel don't re-send it). */
    private var captureSent by mutableStateOf(false)
    private var chatId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        take(intent)
        setContent {
            AppTheme {
                AskPanel(
                    vm = app.chat,
                    capture = capture,
                    captureSent = captureSent,
                    chatId = chatId,
                    onSent = { id ->
                        chatId = id
                        captureSent = true
                        rememberPanelChat(id)
                    },
                    onNewChat = {
                        chatId = null
                        captureSent = false
                    },
                    onClose = { finish() },
                    onOpenApp = ::openInApp,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    override fun onStart() {
        super.onStart()
        app.visibleScreens++
    }

    override fun onStop() {
        app.visibleScreens--
        super.onStop()
    }

    private fun take(intent: Intent?) {
        // Tapping the button again within a while continues the same conversation instead of starting over.
        chatId = recentPanelChat()
        captureSent = false
        capture = if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
            val from = referrer?.host ?: callingPackage
            val label = from?.let { p -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() }
            ScreenCapture(kind = "selection", appName = ScreenText.appName(from, label, null), packageName = from, text = text.take(ScreenText.MAX_CHARS))
        } else {
            app.pendingCapture.also { app.pendingCapture = null }
        }
    }

    private val prefs get() = getSharedPreferences("ui", MODE_PRIVATE)

    private fun recentPanelChat(): String? {
        val id = prefs.getString("panelChat", null) ?: return null
        val at = prefs.getLong("panelChatAt", 0)
        return id.takeIf { System.currentTimeMillis() - at < CONTINUE_MS }
    }

    private fun rememberPanelChat(id: String) {
        prefs.edit().putString("panelChat", id).putLong("panelChatAt", System.currentTimeMillis()).apply()
    }

    /** Continues in the full app, in this chat. */
    private fun openInApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .apply { chatId?.let { putExtra(ReplyService.EXTRA_OPEN_CHAT, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val ACTION_ASK_SCREEN = "com.pocketai.chat.ASK_SCREEN"
        /** How long after the last question the button keeps continuing the same chat. */
        private const val CONTINUE_MS = 30 * 60_000L
    }
}
