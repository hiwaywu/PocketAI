package com.pocketai.chat.screen

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What Pocket AI read from another app: the screen (Pocket AI button) or text the user selected
 * ("Ask Pocket AI" in the selection menu).
 */
data class ScreenCapture(
    /** "screen" or "selection". */
    val kind: String,
    val appName: String,
    val packageName: String? = null,
    /** The page's full link, when a browser shows it in its address bar. */
    val url: String? = null,
    /** The site's name ("nytimes.com") when the browser only shows that. */
    val site: String? = null,
    val text: String,
) {
    /** Short label for the chip on the message, e.g. "Firefox screen · nytimes.com". */
    val label: String
        get() = if (kind == "selection") "Text from $appName"
        else "$appName screen" + ((url?.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: site)?.let { " · $it" } ?: "")
}

/** The parts of screen reading that don't need Android, so they can be unit-tested. */
object ScreenText {
    /** About 6,000 tokens: a whole article or a screenful of comments. */
    const val MAX_CHARS = 25_000

    /** Address bars of common Android browsers (the page's link is read from there). */
    val URL_BAR_IDS = listOf(
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view",
        "org.mozilla.fenix:id/mozac_browser_toolbar_url_view",
        "org.mozilla.focus:id/display_url",
        "com.android.chrome:id/url_bar",
        "com.chrome.beta:id/url_bar",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.brave.browser:id/url_bar",
        "com.microsoft.emmx:id/url_bar",
        "com.opera.browser:id/url_field",
        "com.duckduckgo.mobile.android:id/omnibarTextInput",
        "com.vivaldi.browser:id/url_bar",
    )

    val BROWSERS = URL_BAR_IDS.map { it.substringBefore(":id/") }.toSet()

    private val NAMES = mapOf(
        "org.mozilla.firefox" to "Firefox", "org.mozilla.firefox_beta" to "Firefox Beta", "org.mozilla.fenix" to "Firefox",
        "org.mozilla.focus" to "Firefox Focus", "com.android.chrome" to "Chrome", "com.chrome.beta" to "Chrome Beta",
        "com.sec.android.app.sbrowser" to "Samsung Internet", "com.brave.browser" to "Brave", "com.microsoft.emmx" to "Edge",
        "com.opera.browser" to "Opera", "com.duckduckgo.mobile.android" to "DuckDuckGo", "com.vivaldi.browser" to "Vivaldi",
        "com.reddit.frontpage" to "Reddit", "com.google.android.youtube" to "YouTube", "com.twitter.android" to "X",
        "com.instagram.android" to "Instagram", "com.instagram.barcelona" to "Threads", "com.facebook.katana" to "Facebook",
        "com.zhiliaoapp.musically" to "TikTok", "com.whatsapp" to "WhatsApp", "org.telegram.messenger" to "Telegram",
        "com.discord" to "Discord", "com.google.android.gm" to "Gmail", "com.microsoft.office.outlook" to "Outlook",
        "com.samsung.android.messaging" to "Messages", "com.google.android.apps.messaging" to "Messages",
        "com.linkedin.android" to "LinkedIn", "com.google.android.apps.maps" to "Maps", "com.amazon.mShop.android.shopping" to "Amazon",
    )

    fun appName(pkg: String?, label: String?, windowTitle: String?): String =
        pkg?.let { NAMES[it] } ?: label?.takeIf { it.isNotBlank() } ?: windowTitle?.takeIf { it.isNotBlank() && it.length < 40 }
            ?: pkg?.substringAfterLast('.')?.replaceFirstChar { it.uppercase() } ?: "this app"

    /**
     * An address bar's text → (full link, site). Browsers often show only the site ("nytimes.com"); then there's
     * no link to read, just the site's name.
     */
    fun linkFromBar(raw: String?): Pair<String?, String?> {
        val t = raw?.trim()?.replace(" ", "").orEmpty()
        if (t.isEmpty() || !t.contains('.') || t.contains('\n')) return null to null
        val full = if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
        val u = full.toHttpUrlOrNull() ?: return null to null
        if (!u.host.contains('.')) return null to null
        val hasPath = u.encodedPath.length > 1 || u.query != null
        return (if (hasPath) full else null) to u.host.removePrefix("www.")
    }

    /**
     * Screen text as read from the app, one piece per line: drops repeats (labels are often read twice),
     * empty pieces and invisible characters, and keeps it under [MAX_CHARS].
     */
    fun clean(pieces: List<String>): String {
        val out = StringBuilder()
        var last = ""
        val seen = HashSet<String>()
        for (p in pieces) {
            val t = p.replace(Regex("[\\u200B-\\u200F\\u2060\\uFEFF]"), "").replace(Regex("[ \\t]+"), " ").trim()
            if (t.isEmpty() || t == last) continue
            // Long repeated pieces (the same paragraph under two views) only once; short ones like "Reply" can repeat.
            if (t.length > 40 && !seen.add(t)) continue
            if (out.length + t.length + 1 > MAX_CHARS) break
            out.append(t).append('\n')
            last = t
        }
        return out.toString().trim()
    }
}
