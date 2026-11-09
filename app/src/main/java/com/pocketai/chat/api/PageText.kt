package com.pocketai.chat.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.dankito.readability4j.extended.Readability4JExtended
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Turns a web page into the text the AI reads: only the article (Mozilla's Readability, like Firefox
 * Reader View) with a line saying which site, who wrote it and when, so the AI knows how fresh it is.
 * Menus, "more headlines" lists and sidebars used to leak into summaries.
 */
object PageText {
    private val json = Json { ignoreUnknownKeys = true }

    /** Below this [weight] the article extraction probably missed the story (or there isn't one). */
    const val MIN_ARTICLE = 500

    /**
     * Text length with Chinese/Japanese/Korean characters counted 2.5×, since one says about as much as
     * 2-3 English letters: a complete 300-character RTHK story weighs about as much as 750 English letters.
     */
    fun weight(s: String): Int {
        val cjk = s.count { it.code in 0x3040..0x9FFF || it.code in 0xAC00..0xD7AF }
        return s.length + cjk * 3 / 2
    }

    class Result(val title: String, val text: String, val isArticle: Boolean)

    fun extract(html: String, url: String, maxChars: Int): Result {
        val doc = Jsoup.parse(html, url)
        val meta = header(doc, url)
        val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null } ?: doc.title()).ifBlank { url }

        // Front pages and lists aren't articles: Readability would keep one block of them and drop the rest,
        // so those get the whole page (minus menus) like before.
        val declared = declaresArticle(doc)
        val article = if (declared || isProbablyReaderable(doc)) runCatching { Readability4JExtended(url, html).parse() }.getOrNull() else null
        var body = article?.articleContent?.let { blockText(it) }.orEmpty()
        var isArticle = weight(body) >= MIN_ARTICLE
        // Many news sites put the whole story in their search-engine data even when the page shows a teaser.
        val ld = ldArticleBody(doc)
        if (ld != null && ld.length > body.length * 1.3 && weight(ld) >= MIN_ARTICLE) {
            body = ld
            isArticle = true
        }
        val whole = if (isArticle && declared) "" else wholePage(doc)
        // A front page with a few long blurbs can pass the test, but then Readability keeps one block and drops
        // most headlines (BBC News' front page: 6,700 → 1,700 characters); keep the whole page then.
        if (!isArticle || (!declared && weight(body) * 10 < weight(whole) * 4)) {
            if (whole.length > body.length) {
                body = whole
                isArticle = false
            }
        }
        val byline = article?.byline?.trim()?.takeIf { it.length in 2..80 && !body.startsWith(it) }
            ?.let { if (it.startsWith("By ", true)) it else "By $it" } ?: meta.author
        val head = listOfNotNull(meta.site, byline?.takeUnless { it.removePrefix("By ").equals(meta.site, true) }, meta.date)
        val text = (if (head.isNotEmpty()) head.joinToString(" · ") + "\n\n" else "") + body
        return Result((article?.title?.trim()?.ifBlank { null } ?: title), clean(text, maxChars), isArticle)
    }

    private class Meta(val site: String?, val author: String?, val date: String?)

    private fun header(doc: Document, url: String): Meta {
        fun m(q: String) = doc.selectFirst(q)?.attr("content")?.trim()?.ifBlank { null }
        val site = m("meta[property=og:site_name]") ?: url.toHttpUrlOrNull()?.host?.removePrefix("www.")
        val author = m("meta[name=author]")?.takeIf { it.length <= 80 && !it.contains("http") }?.let { "By $it" }
        val raw = m("meta[property=article:published_time]") ?: m("meta[name=article:published_time]")
            ?: m("meta[itemprop=datePublished]") ?: m("meta[name=pubdate]") ?: m("meta[name=publish-date]")
            ?: m("meta[name=date]") ?: ldField(doc, "datePublished")
            ?: doc.selectFirst("time[datetime]")?.attr("datetime")?.ifBlank { null }
        val date = raw?.let { d -> Regex("""\d{4}-\d{2}-\d{2}""").find(d)?.value }
            // RTHK: <meta name="livetime" content="202611090221">
            ?: m("meta[name=livetime]")?.let { Regex("""^(\d{4})(\d{2})(\d{2})\d{4}$""").find(it) }?.let { r ->
                r.groupValues.let { "${it[1]}-${it[2]}-${it[3]}" }
            }
        val dated = date?.let { "Published $it" }
        return Meta(site, author, dated)
    }

    /**
     * Whether a title read from an app's screen is the same as a search result's title: they share a long enough
     * stretch of text (Chinese characters count 2.5×), so extra words around either one don't matter.
     */
    fun sameTitle(screen: String, candidate: String): Boolean {
        val a = norm(screen)
        val b = norm(candidate)
        if (b.isEmpty() || weight(a) < 12) return false
        val shorter = if (b.length < a.length) b else a
        return weight(commonRun(a, b)) >= minOf(15.0, weight(shorter) * 0.8)
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The longest stretch of text two strings share. */
    private fun commonRun(a: String, b: String): String {
        var best = 0
        var end = 0
        val prev = IntArray(b.length + 1)
        for (i in 1..a.length) {
            var diag = 0
            for (j in 1..b.length) {
                val up = prev[j]
                prev[j] = if (a[i - 1] == b[j - 1]) diag + 1 else 0
                if (prev[j] > best) { best = prev[j]; end = i }
                diag = up
            }
        }
        return a.substring(end - best, end)
    }

    /** og:type article or schema.org NewsArticle/Article/BlogPosting/Report. */
    private fun declaresArticle(doc: Document): Boolean {
        if (doc.selectFirst("meta[property=og:type]")?.attr("content")?.trim()?.equals("article", true) == true) return true
        return ldObjects(doc).any { o ->
            val t = o["@type"]
            val types = when (t) {
                is JsonPrimitive -> listOf(t.content)
                is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.content }
                else -> emptyList()
            }
            types.any { it.endsWith("Article") || it == "BlogPosting" || it == "Report" }
        }
    }

    private val UNLIKELY = Regex(
        "-ad-|ai2html|banner|breadcrumbs|combx|comment|community|cover-wrap|disqus|extra|footer|gdpr|header|legends|menu|" +
            "related|remark|replies|rss|shoutbox|sidebar|skyscraper|social|sponsor|supplemental|ad-break|agegate|pagination|" +
            "pager|popup|yom-remote",
        RegexOption.IGNORE_CASE,
    )
    private val MAYBE = Regex("and|article|body|column|content|main|mathjax|shadow", RegexOption.IGNORE_CASE)

    /**
     * Mozilla's isProbablyReaderable (what decides whether Firefox offers Reader View): enough long
     * paragraphs outside comment/sidebar boxes. Chinese text says more per character, so its thresholds are lower.
     */
    fun isProbablyReaderable(doc: Document): Boolean {
        val nodes = LinkedHashSet<Element>(doc.select("p, pre, article"))
        doc.select("div > br").forEach { br -> br.parent()?.let { nodes += it } }
        val sample = doc.body().text().take(4000)
        val cjk = sample.count { it.code in 0x3040..0x9FFF || it.code in 0xAC00..0xD7AF } * 3 > sample.length
        val minLength = if (cjk) 60 else 140
        val minScore = if (cjk) 12.0 else 20.0
        var score = 0.0
        for (node in nodes) {
            val style = node.attr("style").replace(" ", "")
            if (style.contains("display:none") || node.hasAttr("hidden")) continue
            val match = node.className() + " " + node.id()
            if (UNLIKELY.containsMatchIn(match) && !MAYBE.containsMatchIn(match)) continue
            if (node.tagName() == "p" && node.parents().any { it.tagName() == "li" }) continue
            val len = node.text().trim().length
            if (len < minLength) continue
            score += kotlin.math.sqrt((len - minLength).toDouble())
            if (score > minScore) return true
        }
        return false
    }

    /** Block-aware text: paragraphs on their own lines, list items as "- ", headings kept. */
    fun blockText(el: Element): String {
        val c = el.clone()
        c.select("script,style,noscript,svg,button,form,iframe,figure figcaption:empty").remove()
        c.select("br").append("\\n")
        c.select("li").prepend("- ")
        c.select("p,div,li,h1,h2,h3,h4,h5,h6,tr,section,article,table,blockquote,pre,figcaption,dd,dt").prepend("\\n")
        return clean(c.text().replace("\\n", "\n"), Int.MAX_VALUE)
    }

    /** The old method: the page's <main>/<article> (or body) minus menus, headers and footers. */
    fun wholePage(doc: Document): String {
        val d = doc.clone()
        d.select("script,style,noscript,svg,nav,footer,header,aside,form,iframe,button").remove()
        val root = d.selectFirst("main") ?: d.selectFirst("article") ?: d.body()
        return blockText(root)
    }

    /** Zero-width and other invisible characters some sites (hk01) fill empty boxes with. */
    private val INVISIBLE = Regex("[\u200B-\u200F\u2060\uFEFF\u00AD]")

    fun clean(s: String, max: Int): String =
        s.replace(INVISIBLE, "").lines().map { it.trim() }.filter { it.isNotEmpty() && it != "-" }.joinToString("\n").let {
            if (it.length <= max) it else it.take(max).substringBeforeLast('\n', it.take(max)) + "\n[… cut for length]"
        }

    private fun ldObjects(doc: Document): List<JsonObject> = doc.select("script[type=application/ld+json]").flatMap { s ->
        val el = runCatching { json.parseToJsonElement(s.data()) }.getOrNull() ?: return@flatMap emptyList()
        flatten(el)
    }

    private fun flatten(el: JsonElement): List<JsonObject> = when (el) {
        is JsonArray -> el.flatMap { flatten(it) }
        is JsonObject -> listOf(el) + ((el["@graph"] as? JsonArray)?.flatMap { flatten(it) } ?: emptyList())
        else -> emptyList()
    }

    private fun ldField(doc: Document, key: String): String? =
        ldObjects(doc).firstNotNullOfOrNull { (it[key] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.ifBlank { null } }

    private fun ldArticleBody(doc: Document): String? =
        ldObjects(doc).mapNotNull { (it["articleBody"] as? JsonPrimitive)?.content }
            .maxByOrNull { it.length }
            ?.let { Jsoup.parse(it).text().takeIf { t -> t.isNotBlank() } }
            ?.replace(Regex("""\s{2,}"""), "\n")
}

/** YouTube transcript lines ("1:24 text") grouped into ~30-second chunks, shortened evenly if too long. */
object Transcript {
    private val LINE = Regex("""^\s*\[?((?:\d{1,2}:)?\d{1,2}:\d{2})]?\s+(.+)$""")

    fun seconds(ts: String): Int = ts.split(":").fold(0) { acc, p -> acc * 60 + (p.toIntOrNull() ?: 0) }

    fun format(lines: List<String>, maxTokens: Int): String? {
        val segs = lines.mapNotNull { l -> LINE.find(l.trim())?.let { it.groupValues[1] to it.groupValues[2].trim() } }
            .filter { it.second.isNotEmpty() }
        if (segs.size < 3) return null
        val chunks = mutableListOf<String>()
        var start = -1
        var label = ""
        val buf = StringBuilder()
        for ((ts, text) in segs) {
            val s = seconds(ts)
            if (start < 0 || s - start >= 30) {
                if (buf.isNotEmpty()) chunks += "[$label] ${buf.toString().trim()}"
                buf.clear()
                start = s
                label = ts
            }
            buf.append(text).append(' ')
        }
        if (buf.isNotEmpty()) chunks += "[$label] ${buf.toString().trim()}"
        val total = chunks.sumOf { tokens(it) }
        if (total <= maxTokens) return chunks.joinToString("\n")
        // Too long: keep an even spread of the video (start, middle and end) rather than just its beginning.
        val keep = maxTokens.toDouble() / total
        var acc = 0.0
        val kept = chunks.filter { acc += keep; if (acc >= 1.0) { acc -= 1.0; true } else false }
        return "(Long video: about ${(keep * 100).toInt()}% of the transcript, spread evenly through it.)\n" + kept.joinToString("\n")
    }

    /** Rough tokens: Chinese/Japanese/Korean characters ~1 each, other text ~4 characters per token. */
    fun tokens(s: String): Int {
        val cjk = s.count { it.code in 0x3040..0x9FFF || it.code in 0xAC00..0xD7AF }
        return cjk + (s.length - cjk) / 4
    }
}
