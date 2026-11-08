package com.pocketai.chat.screen

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        val transcript = if (pkg == ScreenText.YOUTUBE) runCatching { youTubeTranscript(root) }.getOrNull() else null
        val w = Walk(browser)
        walk(root, w, false, 0)
        // Newer Firefox draws its address bar without a fixed id: fall back to text shaped like a web address in
        // the browser's own toolbar (not in the page).
        if (bar.isNullOrBlank() && browser) bar = w.addressBars.firstOrNull() ?: w.addresses.firstOrNull()
        var (url, site) = ScreenText.linkFromBar(bar)
        val screenText = ScreenText.clean(w.pieces)
        // No transcript from the app: find the video on YouTube by the title on screen, so the AI gets its exact
        // link (and reads that video's transcript) instead of searching news sites about it.
        val videoLink = if (pkg == ScreenText.YOUTUBE && transcript == null) {
            val titles = ScreenText.titleCandidates(w.pieces)
            if (titles.isEmpty()) null else {
                toast("Finding the video…")
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(9_000) { (application as PocketApp).chat.findYouTubeVideo(titles) }
                    }
                }.getOrNull()
            }
        } else null
        if (videoLink != null) url = videoLink
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
            note = if (pkg == ScreenText.YOUTUBE && transcript == null) {
                if (videoLink != null) ScreenText.youTubeLinkNote(videoLink) else ScreenText.YOUTUBE_NOTE
            } else null,
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
            var button = findLabel(root, "show transcript")
            // A transcript is showing but its list couldn't be found: read what's there, don't tap anything.
            if (button == null && timeLines(root, 3).size >= 3) return null
            if (button == null) {
                // On the video page: open the description (the title's "...more"), where "Show transcript" lives,
                // scrolling down to it if needed.
                val more = findText(root) { ScreenText.isMoreLink(it) } ?: return null
                toast("Opening the transcript…")
                if (!click(more)) return null
                for (i in 0 until 12) {
                    Thread.sleep(250)
                    root = currentRoot() ?: root
                    button = findLabel(root, "show transcript")
                    if (button != null) break
                }
                for (i in 0 until 8) {
                    if (button != null) break
                    val panel = descriptionList(root) ?: break
                    if (!scrollDown(panel)) break
                    Thread.sleep(350)
                    root = currentRoot() ?: root
                    button = findLabel(root, "show transcript")
                }
                button ?: return null
            }
            if (!click(button)) return null
            for (i in 0 until 16) {
                Thread.sleep(250)
                root = currentRoot() ?: root
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
            val lines = texts(list)
            val before = segments.size
            ScreenText.transcriptSegments(lines).forEach { (t, w) -> segments.putIfAbsent("$t|$w", "$t $w") }
            unchanged = if (segments.size == before) unchanged + 1 else 0
            if (unchanged >= 2) break
            if (!scrollDown(list)) {
                if (i > 0) break
            }
            Thread.sleep(400)
        }
        if (segments.size < 3) return null
        return com.pocketai.chat.api.Transcript.format(segments.values.toList(), 12_000)
    }

    private fun currentRoot(): AccessibilityNodeInfo? = appWindow()?.root ?: rootInActiveWindow

    /** Taps a node (or the nearest tappable thing around it). */
    private fun click(node: AccessibilityNodeInfo): Boolean =
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    /** A node whose text or label passes [test]. */
    private fun findText(root: AccessibilityNodeInfo, test: (String) -> Boolean): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (stack.isNotEmpty() && seen < 3_000) {
            val n = stack.removeFirst()
            seen++
            if (n.isVisibleToUser) for (t in listOfNotNull(n.text, n.contentDescription)) if (test(t.toString().trim())) return n
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { stack.add(it) }
        }
        return null
    }

    /** The scrolling list of YouTube's description panel (it holds "Video details" / "Transcript"). */
    private fun descriptionList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        var found: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty() && seen < 3_000) {
            val n = stack.removeLast()
            seen++
            if (n.isScrollable && n.isVisibleToUser) {
                val t = texts(n).joinToString(" ").lowercase()
                if ("transcript" in t || "video details" in t || "description" in t) found = n
            }
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { stack.add(it) }
        }
        return found
    }

    /** Visible nodes whose text starts with a transcript time ("1:49", "1 minute, 49 seconds"), up to [max]. */
    private fun timeLines(root: AccessibilityNodeInfo, max: Int): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val hits = mutableListOf<AccessibilityNodeInfo>()
        var seen = 0
        while (queue.isNotEmpty() && seen < 8_000 && hits.size < max) {
            val n = queue.removeFirst()
            seen++
            val t = (n.text ?: n.contentDescription)?.toString()
            if (t != null && n.isVisibleToUser && ScreenText.leadingTime(t) != null) hits += n
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
        }
        return hits
    }

    /**
     * The transcript's list: found from its time stamps (the list itself may not say it scrolls): the nearest
     * scrolling container around them, else the smallest container holding several of them.
     */
    private fun transcriptList(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        val hits = timeLines(root, 4)
        if (hits.size < 3) return null
        fun holdsTimes(n: AccessibilityNodeInfo) = texts(n).count { ScreenText.leadingTime(it) != null } >= 3
        for (h in hits) {
            generateSequence(h.parent) { it.parent }.firstOrNull { it.isScrollable }?.let { if (holdsTimes(it)) return it }
        }
        return generateSequence(hits[0].parent) { it.parent }.take(12).firstOrNull { holdsTimes(it) }
    }

    /**
     * Scrolls a list down by most of its height: the list's own scroll action if it has one, else a finger-like
     * swipe on it (YouTube's lists don't always take the scroll command).
     */
    private fun scrollDown(list: AccessibilityNodeInfo): Boolean {
        if (list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        if (list.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)) return true
        return swipeUp(list)
    }

    private fun swipeUp(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.height() < 200 || r.width() < 100) return false
        val x = r.exactCenterX()
        val path = Path().apply {
            moveTo(x, r.bottom - r.height() * 0.15f)
            lineTo(x, r.top + r.height() * 0.2f)
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        val done = CountDownLatch(1)
        var ok = false
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; done.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
        }, null)
        if (!sent) return false
        done.await(2, TimeUnit.SECONDS)
        return ok
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

    /** What one pass over a window found. */
    private class Walk(val browser: Boolean) {
        val pieces = mutableListOf<String>()
        /** Web addresses in a browser's toolbar, from an address field (id or editable) or anywhere else in it. */
        val addressBars = mutableListOf<String>()
        val addresses = mutableListOf<String>()
    }

    /**
     * Collects the text of the window in reading order. Off-screen parts of web pages are kept (that's the rest of
     * the article); in apps only what's showing. Password and text-entry fields are skipped. Apps like YouTube
     * keep titles in the labels of tappable areas, so labels count too, except short icon labels ("Back").
     */
    private fun walk(node: AccessibilityNodeInfo?, w: Walk, inPage: Boolean, depth: Int) {
        if (node == null || depth > 80 || w.pieces.size > 4_000) return
        if (node.isPassword) return
        val cls = node.className?.toString().orEmpty()
        val page = inPage || cls.contains("WebView") || cls.contains("GeckoView")
        if (!(w.browser || page) && !node.isVisibleToUser) return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (w.browser && !page) {
            for (t in listOfNotNull(text, desc)) if (ScreenText.looksLikeAddress(t)) {
                val id = node.viewIdResourceName.orEmpty().lowercase()
                if (node.isEditable || listOf("url", "toolbar", "omnibar", "address", "location").any { it in id }) w.addressBars += t.trim()
                else w.addresses += t.trim()
            }
        }
        if (!node.isEditable) {
            when {
                !text.isNullOrBlank() -> w.pieces += text
                !desc.isNullOrBlank() && (com.pocketai.chat.api.PageText.weight(desc) >= 20 || (node.childCount == 0 && !node.isClickable)) ->
                    w.pieces += desc
            }
        }
        for (i in 0 until node.childCount) walk(runCatching { node.getChild(i) }.getOrNull(), w, page, depth + 1)
    }

    private fun texts(node: AccessibilityNodeInfo): List<String> = Walk(false).also { walk(node, it, false, 0) }.pieces

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
