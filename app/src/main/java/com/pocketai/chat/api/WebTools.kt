package com.pocketai.chat.api

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pocketai.chat.BuildConfig
import com.pocketai.chat.data.SearchEngine
import com.pocketai.chat.data.hostOf
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
        /** Desktop Chrome, for YouTube's full watch page (its transcript panel lives there). */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        /** Reddit asks API users to name their app; the browser UA is only tried if this one is refused. */
        private val REDDIT_UA = "android:com.pocketai.chat:${BuildConfig.VERSION_NAME} (Pocket AI)"
        private const val MAX_PAGE_CHARS = 15_000
        private const val MAX_DOWNLOAD = 3_000_000L
        /** About 12k tokens of transcript: a 20-minute talk fits whole; longer videos are sampled evenly. */
        private const val TRANSCRIPT_TOKENS = 12_000

        /**
         * Run repeatedly in the hidden YouTube page: returns the transcript once the panel shows it;
         * otherwise taps "Show transcript" (opening the description first if needed). Videos stay paused.
         */
        private const val TRANSCRIPT_JS = """(function(){try{
document.querySelectorAll('video').forEach(function(v){try{v.muted=true;v.pause();}catch(e){}});
var segs=document.querySelectorAll('ytd-transcript-segment-renderer,ytm-transcript-segment-renderer,transcript-segment-view-model');
if(segs.length>2){var out=[];segs.forEach(function(s){out.push((s.innerText||s.textContent||'').replace(/\s+/g,' ').trim());});
return JSON.stringify({u:location.href.slice(0,90),t:(document.title||'').slice(0,60),s:'done',lines:out});}
var panel=document.querySelector('[target-id="engagement-panel-searchable-transcript"] #segments-container,ytd-transcript-renderer,ytm-transcript-renderer');
if(panel&&/\d:\d\d/.test(panel.innerText||''))return JSON.stringify({u:location.href.slice(0,90),t:(document.title||'').slice(0,60),s:'done',text:panel.innerText});
var btn=document.querySelector('ytd-video-description-transcript-section-renderer button,ytm-video-description-transcript-section-renderer button');
if(!btn){var c=document.querySelectorAll('button,[role="button"],tp-yt-paper-item');
for(var i=0;i<c.length;i++){var t=((c[i].getAttribute('aria-label')||'')+' '+(c[i].innerText||'')).toLowerCase();
if(t.indexOf('show transcript')>=0){btn=c[i];break;}}}
if(btn){if(!window.__paT||Date.now()-window.__paT>4000){window.__paT=Date.now();btn.click();}return JSON.stringify({u:location.href.slice(0,90),t:(document.title||'').slice(0,60),s:'clicked'});}
var more=document.querySelector('#description-inline-expander #expand,ytd-text-inline-expander #expand');
if(more&&!window.__paM){window.__paM=1;more.click();return JSON.stringify({u:location.href.slice(0,90),t:(document.title||'').slice(0,60),s:'expanded'});}
var ready=!!document.querySelector('ytd-watch-metadata #title,ytd-watch-flexy #description');
return JSON.stringify({u:location.href.slice(0,90),t:(document.title||'').slice(0,60),s:ready?'nobutton':'loading'});
}catch(e){return JSON.stringify({u:location.href.slice(0,90),t:(document.title||'').slice(0,60),s:'error',e:String(e)});}})()"""

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
                        "Open a web page and return its readable text. Use it on links the user shares and on search results " +
                            "you want to read. Reddit links return the post, the page it links to and the top-voted comments; " +
                            "YouTube links return the video's details and its transcript when available."
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
     * Reads a page for the AI. Reddit threads and YouTube videos get their own readers; other pages are
     * downloaded and cut down to the article. Pages built by JavaScript (live scoreboards, some news
     * sites) come back nearly empty that way, so those go to DeepSeek's reader or a hidden WebView.
     * [onStatus] reports slow steps ("Reading the comments…").
     */
    suspend fun fetch(url: String, deepSeekKey: String? = null, onStatus: (String) -> Unit = {}): FetchedPage {
        youtubeId(url)?.let { id -> return youtube(id, url, onStatus) }
        YouTubeSearch.query(url)?.let { q -> return youtubeSearch(q) }
        if (Reddit.isReddit(url)) attempt { reddit(url, deepSeekKey, onStatus) }?.let { return it }
        return article(url, deepSeekKey)
    }

    /** Like runCatching, but pressing Stop (cancellation) still stops the reply. */
    private inline fun <T> attempt(block: () -> T?): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun article(url: String, deepSeekKey: String?): FetchedPage {
        val fast = runCatching { withContext(Dispatchers.IO) { download(url) } }
        val page = fast.getOrNull()
        if (page != null && (page.isArticle || PageText.weight(page.text) >= 1_500)) return page.toPage()
        var best: FetchedPage? = page?.toPage()
        fun weight(p: FetchedPage?) = p?.let { PageText.weight(it.text) } ?: 0
        if (!deepSeekKey.isNullOrBlank()) {
            runCatching { withContext(Dispatchers.IO) { deepSeekFetch(url, deepSeekKey) } }.getOrNull()
                ?.takeIf { weight(it) >= 200 && weight(it) > weight(best) }
                ?.let { if (weight(it) >= PageText.MIN_ARTICLE) return it else best = it }
        }
        val rendered = render(url)
        if (rendered != null && weight(rendered) > weight(best)) return rendered
        return best ?: throw (fast.exceptionOrNull() ?: IOException("Could not read $url"))
    }

    private class Downloaded(val title: String, val url: String, val text: String, val isArticle: Boolean) {
        fun toPage() = FetchedPage(title, url, text)
    }

    private fun download(url: String): Downloaded {
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
            if (!ct.contains("html") && ct.isNotEmpty()) return Downloaded(finalUrl, finalUrl, raw.take(MAX_PAGE_CHARS), false)
            val res = PageText.extract(raw, finalUrl, MAX_PAGE_CHARS)
            return Downloaded(res.title, finalUrl, res.text, res.isArticle)
        }
    }

    // ------------------------------------------------------------------ Reddit

    /** A Reddit thread: the post, what it links to, and the top-voted comments. Null if it couldn't be read. */
    private suspend fun reddit(url: String, deepSeekKey: String?, onStatus: (String) -> Unit): FetchedPage? {
        onStatus("Reading the Reddit thread…")
        val thread = withContext(Dispatchers.IO) {
            val link = if (Reddit.isShortLink(url)) resolveRedirect(url) else url
            val jsonUrl = Reddit.jsonUrl(link) ?: return@withContext null
            val fromJson = listOf(REDDIT_UA, UA).firstNotNullOfOrNull { ua ->
                runCatching { getText(jsonUrl, ua, "application/json") }.getOrNull()?.let { Reddit.parse(it) }
            }
            val t = fromJson ?: runCatching { getText(link, UA, "text/html") }.getOrNull()?.let { Reddit.parseHtml(it, link) }
            t?.copy(singleCommentThread = jsonUrl.contains("context=")) to link
        } ?: return null
        val (t, link) = thread
        if (t == null) return null
        val linked = t.post.linkUrl?.takeIf { Reddit.isReadableLink(it) }?.let { target ->
            onStatus("Reading the linked page: ${hostOf(target)}")
            attempt { fetch(target, deepSeekKey) }
        }
        val title = t.post.title + if (t.post.subreddit.isNotBlank()) " : ${t.post.subreddit}" else ""
        return FetchedPage(title, link, Reddit.format(t, System.currentTimeMillis(), linked))
    }

    /**
     * The link of the Reddit thread whose title is one of [titles] (lines read from the Reddit app), searched in
     * [subreddit] ("r/NowInTech") when known. Null if none matches.
     */
    suspend fun findRedditThread(subreddit: String?, titles: List<String>): String? = withContext(Dispatchers.IO) {
        for (t in titles.take(3)) {
            // In the subreddit first (precise), then all of Reddit.
            val scopes: List<String?> = if (subreddit != null) listOf(subreddit, null) else listOf(null)
            for (sub in scopes) {
                val url = Reddit.searchUrl(sub, t)
                val raw = listOf(REDDIT_UA, UA).firstNotNullOfOrNull { ua -> runCatching { getText(url, ua, "application/json") }.getOrNull() }
                    ?: continue
                Reddit.parseSearch(raw).firstOrNull { PageText.sameTitle(t, it.first) }?.let { return@withContext it.second }
            }
        }
        null
    }

    /** Follows a short link's redirects without downloading the page. */
    private fun resolveRedirect(url: String): String {
        var current = url
        val noFollow = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        repeat(5) {
            val req = Request.Builder().url(current).header("User-Agent", UA).build()
            val next = noFollow.newCall(req).execute().use { r ->
                if (r.code in 300..399) r.header("Location")?.let { loc -> r.request.url.resolve(loc)?.toString() } else null
            } ?: return current
            current = next
        }
        return current
    }

    private fun getText(url: String, ua: String, accept: String): String {
        val req = Request.Builder().url(url).header("User-Agent", ua).header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} from $url")
            return r.body?.string().orEmpty()
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

    /** A video's title, channel, date, description and, when YouTube shows one, its transcript. */
    private suspend fun youtube(id: String, original: String, onStatus: (String) -> Unit): FetchedPage {
        val info = withContext(Dispatchers.IO) { youtubeInfo(id) }
        var transcript = info.captionsUrl?.let { u -> withContext(Dispatchers.IO) { runCatching { captions(u) }.getOrNull() } }
        if (transcript == null) {
            onStatus("Opening the video's transcript…")
            transcript = transcriptFromPage(id)?.let { Transcript.format(it, TRANSCRIPT_TOKENS) }
        }
        val text = buildString {
            append(youtubeGuide(id)).append("\n\n")
            append("YouTube video: ${info.title}\n")
            if (info.channel.isNotEmpty()) append("Channel: ${info.channel}\n")
            if (info.seconds > 0) append("Length: ${info.seconds / 60} min ${info.seconds % 60} s\n")
            info.published?.let { append("Published: $it\n") }
            info.views?.let { append("Views: ${"%,d".format(it)}\n") }
            append("\nDescription:\n${info.description.take(3000)}\n\n")
            if (!transcript.isNullOrBlank()) append("Transcript ([m:ss] = time in the video):\n$transcript")
            else append(
                "(No transcript could be read for this video (${transcriptProblem ?: "YouTube refused the captions"}). " +
                    "Summarize from the title, description and chapters, and " +
                    "say in one short line at the end that the transcript wasn't available, with the reason in brackets.)"
            )
        }
        return FetchedPage(info.title, original, text)
    }

    // 2.20: the owner found topic-grouped summaries ("The rumor itself:", "Why it would matter:", "Skepticism:") useless
    // for videos and asked for the video's own order, the way it was said. Keep it chronological.
    private fun youtubeGuide(id: String) =
        "[Pocket AI: this is a YouTube video's details and, when available, its transcript. Unless the user asks for " +
            "something else, summarize it in the order it's said: one or two sentences on what the video is, then walk " +
            "through it from start to end, one short paragraph or bullet per part, each starting with its time linked " +
            "to that spot, like [4:05](https://youtu.be/$id?t=245). Say who says what (host, guest, or the speakers' " +
            "names when known), keep their actual points, examples, numbers and memorable lines, and note where they " +
            "disagree. Don't regroup it by theme or add labels like 'Why it matters:' or 'Conclusion:'; end with how " +
            "the video ends. Longer videos get more parts, but keep each one brief. The video's comments aren't " +
            "included: Pocket AI can't read YouTube comments, so don't offer to.]"

    private class VideoInfo(
        val title: String, val channel: String, val seconds: Long, val description: String,
        val published: String?, val views: Long?, val captionsUrl: String?,
    )

    private fun youtubeInfo(id: String): VideoInfo {
        val watch = "https://www.youtube.com/watch?v=$id&hl=en"
        val html = http.newCall(
            Request.Builder().url(watch)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
                .build()
        ).execute().use { r ->
            if (!r.isSuccessful) throw IOException("YouTube returned HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val player = YouTubeSearch.jsonAfter(html, "ytInitialPlayerResponse")
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: throw IOException("Couldn't read this YouTube page.")
        val details = player["videoDetails"] as? JsonObject
        val micro = (player["microformat"] as? JsonObject)?.get("playerMicroformatRenderer") as? JsonObject
        val tracks = ((player["captions"] as? JsonObject)
            ?.get("playerCaptionsTracklistRenderer") as? JsonObject)
            ?.get("captionTracks") as? JsonArray
        val trackList = tracks.orEmpty().mapNotNull { it as? JsonObject }
        val track = trackList.firstOrNull { it.str("languageCode")?.startsWith("en") == true && it.str("kind") != "asr" }
            ?: trackList.firstOrNull { it.str("languageCode")?.startsWith("en") == true }
            ?: trackList.firstOrNull()
        return VideoInfo(
            title = details?.str("title") ?: "YouTube video",
            channel = details?.str("author").orEmpty(),
            seconds = details?.str("lengthSeconds")?.toLongOrNull() ?: 0,
            description = details?.str("shortDescription").orEmpty(),
            published = (micro?.str("publishDate") ?: micro?.str("uploadDate"))?.take(10),
            views = details?.str("viewCount")?.toLongOrNull(),
            captionsUrl = track?.str("baseUrl"),
        )
    }

    /** The caption track as timed lines, or null when YouTube refuses it (it usually does since 2025). */
    private fun captions(baseUrl: String): String? {
        val url = if (baseUrl.contains("fmt=")) baseUrl else "$baseUrl&fmt=json3"
        val raw = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { it.body?.string().orEmpty() }
        if (raw.isBlank()) return null
        val lines = if (raw.trimStart().startsWith("{")) {
            val events = json.parseToJsonElement(raw).jsonObject["events"] as? JsonArray
            events.orEmpty().mapNotNull { ev ->
                val o = ev as? JsonObject ?: return@mapNotNull null
                val t = (o["tStartMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
                val text = (o["segs"] as? JsonArray)?.joinToString("") { seg -> (seg as? JsonObject)?.str("utf8").orEmpty() }
                    ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                if (text.isEmpty()) null else "${t / 60_000}:${((t / 1000) % 60).toString().padStart(2, '0')} $text"
            }
        } else {
            Jsoup.parse(raw).select("text").mapNotNull { el ->
                val t = el.attr("start").toDoubleOrNull()?.toLong() ?: return@mapNotNull null
                "${t / 60}:${(t % 60).toString().padStart(2, '0')} ${el.text()}"
            }
        }
        return Transcript.format(lines, TRANSCRIPT_TOKENS)
    }

    /**
     * Opens the video's page in a hidden WebView and taps YouTube's own "Show transcript" button, the
     * same thing a person does in a browser, then reads the transcript panel. Null if there's none.
     */
    @SuppressLint("SetJavaScriptEnabled")
    /** Why the last transcript attempt failed (shown to the AI, which passes it on), e.g. "the page has no transcript button". */
    @Volatile private var transcriptProblem: String? = null

    private suspend fun transcriptFromPage(id: String): List<String>? = withContext(Dispatchers.Main) {
        transcriptProblem = "timed out loading YouTube's page"
        withTimeoutOrNull(22_000) {
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                val wv = WebView(appContext)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.mediaPlaybackRequiresUserGesture = true
                wv.settings.userAgentString = DESKTOP_UA
                wv.layout(0, 0, 1280, 2400)
                runCatching { CookieManager.getInstance().setCookie("https://www.youtube.com", "SOCS=CAI; path=/; domain=.youtube.com") }
                var done = false
                var started = false
                var polls = 0
                var noButton = 0
                fun finish(lines: List<String>?) {
                    if (done) return
                    done = true
                    runCatching { wv.stopLoading(); wv.destroy() }
                    if (cont.isActive) cont.resume(lines)
                }
                lateinit var poll: Runnable
                poll = Runnable {
                    if (done) return@Runnable
                    polls++
                    wv.evaluateJavascript(TRANSCRIPT_JS) { res ->
                        if (done) return@evaluateJavascript
                        val o = runCatching { json.parseToJsonElement(json.decodeFromString<String>(res)).jsonObject }.getOrNull()
                        val state = o?.str("s")
                        val lines = (o?.get("lines") as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            ?: o?.str("text")?.let { splitPanel(it) }
                        transcriptProblem = when (state) {
                            "nobutton" -> "the page has no Show transcript button"
                            "clicked" -> "tapped Show transcript but no lines appeared"
                            "expanded" -> "opened the description but found no Show transcript button"
                            "error" -> "page script error: ${o?.str("e")}"
                            else -> "the video page didn't finish loading"
                        } + " (page: ${o?.str("t") ?: "?"} · ${o?.str("u") ?: "?"})"
                        when {
                            state == "done" && !lines.isNullOrEmpty() -> finish(lines)
                            state == "nobutton" && ++noButton >= 8 -> finish(null) // this video has no transcript
                            polls >= 30 -> finish(null)
                            else -> main.postDelayed(poll, 600)
                        }
                    }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) {
                        if (!started) { started = true; main.postDelayed(poll, 1500) }
                    }
                }
                main.postDelayed({ if (!started && !done) { started = true; poll.run() } }, 9_000)
                cont.invokeOnCancellation { main.post { if (!done) { done = true; runCatching { wv.destroy() } } } }
                wv.loadUrl("https://www.youtube.com/watch?v=$id&hl=en")
            }
        }
    }

    /** The transcript panel's text ("0:00\nWords\n0:04\nMore words") as "m:ss words" lines. */
    private fun splitPanel(text: String): List<String> {
        val out = mutableListOf<String>()
        var ts: String? = null
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (Regex("""^(\d{1,2}:)?\d{1,2}:\d{2}$""").matches(l)) ts = l
            else if (ts != null) { out += "$ts $l"; ts = null }
        }
        return out
    }

    /** YouTube's own search page, as a list of videos with links (used to find a video seen in the YouTube app). */
    private suspend fun youtubeSearch(q: String): FetchedPage = withContext(Dispatchers.IO) {
        val html = youtubeSearchPage(q)
        FetchedPage("YouTube search: $q", YouTubeSearch.searchUrl(q), YouTubeSearch.format(q, YouTubeSearch.parse(html)))
    }

    private fun youtubeSearchPage(q: String): String = http.newCall(
        Request.Builder().url(YouTubeSearch.searchUrl(q))
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
            .build()
    ).execute().use { r ->
        if (!r.isSuccessful) throw IOException("YouTube search returned HTTP ${r.code}")
        r.body?.string().orEmpty()
    }

    /** The link of the video whose title is one of [titles] (lines read from the YouTube app), or null. */
    suspend fun findYouTubeVideo(titles: List<String>): String? = withContext(Dispatchers.IO) {
        for (t in titles.take(3)) {
            val videos = runCatching { YouTubeSearch.parse(youtubeSearchPage(t)) }.getOrNull() ?: continue
            YouTubeSearch.bestMatch(t, videos)?.let { return@withContext it.link }
        }
        null
    }

    private fun cleanText(s: String): String = PageText.clean(s, MAX_PAGE_CHARS)

    /**
     * Loads the page in an off-screen WebView so JavaScript runs, then cuts the finished page down to
     * the article (or its visible text when it isn't an article).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun render(url: String): FetchedPage? = withContext(Dispatchers.Main) {
        val raw = withTimeoutOrNull(14_000) {
            suspendCancellableCoroutine<JsonObject?> { cont ->
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
                        "(function(){return JSON.stringify({t:document.title||'',u:location.href," +
                            "b:document.body?document.body.innerText:'',h:document.documentElement.outerHTML.slice(0,3000000)})})()"
                    ) { res ->
                        val o = runCatching { json.parseToJsonElement(json.decodeFromString<String>(res)).jsonObject }.getOrNull()
                        wv.destroy()
                        if (cont.isActive) cont.resume(o)
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
        } ?: return@withContext null
        val finalUrl = raw.str("u") ?: url
        val innerText = cleanText(raw.str("b").orEmpty())
        withContext(Dispatchers.Default) {
            val html = raw.str("h").orEmpty()
            val res = if (html.isNotBlank()) runCatching { PageText.extract(html, finalUrl, MAX_PAGE_CHARS) }.getOrNull() else null
            when {
                res != null && res.isArticle -> FetchedPage(res.title, finalUrl, res.text)
                innerText.isNotBlank() -> FetchedPage(raw.str("t").orEmpty().ifBlank { url }, finalUrl, innerText)
                else -> null
            }
        }
    }
}
