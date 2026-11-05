package com.pocketai.chat.api

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pocketai.chat.data.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.min

data class SearchHit(val title: String, val url: String, val snippet: String)
data class FetchedPage(val title: String, val url: String, val text: String)

/**
 * The AI's web tools. They run on the phone: the model asks for a search or a page,
 * the app does it, and the text goes back to the model.
 */
class WebTools(private val http: OkHttpClient, private val appContext: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 16; SM-F966U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        private const val MAX_PAGE_CHARS = 15_000
        private const val MAX_DOWNLOAD = 3_000_000L

        val TOOL_DEFS: JsonArray = buildJsonArray {
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "web_search")
                    put(
                        "description",
                        "Search the web. Returns the top results with titles, URLs and a summary of each page. Use it for " +
                            "news, sports scores, prices, weather, recent events, or any fact you are not sure about. " +
                            "Write one precise query with exact dates (e.g. 'NBA scores November 3 2026'). The summaries " +
                            "often already contain the answer; only call fetch_url if they don't."
                    )
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "The search query. Include dates or places when relevant.")
                            }
                        }
                        putJsonArray("required") { add("query") }
                    }
                }
            }
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "fetch_url")
                    put(
                        "description",
                        "Open a web page and return its readable text. Use it on links the user shares and on search results you want to read."
                    )
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("url") {
                                put("type", "string")
                                put("description", "Full http(s) URL of the page.")
                            }
                        }
                        putJsonArray("required") { add("url") }
                    }
                }
            }
        }
    }

    suspend fun search(query: String, engine: SearchEngine, key: String): List<SearchHit> = withContext(Dispatchers.IO) {
        when (engine) {
            SearchEngine.BRAVE -> if (key.isBlank()) throw IOException("Brave Search needs an API key in Settings.") else brave(query, key)
            SearchEngine.TAVILY -> if (key.isBlank()) throw IOException("Tavily needs an API key in Settings.") else tavily(query, key)
            SearchEngine.DEEPSEEK -> if (key.isBlank()) throw IOException("DeepSeek Search needs a DeepSeek API key in Settings.") else deepSeek(query, key)
            SearchEngine.DUCKDUCKGO -> duckDuckGo(query)
        }
    }

    /**
     * DeepSeek Search API (POST https://api.deepseek.com/v1/tools/search). Each result has a
     * 600-800 character `summary` written for models, so the AI usually doesn't need to open pages.
     * Results are ordered by DeepSeek's authority + relevance scores so big, reliable sites come first.
     */
    private fun deepSeek(q: String, key: String): List<SearchHit> {
        val body = buildJsonObject {
            put("query", q.take(128))
            put("count", 10)
            put("need_content", false)
        }
        val req = Request.Builder().url("https://api.deepseek.com/v1/tools/search")
            .header("Authorization", "Bearer ${key.trim()}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("DeepSeek Search HTTP ${r.code}: ${deepSeekError(raw)}")
            val results = json.parseToJsonElement(raw).jsonObject["results"] as? JsonArray
            return results.orEmpty().mapNotNull { it as? JsonObject }
                .sortedByDescending { o -> num(o, "authority") + num(o, "rerank_score") }
                .take(6)
                .mapNotNull { o ->
                    val url = o.str("url") ?: return@mapNotNull null
                    val date = o.str("date")?.take(10)?.let { " (published $it)" }.orEmpty()
                    val site = o.str("site_name")?.let { "$it - " }.orEmpty()
                    SearchHit(site + o.str("title").orEmpty() + date, url, o.str("summary")?.ifBlank { null } ?: o.str("snippet").orEmpty())
                }
        }
    }

    private fun num(o: JsonObject, key: String): Double =
        (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0

    /** DeepSeek Fetch API: returns a page as Markdown, read on DeepSeek's side (fast, handles script-built sites). */
    private fun deepSeekFetch(url: String, key: String): FetchedPage {
        val body = buildJsonObject { put("url", url) }
        val req = Request.Builder().url("https://api.deepseek.com/v1/tools/fetch")
            .header("Authorization", "Bearer ${key.trim()}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("DeepSeek Fetch HTTP ${r.code}: ${deepSeekError(raw)}")
            val o = json.parseToJsonElement(raw).jsonObject
            return FetchedPage(o.str("title").orEmpty().ifBlank { url }, url, cleanText(o.str("content").orEmpty()))
        }
    }

    private fun deepSeekError(raw: String): String = runCatching {
        (json.parseToJsonElement(raw).jsonObject["error"] as? JsonObject)?.str("message")
    }.getOrNull() ?: raw.take(200)

    private fun duckDuckGo(q: String): List<SearchHit> {
        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/")
            .header("User-Agent", UA)
            .post(FormBody.Builder().add("q", q).build())
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("DuckDuckGo returned HTTP ${r.code}")
            val doc = Jsoup.parse(r.body?.string().orEmpty(), "https://html.duckduckgo.com/")
            val hits = doc.select("div.result").mapNotNull { el ->
                if (el.hasClass("result--ad")) return@mapNotNull null
                val a = el.selectFirst("a.result__a") ?: return@mapNotNull null
                val url = unwrapDdg(a.attr("href"))
                if (!url.startsWith("http")) return@mapNotNull null
                SearchHit(a.text(), url, el.selectFirst(".result__snippet")?.text().orEmpty())
            }.take(8)
            if (hits.isEmpty()) throw IOException(
                "DuckDuckGo returned no results (it may be rate-limiting). A free Brave or Tavily key in Settings is more reliable."
            )
            return hits
        }
    }

    private fun unwrapDdg(href: String): String {
        val full = if (href.startsWith("//")) "https:$href" else href
        return full.toHttpUrlOrNull()?.queryParameter("uddg") ?: full
    }

    private fun brave(q: String, key: String): List<SearchHit> {
        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("count", "8").build()
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", key.trim())
            .build()
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Brave Search HTTP ${r.code}: ${raw.take(200)}")
            val results = (json.parseToJsonElement(raw).jsonObject["web"] as? JsonObject)?.get("results") as? JsonArray
            return results.orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                SearchHit(o.str("title").orEmpty(), o.str("url") ?: return@mapNotNull null, Jsoup.parse(o.str("description").orEmpty()).text())
            }
        }
    }

    private fun tavily(q: String, key: String): List<SearchHit> {
        val body = buildJsonObject {
            put("query", q)
            put("max_results", 8)
            put("api_key", key.trim())
        }
        val req = Request.Builder().url("https://api.tavily.com/search")
            .header("Authorization", "Bearer ${key.trim()}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Tavily HTTP ${r.code}: ${raw.take(200)}")
            val results = json.parseToJsonElement(raw).jsonObject["results"] as? JsonArray
            return results.orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                SearchHit(o.str("title").orEmpty(), o.str("url") ?: return@mapNotNull null, o.str("content").orEmpty())
            }
        }
    }

    /**
     * Downloads a page and extracts its text. Pages built by JavaScript (live scoreboards, many
     * news sites) come back nearly empty that way, so those are re-opened in a hidden browser.
     */
    suspend fun fetch(url: String, deepSeekKey: String? = null): FetchedPage {
        youtubeId(url)?.let { id -> return withContext(Dispatchers.IO) { youtube(id, url) } }
        if (!deepSeekKey.isNullOrBlank()) {
            runCatching { withContext(Dispatchers.IO) { deepSeekFetch(url, deepSeekKey) } }
                .getOrNull()?.takeIf { it.text.length >= 200 }?.let { return it }
        }
        val fast = runCatching { withContext(Dispatchers.IO) { download(url) } }
        val page = fast.getOrNull()
        if (page != null && page.text.length >= 500) return page
        val rendered = render(url)
        if (rendered != null && rendered.text.length > (page?.text?.length ?: 0)) return rendered
        return page ?: throw (fast.exceptionOrNull() ?: IOException("Could not read $url"))
    }

    private fun download(url: String): FetchedPage {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} from $url")
            val ct = r.header("Content-Type").orEmpty().lowercase()
            if (ct.startsWith("image/") || ct.contains("pdf") || ct.startsWith("video/") || ct.startsWith("audio/")) {
                throw IOException("That link is a file ($ct), not a web page.")
            }
            val body = r.body ?: throw IOException("Empty page")
            val src = body.source()
            src.request(MAX_DOWNLOAD)
            val bytes = src.buffer.readByteArray(min(src.buffer.size, MAX_DOWNLOAD))
            val charset = body.contentType()?.charset() ?: Charsets.UTF_8
            val raw = String(bytes, charset)
            val finalUrl = r.request.url.toString()
            if (!ct.contains("html") && ct.isNotEmpty()) return FetchedPage(finalUrl, finalUrl, raw.take(MAX_PAGE_CHARS))
            return FetchedPage(titleOf(raw, finalUrl), finalUrl, htmlToText(raw, finalUrl))
        }
    }

    // ------------------------------------------------------------------ YouTube

    private fun youtubeId(url: String): String? {
        val m = Regex(
            """(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/)|youtu\.be/)([A-Za-z0-9_-]{11})""",
            RegexOption.IGNORE_CASE,
        ).find(url)
        return m?.groupValues?.get(1)
    }

    /** Reads a video's title, description and caption transcript (best effort). */
    private fun youtube(id: String, original: String): FetchedPage {
        val watch = "https://www.youtube.com/watch?v=$id&hl=en"
        val html = http.newCall(
            Request.Builder().url(watch)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
                .build()
        ).execute().use { r ->
            if (!r.isSuccessful) throw IOException("YouTube returned HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val player = extractJsonObject(html, "ytInitialPlayerResponse")
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: throw IOException("Couldn't read this YouTube page.")
        val details = player["videoDetails"] as? JsonObject
        val title = details?.str("title") ?: "YouTube video"
        val channel = details?.str("author").orEmpty()
        val seconds = details?.str("lengthSeconds")?.toLongOrNull() ?: 0
        val description = details?.str("shortDescription").orEmpty()

        val tracks = ((player["captions"] as? JsonObject)
            ?.get("playerCaptionsTracklistRenderer") as? JsonObject)
            ?.get("captionTracks") as? JsonArray
        val trackList = tracks.orEmpty().mapNotNull { it as? JsonObject }
        val track = trackList.firstOrNull { it.str("languageCode")?.startsWith("en") == true && it.str("kind") != "asr" }
            ?: trackList.firstOrNull { it.str("languageCode")?.startsWith("en") == true }
            ?: trackList.firstOrNull()
        val transcript = track?.str("baseUrl")?.let { runCatching { captions(it) }.getOrNull() }.orEmpty()

        val text = buildString {
            append("YouTube video: $title\n")
            if (channel.isNotEmpty()) append("Channel: $channel\n")
            if (seconds > 0) append("Length: ${seconds / 60} min ${seconds % 60} s\n")
            append("\nDescription:\n${description.take(3000)}\n\n")
            if (transcript.isNotBlank()) append("Transcript:\n$transcript")
            else append(
                "(No transcript could be loaded for this video. Summarize from the title and description, say that the " +
                    "transcript wasn't available, and tip the user: for a full summary, open the video in Pocket AI's " +
                    "Browser tab, tap \"...more\" then \"Show transcript\", then type \"summarize\" in the browser's message bar.)"
            )
        }
        return FetchedPage(title, original, text.take(60_000))
    }

    private fun captions(baseUrl: String): String {
        val url = if (baseUrl.contains("fmt=")) baseUrl else "$baseUrl&fmt=json3"
        val raw = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { it.body?.string().orEmpty() }
        if (raw.isBlank()) return ""
        if (raw.trimStart().startsWith("{")) {
            val events = json.parseToJsonElement(raw).jsonObject["events"] as? JsonArray
            return events.orEmpty().mapNotNull { ev ->
                ((ev as? JsonObject)?.get("segs") as? JsonArray)?.joinToString("") { seg -> (seg as? JsonObject)?.str("utf8").orEmpty() }
            }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        }
        return Jsoup.parse(raw).select("text").joinToString(" ") { it.text() }.trim()
    }

    /** Finds `marker = {...}` in a page and returns the balanced JSON object text. */
    private fun extractJsonObject(html: String, marker: String): String? {
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

    private fun titleOf(html: String, url: String) = Jsoup.parse(html, url).title().ifBlank { url }

    private fun htmlToText(html: String, url: String): String {
        val doc = Jsoup.parse(html, url)
        doc.select("script,style,noscript,svg,nav,footer,header,aside,form,iframe,button").remove()
        val root = doc.selectFirst("main") ?: doc.selectFirst("article") ?: doc.body() ?: return ""
        root.select("br").append("\\n")
        root.select("p,div,li,h1,h2,h3,h4,h5,h6,tr,section,article,table").prepend("\\n")
        return cleanText(root.text().replace("\\n", "\n"))
    }

    private fun cleanText(s: String): String =
        s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").take(MAX_PAGE_CHARS)

    /** Loads the page in an off-screen WebView so JavaScript runs, then reads document.body.innerText. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun render(url: String): FetchedPage? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(14_000) {
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                val wv = WebView(appContext)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = UA
                wv.layout(0, 0, 1080, 2400)
                var finished = false
                fun finish() {
                    if (finished) return
                    finished = true
                    wv.evaluateJavascript(
                        "(function(){return JSON.stringify({t:document.title||'',u:location.href,b:document.body?document.body.innerText:''})})()"
                    ) { res ->
                        val page = runCatching {
                            val inner = json.decodeFromString<String>(res)
                            val o = json.parseToJsonElement(inner).jsonObject
                            FetchedPage(o.str("t").orEmpty().ifBlank { url }, o.str("u") ?: url, cleanText(o.str("b").orEmpty()))
                        }.getOrNull()
                        wv.destroy()
                        if (cont.isActive) cont.resume(page)
                    }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) {
                        // Give scripts a moment to fill in the page (scores, articles).
                        main.postDelayed({ finish() }, 1500)
                    }
                }
                main.postDelayed({ finish() }, 10_000)
                cont.invokeOnCancellation { main.post { if (!finished) { finished = true; wv.destroy() } } }
                wv.loadUrl(url)
            }
        }
    }
}
