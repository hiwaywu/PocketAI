package com.pocketai.chat.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * YouTube's own search page (youtube.com/results?search_query=…) turned into a short list of videos with their
 * links. The YouTube app never shows a video's link, so when the Pocket AI button is used there, the AI finds the
 * video by the title on screen and then reads it (details + transcript) through fetch_url.
 */
object YouTubeSearch {
    private val json = Json { ignoreUnknownKeys = true }

    data class Video(
        val id: String,
        val title: String,
        val channel: String,
        val length: String?,
        val views: String?,
        val published: String?,
    ) {
        val link: String get() = "https://www.youtube.com/watch?v=$id"
    }

    /** The search words of a youtube.com/results link, or null if it isn't one. */
    fun query(url: String): String? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        if (!u.host.endsWith("youtube.com") || u.encodedPath != "/results") return null
        return (u.queryParameter("search_query") ?: u.queryParameter("q"))?.takeIf { it.isNotBlank() }
    }

    fun searchUrl(q: String): String = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(q, "UTF-8") + "&hl=en"

    /** Videos in the page's ytInitialData, in YouTube's order (ads, channels, playlists and Shorts shelves skipped). */
    fun parse(html: String): List<Video> {
        val raw = jsonAfter(html, "ytInitialData") ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Video>()
        fun walk(el: JsonElement) {
            when (el) {
                is JsonObject -> for ((k, v) in el) {
                    if (k == "videoRenderer" && v is JsonObject) video(v)?.let { if (out.none { o -> o.id == it.id }) out += it }
                    else walk(v)
                }
                is JsonArray -> el.forEach { walk(it) }
                else -> {}
            }
        }
        walk(root)
        return out
    }

    private fun video(o: JsonObject): Video? {
        val id = (o["videoId"] as? JsonPrimitive)?.content ?: return null
        return Video(
            id = id,
            title = text(o["title"]) ?: return null,
            channel = text(o["ownerText"]) ?: text(o["longBylineText"]).orEmpty(),
            length = text(o["lengthText"]),
            views = text(o["viewCountText"]),
            published = text(o["publishedTimeText"]),
        )
    }

    /** YouTube text is either {"simpleText": "…"} or {"runs": [{"text": "…"}, …]}. */
    private fun text(el: JsonElement?): String? {
        val o = el as? JsonObject ?: return null
        (o["simpleText"] as? JsonPrimitive)?.content?.let { return it }
        return (o["runs"] as? JsonArray)?.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty() }
            ?.takeIf { it.isNotBlank() }
    }

    fun format(q: String, videos: List<Video>): String =
        if (videos.isEmpty()) "No YouTube videos found for \"$q\"."
        else "YouTube search results for \"$q\" (call fetch_url on a video's link to read its details and transcript):\n\n" +
            videos.take(8).mapIndexed { i, v ->
                "${i + 1}. ${v.title}\n   Channel: ${v.channel}" +
                    listOfNotNull(v.length, v.views, v.published).joinToString("") { " · $it" } +
                    "\n   ${v.link}"
            }.joinToString("\n")

    /** Finds `marker = {...}` in a page and returns the balanced JSON object text. */
    fun jsonAfter(html: String, marker: String): String? {
        var i = html.indexOf(marker)
        if (i < 0) return null
        i = html.indexOf('{', i)
        if (i < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (j in i until html.length) {
            val c = html[j]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return html.substring(i, j + 1) }
            }
        }
        return null
    }
}
