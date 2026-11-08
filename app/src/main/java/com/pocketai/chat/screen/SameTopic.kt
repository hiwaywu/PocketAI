package com.pocketai.chat.screen

import com.pocketai.chat.api.PageText
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Decides whether a new tap of the Pocket AI button is about the same thing as the panel's recent chat (continue
 * it) or something new (start a new chat), so a long chat doesn't drag old pages along: the AI re-reads the whole
 * chat with every question, so mixing topics costs tokens and muddles answers.
 */
object SameTopic {
    /** After this long, a new tap always starts a new chat. */
    const val MAX_GAP_MS = 30 * 60_000L

    /** The screen the recent chat was last about. */
    data class Last(val app: String?, val url: String?, val text: String, val at: Long)

    fun isSame(last: Last?, now: ScreenCapture?, nowMs: Long): Boolean {
        if (last == null || now == null) return false
        if (nowMs - last.at > MAX_GAP_MS) return false
        // Text selected in the app you were just asking about is almost always part of what you're reading.
        if (last.app != null && now.packageName != null && last.app != now.packageName) return false
        if (now.kind == "selection") return true
        val a = pageKey(last.url)
        val b = pageKey(now.url)
        if (a != null && b != null) return a == b
        val overlap = overlap(last.text, now.text) ?: return true // too little text to tell: stay in the chat
        return overlap >= 0.2
    }

    /** A page's identity: site + path (+ the video id on YouTube), ignoring tracking parameters. */
    fun pageKey(url: String?): String? {
        val u = url?.trim()?.toHttpUrlOrNull() ?: return null
        val host = u.host.lowercase().removePrefix("www.").removePrefix("m.")
        val v = if (host.endsWith("youtube.com")) u.queryParameter("v") else null
        return host + u.encodedPath.trimEnd('/') + (v?.let { "?v=$it" } ?: "")
    }

    /**
     * Share of the longer lines (titles, paragraphs, comments; not buttons) the two screens have in common, or
     * null when either has too few to compare. Scrolling the same article keeps most of them; a new page shares
     * almost none.
     */
    fun overlap(a: String, b: String): Double? {
        val x = lines(a)
        val y = lines(b)
        if (x.size < 3 || y.size < 3) return null
        return x.intersect(y).size.toDouble() / minOf(x.size, y.size)
    }

    private fun lines(s: String): Set<String> =
        s.lines().map { it.trim().lowercase() }.filter { PageText.weight(it) >= 30 }.toSet()
}
