package com.pocketai.chat.screen

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
 * "Ask Pocket AI": an accessibility service that draws Pocket AI's own floating button ([FloatingButton]). Tapping
 * it reads the app on screen (its text, and the page's link in browsers) and opens the Pocket AI panel over that app. Nothing is read until the button is tapped:
 * events are ignored, and Gemini keeps the side button and its own screen features.
 */
class AskService : AccessibilityService() {
    /** Pocket AI's own floating button (tap to ask, hold to hide). */
    var button: FloatingButton? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        button = FloatingButton(this) { askAboutScreen() }.also { it.refresh() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        button?.onScreenChanged()
    }

    override fun onDestroy() {
        button?.destroy()
        button = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        button?.destroy()
        button = null
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    private var busy = false

    private fun askAboutScreen() {
        if (busy) return
        busy = true
        // Off the main thread: in YouTube and Reddit the button looks the video/thread up online first.
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
    /**
     * The app window the user is looking at (on the unfolded Fold with two apps open, the one last touched).
     * Pocket AI's own panel is skipped: tapping the button while a panel is still open read the panel itself.
     */
    private fun appWindow(): AccessibilityWindowInfo? {
        val apps = appWindows()
        chosenWindow?.let { id -> apps.firstOrNull { it.id == id }?.let { return it } }
        return apps.firstOrNull()
    }

    /** App windows other than Pocket AI's, the active (then focused) one first. */
    private fun appWindows(): List<AccessibilityWindowInfo> = runCatching { windows }.getOrNull().orEmpty()
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() != packageName }
        .sortedByDescending { (if (it.isActive) 2 else 0) + (if (it.isFocused) 1 else 0) }

    /** The window read on this tap (later steps, like YouTube's transcript, keep using it). */
    @Volatile private var chosenWindow: Int? = null
    /** Text found in each app window on this tap, for the report. */
    private var windowTexts = ""

    /**
     * The window to read: the active one, unless another app window has far more text. An app can have an empty
     * window marked "active" (the Reddit app read as nothing in 2.14).
     */
    private fun pickWindow(): AccessibilityWindowInfo? {
        val sized = appWindows().map { win ->
            val r = win.root
            val w = Walk(r?.packageName?.toString() in ScreenText.BROWSERS)
            if (r != null) walk(r, w, false, 0)
            win to w.pieces.sumOf { it.length }
        }
        windowTexts = sized.joinToString("; ") { (win, n) -> "${win.root?.packageName ?: "?"}${if (win.isActive) " active" else ""} $n chars" }
        if (sized.isEmpty()) return null
        val most = sized.maxByOrNull { it.second }!!
        val first = sized.first()
        return if (first.second >= 50 && first.second * 2 >= most.second) first.first else most.first
    }

    private fun readScreen(): ScreenCapture? {
        chosenWindow = null
        val win = pickWindow()
        chosenWindow = win?.id
        val root = win?.root ?: rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
            ?: return ScreenCapture("screen", "this app", text = "", debug = "no app window to read\n" + windowsLine())
        val pkg = root.packageName?.toString()
        val browser = pkg in ScreenText.BROWSERS
        var bar: String? = null
        for (id in ScreenText.URL_BAR_IDS) {
            if (!id.startsWith("$pkg:")) continue
            bar = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()?.firstOrNull()?.text?.toString()
            if (bar != null) break
        }
        val w = Walk(browser)
        walk(root, w, false, 0)
        // Newer Firefox draws its address bar without a fixed id: fall back to text shaped like a web address in
        // the browser's own toolbar (not in the page).
        if (bar.isNullOrBlank() && browser) bar = w.addressBars.firstOrNull() ?: w.addresses.firstOrNull()
        var (url, site) = ScreenText.linkFromBar(bar)
        val screenText = ScreenText.clean(w.pieces)
        // YouTube app: it hides most of its text (comments, most of the transcript) from accessibility services, and
        // scrolling its transcript from outside never worked (2.9–2.18). So find the video by the title on screen and
        // read it from the web like a shared link: details + the whole transcript (that path works on the phone).
        var ytLog: String? = null
        val video = if (pkg == ScreenText.YOUTUBE) {
            val titles = ScreenText.titleCandidates(w.pieces)
            if (titles.isEmpty()) { ytLog = "youtube: no title on screen"; null } else {
                toast("Reading the video…")
                val page = runCatching {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(50_000) { (application as PocketApp).chat.readYouTubeVideo(titles) }
                    }
                }.getOrNull()
                ytLog = if (page != null) "youtube: read ${page.url}" else "youtube: no video found for ${titles.take(3)}"
                page
            }
        } else null
        if (video != null) url = video.url
        // Reddit app: find the thread on reddit.com by the title on screen and read all of it (post, linked
        // article, top comments), like a shared link. The app itself only shows what's on screen.
        var redditLog: String? = null
        val thread = if (pkg == ScreenText.REDDIT_APP) {
            val sub = w.pieces.map { it.trim() }.firstOrNull { Regex("""^r/[A-Za-z0-9_]{2,}$""").matches(it) }
            val titles = ScreenText.redditTitleCandidates(w.pieces)
            if (titles.isEmpty()) { redditLog = "reddit: no title on screen"; null } else {
                toast("Reading the Reddit thread…")
                val page = runCatching {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(30_000) { (application as PocketApp).chat.readRedditThread(sub, titles) }
                    }
                }.getOrNull()
                redditLog = if (page != null) "reddit: read ${page.url}" else "reddit: no thread found for ${titles.take(2)} in ${sub ?: "all of Reddit"}"
                page
            }
        } else null
        if (thread != null) url = thread.url
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg ?: "", 0)).toString()
        }.getOrNull()
        return ScreenCapture(
            kind = "screen",
            appName = ScreenText.appName(pkg, label, win?.title?.toString()),
            packageName = pkg,
            url = url,
            site = site,
            text = thread?.let { "${it.text}\n\nOn screen in the Reddit app:\n$screenText" }
                ?: video?.let { "${it.text}\n\nOn screen in the YouTube app:\n$screenText" }
                ?: screenText,
            note = when {
                pkg != ScreenText.YOUTUBE -> null
                commentsOpen(root) -> ScreenText.YOUTUBE_COMMENTS_HIDDEN_NOTE
                video != null -> null
                else -> ScreenText.YOUTUBE_NOTE
            },
            debug = listOfNotNull(ytLog, redditLog, runCatching { report(root, win) }.getOrNull())
                .joinToString("\n").ifBlank { null },
        )
    }

    /**
     * A technical report of the screen for fixing problems remotely: the windows, how much text each had, and the
     * start of the chosen window's structure (classes, flags S=scrolls C=tappable h=hidden). Shown and copyable
     * under the panel's "What Pocket AI read".
     */
    private fun report(root: AccessibilityNodeInfo, chosen: AccessibilityWindowInfo?): String {
        val sb = StringBuilder()
        val wins = runCatching { windows }.getOrNull().orEmpty()
        sb.append("windows: ").append(wins.joinToString("; ") { w ->
            "${winType(w.type)} ${w.root?.packageName ?: "?"}${if (w.isActive) " active" else ""}${if (w.isFocused) " focused" else ""}${if (w == chosen) " ←read" else ""}"
        }).append('\n')
        sb.append("text per app window: $windowTexts\n")
        sb.append("screen:\n")
        dumpTree(root, sb, 1, intArrayOf(0), 60)
        return sb.toString().take(7_000)
    }

    private fun winType(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y"
        else -> "type$t"
    }

    private fun describe(n: AccessibilityNodeInfo): String {
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
        val flags = buildString {
            if (n.isScrollable) append('S')
            if (n.isClickable) append('C')
            if (!n.isVisibleToUser) append('h')
        }
        val scrolls = n.actionList.mapNotNull { a ->
            when (a.id) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "fwd"
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "back"
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> "down"
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> "up"
                else -> null
            }
        }
        val id = n.viewIdResourceName?.substringAfter(":id/")
        val t = n.text?.toString()?.replace('\n', ' ')?.take(36)
        val d = n.contentDescription?.toString()?.replace('\n', ' ')?.take(36)
        return buildString {
            append(cls)
            if (flags.isNotEmpty()) append(" [").append(flags).append(']')
            if (scrolls.isNotEmpty()) append(" {").append(scrolls.joinToString(",")).append('}')
            append(" ch=").append(n.childCount)
            id?.let { append(" #").append(it) }
            t?.let { append(" t=\"").append(it).append('"') }
            d?.let { append(" d=\"").append(it).append('"') }
        }
    }

    private fun dumpTree(n: AccessibilityNodeInfo, sb: StringBuilder, depth: Int, count: IntArray, max: Int) {
        if (count[0] >= max || depth > 25) return
        count[0]++
        sb.append("  ".repeat(depth)).append(describe(n)).append('\n')
        for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { dumpTree(it, sb, depth + 1, count, max) }
    }

    /** YouTube's comments panel is showing ("Comments" with its "Top" / "Newest" tabs). */
    private fun commentsOpen(root: AccessibilityNodeInfo) =
        findLabel(root, "comments") != null && findLabel(root, "top") != null && findLabel(root, "newest") != null

    private fun windowsLine() = "windows: " + runCatching { windows }.getOrNull().orEmpty()
        .joinToString("; ") { "${winType(it.type)} ${it.root?.packageName ?: "?"}${if (it.isActive) " active" else ""}" }

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

    companion object {
        /** The running service (null when the Ask Pocket AI shortcut is off), for the button's settings and tile. */
        @Volatile var instance: AskService? = null
            private set


        /** Whether the user has turned the Pocket AI button on in Accessibility settings. */
        fun isOn(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return isUs(ctx, enabled)
        }

        /**
         * Whether Android's own accessibility button still has Ask Pocket AI as its shortcut (set up before 2.18, when
         * the service asked for that button). Tapping it now would switch the service off, so Settings says how to
         * remove it. Null when Android doesn't let us read that setting.
         */
        fun systemButtonAssigned(ctx: Context): Boolean? = runCatching {
            isUs(ctx, Settings.Secure.getString(ctx.contentResolver, "accessibility_button_targets").orEmpty())
        }.getOrNull()

        private fun isUs(ctx: Context, list: String) = list.split(':').any { s ->
            ComponentName.unflattenFromString(s)?.let { it.packageName == ctx.packageName && it.className == AskService::class.java.name } == true
        }
    }
}
