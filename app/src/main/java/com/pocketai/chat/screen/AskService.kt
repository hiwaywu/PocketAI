package com.pocketai.chat.screen

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
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

    private var busy = false

    private fun askAboutScreen() {
        if (busy) return
        busy = true
        // Off the main thread: reading a YouTube transcript scrolls the panel and waits for it to load.
        Thread {
            val capture = try {
                runCatching { readScreen() }.getOrNull()
            } finally {
                busy = false
            }
            (application as PocketApp).pendingCapture = capture
            startActivity(
                Intent(this, AskActivity::class.java)
                    .setAction(AskActivity.ACTION_ASK_SCREEN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }.start()
    }

    private fun toast(text: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

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
        val transcript = if (pkg == ScreenText.YOUTUBE) runCatching { youTubeTranscript(root) }.getOrNull() else null
        val pieces = mutableListOf<String>()
        walk(root, browser, pieces, 0)
        val screenText = ScreenText.clean(pieces)
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg ?: "", 0)).toString()
        }.getOrNull()
        return ScreenCapture(
            kind = "screen",
            appName = ScreenText.appName(pkg, label, win?.title?.toString()),
            packageName = pkg,
            url = url,
            site = site,
            text = transcript?.let {
                "Transcript (read from the YouTube app's transcript panel; [m:ss] = time in the video):\n$it\n\nRest of the screen:\n$screenText"
            } ?: screenText,
            note = if (pkg == ScreenText.YOUTUBE && transcript == null) ScreenText.YOUTUBE_NOTE else null,
        )
    }

    /**
     * The YouTube app's own transcript: if its "Show transcript" button is on screen it's tapped; then the
     * transcript panel is scrolled through (you see it scroll) and every line is collected. Null when there's no
     * transcript panel or button on screen.
     */
    private fun youTubeTranscript(start: AccessibilityNodeInfo): String? {
        var root = start
        var list = transcriptList(root)
        if (list == null) {
            val button = findLabel(root, "show transcript") ?: return null
            val target = generateSequence(button) { it.parent }.firstOrNull { it.isClickable } ?: return null
            if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return null
            for (i in 0 until 16) {
                Thread.sleep(250)
                root = appWindow()?.root ?: rootInActiveWindow ?: root
                list = transcriptList(root)
                if (list != null) break
            }
            list ?: return null
        }
        toast("Reading the transcript…")
        val segments = LinkedHashMap<String, String>()
        var unchanged = 0
        for (i in 0 until 150) {
            list!!.refresh()
            val lines = mutableListOf<String>()
            walk(list, false, lines, 0)
            val before = segments.size
            ScreenText.transcriptSegments(lines).forEach { (t, w) -> segments.putIfAbsent("$t|$w", "$t $w") }
            unchanged = if (segments.size == before) unchanged + 1 else 0
            if (unchanged >= 2) break
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                if (i > 0) break
            }
            Thread.sleep(300)
        }
        if (segments.size < 3) return null
        return com.pocketai.chat.api.Transcript.format(segments.values.toList(), 12_000)
    }

    /** A scrolling list on screen whose items look like transcript lines (a time, then words). */
    private fun transcriptList(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (stack.isNotEmpty() && seen < 3_000) {
            val n = stack.removeLast()
            seen++
            if (n.isScrollable) {
                val lines = mutableListOf<String>()
                walk(n, false, lines, 0)
                if (lines.count { ScreenText.isTimestamp(it) || ScreenText.transcriptSegments(listOf(it)).isNotEmpty() } >= 4) return n
            }
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { stack.add(it) }
        }
        return null
    }

    /** A node whose text or description is [label] (ignoring case). */
    private fun findLabel(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (stack.isNotEmpty() && seen < 3_000) {
            val n = stack.removeLast()
            seen++
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (t != null && t.equals(label, ignoreCase = true) && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { stack.add(it) }
        }
        return null
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
