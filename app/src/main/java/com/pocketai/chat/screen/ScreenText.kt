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
    /** A note for the AI only (e.g. how to find a YouTube video's transcript); not shown in the "what it read" preview. */
    val note: String? = null,
    /** How reading went (e.g. how far the YouTube transcript got), shown small under the preview to help fix problems. */
    val debug: String? = null,
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

    const val YOUTUBE = "com.google.android.youtube"

    /** Sent with a YouTube app screen when its transcript couldn't be read there. */
    const val YOUTUBE_NOTE =
        "[Pocket AI: this is the YouTube app, which doesn't show the video's link (it couldn't be found automatically). " +
            "If the user asks what the video " +
            "says (a summary, the transcript, details), first call fetch_url on https://www.youtube.com/results?search_query= " +
            "followed by the video's exact title from the screen (plus the channel name), then call fetch_url on the " +
            "matching video's link to read its details and transcript. Don't ask the user for the link, and don't search " +
            "news sites about it instead; find the video this way. " +
            "Only if no title is on the screen (e.g. the video is full screen), ask them to exit full screen and tap the " +
            "Pocket AI button again.]"

    /** For when the app found the video on YouTube by its title. */
    fun youTubeLinkNote(link: String) =
        "[Pocket AI: this is the YouTube app; the video on screen is $link. If the user asks what the video says (a " +
            "summary, the transcript, details), call fetch_url on that link: it returns the video's details and its " +
            "transcript. Don't search news sites about it instead.]"

    /** When only part of the transcript could be read from the YouTube app. */
    fun youTubePartialNote(link: String?) =
        "[Pocket AI: the transcript below is incomplete; only part of it could be read from the YouTube app. " +
            (if (link != null) "Before answering, call fetch_url on $link to get the whole transcript. "
            else "Before answering, get the whole transcript: call fetch_url on https://www.youtube.com/results?search_query= " +
                "followed by the video's title from the screen, then on the matching video's link. ") +
            "Don't search news sites to fill the gaps.]"

    private val CLOCK = Regex("""((?:\d{1,2}:)?\d{1,2}:\d{2})\s*/\s*((?:\d{1,2}:)?\d{1,2}:\d{2})""")
    private val SPOKEN_LENGTH = Regex("""(?i)\bof\s+((?:\d+\s*hours?,?\s*)?(?:\d+\s*minutes?,?\s*)?(?:\d+\s*seconds?)?)""")

    /** The video's length in seconds, from the player's "2:04 / 21:25" (or "… of 21 minutes, 25 seconds"), if shown. */
    fun videoLength(pieces: List<String>): Int? {
        for (p in pieces) {
            CLOCK.find(p)?.let { return com.pocketai.chat.api.Transcript.seconds(it.groupValues[2]) }
            SPOKEN_LENGTH.find(p)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { spoken ->
                leadingTime(spoken)?.first?.let { return com.pocketai.chat.api.Transcript.seconds(it) }
            }
        }
        return null
    }

    /** YouTube's "...more" under the title (opens the description) and similar labels. */
    fun isMoreLink(t: String): Boolean {
        val s = t.trim().lowercase()
        return s.endsWith("...more") || s.endsWith("…more") || s.endsWith("... more") || s == "more" ||
            s == "show more" || s.contains("expand description") || s == "description"
    }

    private val NOT_TITLE = Regex(
        """(?i)^(\d[\d.,]*\s*[kmb]?\s*(views?|subscribers?|likes?|comments?|watching)\b|.*\b\d+\s*(seconds?|minutes?|hours?|days?|weeks?|months?|years?)\s+ago\b|subscribe|share|download|remix|save|clip|thanks|comments?$|like this video|dislike this video|more actions|search transcript|transcript$)""",
    )

    /**
     * Lines from a YouTube app screen that could be the video's title (searched on YouTube to find its link),
     * best first: longer lines that aren't counts, dates or buttons.
     */
    fun titleCandidates(pieces: List<String>): List<String> =
        pieces.map { it.trim() }
            .filter { com.pocketai.chat.api.PageText.weight(it) >= 16 && it.length <= 200 && !NOT_TITLE.containsMatchIn(it) && !isTimestamp(it) }
            .distinct()
            .take(4)

    private val TIMESTAMP = Regex("""^(\d{1,2}:)?\d{1,2}:\d{2}$""")
    private val TIMED_LINE = Regex("""^((?:\d{1,2}:)?\d{1,2}:\d{2})\s+(.+)$""")

    fun isTimestamp(s: String) = TIMESTAMP.matches(s.trim())

    private val DURATION = Regex(
        """^(?:(\d+)\s*hours?)?[,\s]*(?:(\d+)\s*minutes?)?[,\s]*(?:(\d+)\s*seconds?)?(?=$|[\s,])""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A transcript time at the start of [s] → (time as "m:ss", the words after it). Understands "1:49", "1:02:03"
     * and the spoken form screen readers get ("1 minute, 49 seconds"). Not the player's "2:04 / 21:25" or
     * "2 hours ago".
     */
    fun leadingTime(s: String): Pair<String, String>? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val (time, rest) = when {
            isTimestamp(t) -> t to ""
            TIMED_LINE.find(t) != null -> TIMED_LINE.find(t)!!.let { it.groupValues[1] to it.groupValues[2].trim() }
            else -> {
                val m = DURATION.find(t) ?: return null
                val (h, mi, se) = m.destructured
                if (h.isEmpty() && mi.isEmpty() && se.isEmpty()) return null
                val hh = h.toIntOrNull() ?: 0
                val mm = mi.toIntOrNull() ?: 0
                val ss = se.toIntOrNull() ?: 0
                val clock = if (hh > 0) "%d:%02d:%02d".format(hh, mm, ss) else "%d:%02d".format(mm, ss)
                clock to t.substring(m.range.last + 1).trim().trimStart(',', ' ')
            }
        }
        if (rest.startsWith("/") || rest.startsWith("ago", ignoreCase = true)) return null
        return time to rest
    }

    /**
     * Pairs transcript lines read from the YouTube app's transcript panel: a time followed by its words, or both
     * on one line. Returns (time, words), in order.
     */
    fun transcriptSegments(lines: List<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var pending: String? = null
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            val lt = leadingTime(l)
            when {
                lt != null && lt.second.isEmpty() -> pending = lt.first
                lt != null -> { out += lt; pending = null }
                pending != null -> { out += pending!! to l; pending = null }
            }
        }
        return out
    }

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

    private val ADDRESS = Regex("""^(https?://)?([a-z0-9-]+\.)+[a-z]{2,}(:\d+)?(/\S*)?$""", RegexOption.IGNORE_CASE)

    /** Text shaped like a web address ("nytimes.com/2026/…", "https://…"), as a browser's address bar shows it. */
    /** Invisible direction marks some browsers wrap the address in. */
    private val MARKS = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")

    fun looksLikeAddress(t: String): Boolean {
        val s = t.replace(MARKS, "").trim().removeSuffix("…")
        return s.length in 4..2000 && !s.contains(' ') && ADDRESS.matches(s)
    }

    /**
     * An address bar's text → (full link, site). Browsers often show only the site ("nytimes.com"); then there's
     * no link to read, just the site's name.
     */
    fun linkFromBar(raw: String?): Pair<String?, String?> {
        val t = raw?.replace(MARKS, "")?.trim()?.removeSuffix("…")?.replace(" ", "").orEmpty()
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
