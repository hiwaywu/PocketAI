package com.pocketai.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pocketai.chat.ui.AppRoot
import com.pocketai.chat.ui.AppTheme

class MainActivity : ComponentActivity() {
    /** Lives for the whole process (see [PocketApp]) so leaving the app never stops a reply. */
    private val vm: ChatViewModel get() = (application as PocketApp).chat

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        askForNotificationsOnce()
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as PocketApp).visibleScreens++
        ReplyService.clearDone(this)
    }

    override fun onStop() {
        (application as PocketApp).visibleScreens--
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Android 13+ needs permission for the "replying…" / "reply ready" notifications.
     * Asked once; replies keep running in the background either way.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotifications", false)) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(ReplyService.EXTRA_OPEN_CHAT)?.let { id ->
            vm.openChat(id)
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val uris = mutableListOf<Uri>()
        if (intent.type?.startsWith("image/") == true) {
            if (action == Intent.ACTION_SEND) {
                streamExtra(intent)?.let { uris += it }
            } else {
                streamListExtra(intent)?.let { uris += it }
            }
        }
        vm.handleShare(text, subject, uris)
        // Don't re-handle the same share if the activity is recreated.
        setIntent(Intent(this, MainActivity::class.java))
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
}
