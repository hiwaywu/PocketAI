package com.pocketai.chat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.pocketai.chat.screen.SameTopic
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
    /** The recent chat that wasn't continued (different page or app), offered as "Continue last chat". */
    private var lastChatId by mutableStateOf<String?>(null)

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
                    lastChatId = lastChatId,
                    onContinueLast = {
                        chatId = lastChatId
                        lastChatId = null
                    },
                    onSent = { id ->
                        chatId = id
                        captureSent = true
                        rememberPanelChat(id)
                    },
                    onNewChat = {
                        lastChatId = chatId
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
        // The floating button steps aside while the panel is open (it used to cover the panel's ✕).
        com.pocketai.chat.screen.AskService.instance?.button?.setPanelOpen(true)
    }

    override fun onStop() {
        com.pocketai.chat.screen.AskService.instance?.button?.setPanelOpen(false)
        app.visibleScreens--
        super.onStop()
    }

    private fun take(intent: Intent?) {
        chatId = null
        lastChatId = null
        captureSent = false
        capture = if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
            val from = referrer?.host ?: callingPackage
            val label = from?.let { p -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() }
            ScreenCapture(kind = "selection", appName = ScreenText.appName(from, label, null), packageName = from, text = text.take(ScreenText.MAX_CHARS))
        } else {
            app.pendingCapture.also { app.pendingCapture = null }
        }
        decideChat()
    }

    /**
     * Same page or app as the recent panel chat (within 30 minutes) → continue it, so the AI knows it's related;
     * a new website, video or app → a new chat, so old pages aren't re-sent with every question.
     */
    private fun decideChat() {
        val recent = recentPanelChat() ?: return
        val at = prefs.getLong("panelChatAt", 0)
        val shown = capture
        lifecycleScope.launch {
            app.chat.awaitChats()
            if (chatId != null || captureSent || capture !== shown || app.chat.chat(recent) == null) return@launch
            if (SameTopic.isSame(app.chat.lastScreen(recent, at), shown, System.currentTimeMillis())) chatId = recent
            else lastChatId = recent
        }
    }

    private val prefs get() = getSharedPreferences("ui", MODE_PRIVATE)

    private fun recentPanelChat(): String? {
        val id = prefs.getString("panelChat", null) ?: return null
        val at = prefs.getLong("panelChatAt", 0)
        return id.takeIf { System.currentTimeMillis() - at < SameTopic.MAX_GAP_MS }
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
    }
}
