package com.pocketai.chat.screen

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.pocketai.chat.AskActivity
import com.pocketai.chat.PocketApp

/**
 * The "Ask Pocket AI" accessibility button. Tapping it reads the app on screen (its text, and the page's link
 * in browsers) and opens the Pocket AI panel over that app. Nothing is read until the button is tapped:
 * events are ignored, and Gemini keeps the side button and its own screen features.
 */
class AskService : AccessibilityService() {
    private var callback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val cb = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) = askAboutScreen()
        }
        accessibilityButtonController.registerAccessibilityButtonCallback(cb)
        callback = cb
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        callback?.let { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) }
        callback = null
        return super.onUnbind(intent)
    }

    private fun askAboutScreen() {
        val capture = runCatching { readScreen() }.getOrNull()
        (application as PocketApp).pendingCapture = capture
        startActivity(
            Intent(this, AskActivity::class.java)
                .setAction(AskActivity.ACTION_ASK_SCREEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    /** The app window the user is looking at (on the unfolded Fold with two apps open, the one last touched). */
    private fun appWindow(): AccessibilityWindowInfo? {
        val apps = runCatching { windows }.getOrNull().orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused } ?: apps.firstOrNull()
    }

    private fun readScreen(): ScreenCapture? {
        val win = appWindow()
        val root = win?.root ?: rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        if (pkg == packageName) return null
        val browser = pkg in ScreenText.BROWSERS
        var bar: String? = null
        for (id in ScreenText.URL_BAR_IDS) {
            if (!id.startsWith("$pkg:")) continue
            bar = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()?.firstOrNull()?.text?.toString()
            if (bar != null) break
        }
        val (url, site) = ScreenText.linkFromBar(bar)
        val pieces = mutableListOf<String>()
        walk(root, browser, pieces, 0)
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg ?: "", 0)).toString()
        }.getOrNull()
        return ScreenCapture(
            kind = "screen",
            appName = ScreenText.appName(pkg, label, win?.title?.toString()),
            packageName = pkg,
            url = url,
            site = site,
            text = ScreenText.clean(pieces),
        )
    }

    /**
     * Collects the text of the window in reading order. Off-screen parts of web pages are kept (that's the rest of
     * the article); in apps only what's showing. Password and text-entry fields are skipped.
     */
    private fun walk(node: AccessibilityNodeInfo?, web: Boolean, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 80 || out.size > 4_000) return
        if (node.isPassword) return
        val cls = node.className?.toString().orEmpty()
        val inWeb = web || cls.contains("WebView") || cls.contains("GeckoView")
        if (!inWeb && !node.isVisibleToUser) return
        if (!node.isEditable) {
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            when {
                !text.isNullOrBlank() -> out += text
                // Image descriptions and the like; skip icon buttons ("More options", "Back").
                !desc.isNullOrBlank() && node.childCount == 0 && !node.isClickable -> out += desc
            }
        }
        for (i in 0 until node.childCount) walk(runCatching { node.getChild(i) }.getOrNull(), inWeb, out, depth + 1)
    }

    companion object {
        /** Whether the user has turned the Pocket AI button on in Accessibility settings. */
        fun isOn(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { s ->
                ComponentName.unflattenFromString(s)?.let { it.packageName == ctx.packageName && it.className == AskService::class.java.name } == true
            }
        }
    }
}
