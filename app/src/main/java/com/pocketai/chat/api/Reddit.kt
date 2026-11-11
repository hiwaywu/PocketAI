package com.pocketai.chat.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class RedditComment(
    val body: String,
    val score: Int?,
    val isOp: Boolean,
    /** The commenter's flair (e.g. "Verified Nurse"), which can mark first-hand or expert replies. */
    val flair: String? = null,
    val controversial: Boolean = false,
    val replies: List<RedditComment> = emptyList(),
)

data class RedditPost(
    val subreddit: String,
    val title: String,
    val author: String,
    val createdMs: Long? = null,
    val score: Int? = null,
    val upvoteRatio: Double? = null,
    val numComments: Int? = null,
    val flair: String? = null,
    val selfText: String = "",
    /** What the post points to: an article, a video or an image. Null for text posts. */
    val linkUrl: String? = null,
    /** "text", "link", "image", "gallery", "video", "poll". */
    val kind: String = "text",
    /** Extra detail about the media, e.g. "1:12 long" or "6 images". */
    val mediaNote: String? = null,
    val poll: List<Pair<String, Int?>> = emptyList(),
    val crosspostFrom: String? = null,
    val nsfw: Boolean = false,
    /** The post's picture(s) (image, gallery, or a video's preview frame), shown to the AI when it can see images. */
    val images: List<String> = emptyList(),
)

data class RedditThread(
    val post: RedditPost,
    val comments: List<RedditComment>,
    /** True when the link pointed at one comment, so only that part of the thread was loaded. */
    val singleCommentThread: Boolean = false,
)

/**
 * Reads Reddit threads properly: the post, what it links to, and the top-voted comments with their
 * upvotes, so the AI can tell what the thread is about and what the room thinks. Before 2.2 the AI got
 * the whole web page, where recommended posts and sidebars drowned out the actual comments.
 */
object Reddit {
    private val json = Json { ignoreUnknownKeys = true }
    private val HOST = Regex("""^https?://([a-z0-9-]+\.)*(reddit\.com|redd\.it)(/|$)""", RegexOption.IGNORE_CASE)
    private const val QUERY = "sort=top&limit=200&depth=5&raw_json=1"

    /** Characters of comments sent to the AI (about 3,500 tokens): enough to judge the mood of a big thread. */
    const val COMMENT_BUDGET = 14_000
    /** Characters of the article a link post points to. */
    const val LINKED_BUDGET = 8_000

    fun isReddit(url: String): Boolean = HOST.containsMatchIn(url.trim())

    /** Share links (reddit.com/r/x/s/AbC123) and redd.it short links redirect to the real thread first. */
    fun isShortLink(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        val seg = u.pathSegments.filter { it.isNotEmpty() }
        return (seg.size >= 3 && seg[0] == "r" && seg[2] == "s") || (u.host.endsWith("redd.it") && seg.size == 1 && seg[0].length > 7)
    }

    /** Reddit's JSON for a thread link, or null if the link isn't a thread (e.g. a subreddit's front page). */
    fun jsonUrl(url: String): String? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        val seg = u.pathSegments.filter { it.isNotEmpty() }
        if (u.host.endsWith("redd.it") && seg.size == 1) return "https://www.reddit.com/comments/${seg[0]}/.json?$QUERY"
        val g = seg.indexOf("gallery")
        if (g >= 0 && g + 1 < seg.size) return "https://www.reddit.com/comments/${seg[g + 1]}/.json?$QUERY"
        val i = seg.indexOf("comments")
        if (i < 0 || i + 1 >= seg.size) return null
        // comments/<post>/<slug>/<comment>: a link to one comment, shown with a little context above it.
        val path = seg.take(minOf(seg.size, i + 4)).joinToString("/")
        val single = seg.size >= i + 4
        return "https://www.reddit.com/$path/.json?$QUERY" + if (single) "&context=3" else ""
    }

    /** Whether a link post's target is worth reading too (an article or video, not Reddit itself or a bare image). */
    fun isReadableLink(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        val host = u.host.lowercase()
        if (isReddit(url) || host.endsWith("redd.it") || host.endsWith("redditmedia.com") || host.endsWith("redditstatic.com")) return false
        if (host == "i.imgur.com" || host == "imgur.com" || host.endsWith(".gfycat.com") || host == "gfycat.com") return false
        val path = u.encodedPath.lowercase()
        return MEDIA_EXT.none { path.endsWith(it) }
    }

    /** Pictures from one post shown to the AI (each costs about a thousand tokens). */
    const val MAX_IMAGES = 3
    private val MEDIA_EXT = listOf(".jpg", ".jpeg", ".png", ".gif", ".gifv", ".webp", ".mp4", ".webm", ".mov", ".mp3", ".pdf")

    /** Reddit's search (JSON), to find a thread seen in the Reddit app by its title. */
    fun searchUrl(subreddit: String?, title: String): String {
        val q = java.net.URLEncoder.encode(title.take(200), "UTF-8")
        return if (subreddit != null) "https://www.reddit.com/$subreddit/search.json?q=$q&restrict_sr=1&sort=relevance&t=all&limit=10&raw_json=1"
        else "https://www.reddit.com/search.json?q=$q&sort=relevance&t=all&limit=10&raw_json=1"
    }

    /** Search results → (title, thread link). */
    fun parseSearch(raw: String): List<Pair<String, String>> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        return root.children().mapNotNull { c ->
            val d = c.data() ?: return@mapNotNull null
            val title = d.s("title") ?: return@mapNotNull null
            val link = d.s("permalink")?.let { "https://www.reddit.com$it" } ?: return@mapNotNull null
            title to link
        }
    }

    // ------------------------------------------------------------------ Reddit's JSON

    fun parse(raw: String): RedditThread? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return null
        val postData = root.getOrNull(0)?.children()?.firstOrNull()?.data() ?: return null
        val post = parsePost(postData)
        val comments = root.getOrNull(1)?.children().orEmpty().mapNotNull { parseComment(it, post.author) }
        return RedditThread(post, comments)
    }

    private fun parsePost(d: JsonObject): RedditPost {
        val parent = (d["crosspost_parent_list"] as? JsonArray)?.firstOrNull() as? JsonObject
        val c = parent ?: d // content comes from the original post of a crosspost
        val url = c.s("url_overridden_by_dest") ?: c.s("url")
        val domain = c.s("domain").orEmpty().lowercase()
        val hint = c.s("post_hint").orEmpty()
        val video = (c["media"] as? JsonObject)?.get("reddit_video") as? JsonObject
            ?: ((c["secure_media"] as? JsonObject)?.get("reddit_video") as? JsonObject)
        val pollOptions = ((c["poll_data"] as? JsonObject)?.get("options") as? JsonArray)?.mapNotNull { o ->
            (o as? JsonObject)?.let { (it.s("text") ?: return@mapNotNull null) to it.i("vote_count") }
        }.orEmpty()
        val galleryCount = ((c["gallery_data"] as? JsonObject)?.get("items") as? JsonArray)?.size
        val kind: String
        var linkUrl: String? = null
        var note: String? = null
        when {
            pollOptions.isNotEmpty() -> kind = "poll"
            c.b("is_gallery") == true || galleryCount != null -> { kind = "gallery"; note = galleryCount?.let { "$it images" } }
            c.b("is_video") == true || video != null || domain == "v.redd.it" || hint == "hosted:video" -> {
                kind = "video"
                note = video?.i("duration")?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')} long" }
            }
            hint == "image" || domain == "i.redd.it" || (url != null && MEDIA_EXT.take(5).any { url.lowercase().substringBefore('?').endsWith(it) }) -> kind = "image"
            c.b("is_self") == true || url == null || isReddit(url) && url.contains("/comments/") && parent == null -> kind = "text"
            else -> { kind = if (hint == "rich:video") "video link" else "link"; linkUrl = url }
        }
        // 2.20: "what is it" posts are all about the picture, which the AI couldn't see.
        val preview = ((((c["preview"] as? JsonObject)?.get("images") as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("source") as? JsonObject)?.s("url")
        val gallery = ((c["gallery_data"] as? JsonObject)?.get("items") as? JsonArray).orEmpty().mapNotNull { item ->
            val id = (item as? JsonObject)?.s("media_id") ?: return@mapNotNull null
            (((c["media_metadata"] as? JsonObject)?.get(id) as? JsonObject)?.get("s") as? JsonObject)?.s("u")
        }
        val images = when (kind) {
            "image" -> listOfNotNull(url?.takeIf { u -> MEDIA_EXT.take(5).any { u.lowercase().substringBefore('?').endsWith(it) } } ?: preview)
            "gallery" -> gallery
            "video", "text" -> listOfNotNull(preview)
            else -> emptyList()
        }.map { it.replace("&amp;", "&") }.distinct().take(MAX_IMAGES)
        return RedditPost(
            images = images,
            subreddit = d.s("subreddit_name_prefixed") ?: d.s("subreddit")?.let { "r/$it" }.orEmpty(),
            title = d.s("title").orEmpty(),
            author = d.s("author").orEmpty(),
            createdMs = d.d("created_utc")?.let { (it * 1000).toLong() },
            score = d.i("score"),
            upvoteRatio = d.d("upvote_ratio"),
            numComments = d.i("num_comments"),
            flair = d.s("link_flair_text")?.takeIf { it.isNotBlank() },
            selfText = c.s("selftext").orEmpty().trim(),
            linkUrl = linkUrl,
            kind = kind,
            mediaNote = note,
            poll = pollOptions,
            crosspostFrom = parent?.s("subreddit_name_prefixed"),
            nsfw = d.b("over_18") == true,
        )
    }

    private fun parseComment(o: JsonObject, opName: String): RedditComment? {
        if (o.s("kind") != "t1") return null
        val d = o.data() ?: return null
        val author = d.s("author").orEmpty()
        // Skip bots and pinned mod notices; they say nothing about what people think.
        if (d.b("stickied") == true || author == "AutoModerator" || d.s("distinguished") == "moderator") return null
        val replies = ((d["replies"] as? JsonObject)?.children().orEmpty()).mapNotNull { parseComment(it, opName) }
        val body = d.s("body").orEmpty().trim()
        val gone = body == "[deleted]" || body == "[removed]" || body.isEmpty()
        if (gone && replies.isEmpty()) return null
        return RedditComment(
            body = if (gone) "[deleted]" else body,
            score = d.i("score").takeUnless { d.b("score_hidden") == true },
            isOp = author.isNotEmpty() && author == opName && author != "[deleted]",
            flair = d.s("author_flair_text")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 },
            controversial = (d.i("controversiality") ?: 0) > 0,
            replies = replies,
        )
    }

    // ------------------------------------------------------------------ the web page (fallback)

    /**
     * Reddit's own web page (shreddit), used when its JSON is refused. The page carries the post and the
     * first batch of comments as <shreddit-post> / <shreddit-comment> elements.
     */
    fun parseHtml(html: String, url: String): RedditThread? {
        val doc = Jsoup.parse(html, url)
        val p = doc.selectFirst("shreddit-post") ?: return null
        val author = p.attr("author")
        val postType = p.attr("post-type").lowercase()
        val href = p.attr("content-href").ifBlank { null }
        val kind = when (postType) {
            "image" -> "image"
            "gallery" -> "gallery"
            "video" -> "video"
            "link" -> if (href != null && isReadableLink(href)) "link" else "image"
            "poll" -> "poll"
            else -> "text"
        }
        val post = RedditPost(
            subreddit = p.attr("subreddit-prefixed-name").ifBlank { p.attr("subreddit-name").takeIf { it.isNotBlank() }?.let { "r/$it" }.orEmpty() },
            title = p.attr("post-title").ifBlank { doc.title() },
            author = author,
            createdMs = parseIso(p.attr("created-timestamp")),
            score = p.attr("score").toIntOrNull(),
            numComments = p.attr("comment-count").toIntOrNull(),
            flair = p.selectFirst("shreddit-post-flair")?.text()?.takeIf { it.isNotBlank() },
            selfText = (p.selectFirst("[slot=text-body]") ?: p.selectFirst("[id$=-post-rtjson-content]"))?.let { blockText(it) }.orEmpty(),
            linkUrl = href.takeIf { kind == "link" },
            kind = kind,
        )
        val tree = doc.selectFirst("shreddit-comment-tree")
        val tops = tree?.children()?.filter { it.tagName() == "shreddit-comment" }
            ?: doc.select("shreddit-comment[depth=0]").toList()
        fun conv(el: Element): RedditComment? {
            val body = el.children().firstOrNull { it.attr("slot") == "comment" }?.let { blockText(it) }.orEmpty()
            val replies = el.children().filter { it.tagName() == "shreddit-comment" }.mapNotNull { conv(it) }
            if (body.isBlank() && replies.isEmpty()) return null
            return RedditComment(
                body = body.ifBlank { "[deleted]" },
                score = el.attr("score").toIntOrNull(),
                isOp = el.attr("author").let { it.isNotEmpty() && it == author },
                replies = replies,
            )
        }
        val comments = tops.mapNotNull { conv(it) }
        if (post.title.isBlank() && comments.isEmpty()) return null
        return RedditThread(post, comments)
    }

    private fun blockText(el: Element): String {
        val c = el.clone()
        c.select("br").append("\\n")
        c.select("p,li,blockquote,h1,h2,h3,h4,h5,h6,pre").prepend("\\n")
        return c.text().replace("\\n", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    // ------------------------------------------------------------------ what the AI gets

    /**
     * How to summarize a thread; sent with the thread only, so ordinary chats don't pay for it. 2.20: the owner found
     * summaries thin ("tell me what it was about and what people are saying"), so the two parts are spelled out.
     */
    const val GUIDE =
        "[Pocket AI: this is a Reddit thread: the post, the page it links to (if any, read for you), its picture (if " +
            "you can see it) and the top-voted comments with their upvotes. Unless the user asks for something else, " +
            "summarize it in two parts. First, what it's about: what the poster shared or asked, and the substance of " +
            "the linked article or what the picture shows, with the real details. Then what people are saying: the " +
            "main reactions and answers, most upvoted first, the arguments and disagreements, corrections, and the " +
            "funny or sharp comments worth knowing, so the user gets the feel of the thread without reading it. If the " +
            "post asks a question, say what the answer turned out to be and how sure people are. Upvotes show what's " +
            "popular; no need to quote the numbers. Commenters' claims are claims, not facts. Don't repeat private " +
            "people's names or personal details that commenters dug up, unless news outlets already reported them.]"

    fun format(t: RedditThread, nowMs: Long, linked: FetchedPage?): String = buildString {
        val p = t.post
        append(GUIDE).append("\n\n")
        append("Reddit post in ${p.subreddit.ifBlank { "Reddit" }}")
        p.crosspostFrom?.let { append(" (crossposted from $it)") }
        p.flair?.let { append(" · flair: $it") }
        if (p.nsfw) append(" · NSFW")
        append("\nTitle: ${p.title}\n")
        append("Posted by u/${p.author.ifBlank { "[deleted]" }}")
        p.createdMs?.let { append(", ${ago(nowMs - it)} (${DATE.format(Date(it))})") }
        append("\n")
        val stats = buildList {
            p.score?.let { s -> add("${compact(s)} points" + (p.upvoteRatio?.let { ", ${(it * 100).toInt()}% upvoted" } ?: "")) }
            p.numComments?.let { add("${"%,d".format(it)} comments") }
        }
        if (stats.isNotEmpty()) append("Votes: ${stats.joinToString(" · ")}\n")
        when (p.kind) {
            "video" -> append("Post type: video${p.mediaNote?.let { " ($it)" } ?: ""}. You can't watch it (a still from it may follow this result); go by the title, post text and comments.\n")
            "image" -> append("Post type: image. The picture follows this result if you can see images; describe what it shows. Otherwise go by the title, post text and comments.\n")
            "gallery" -> append("Post type: image gallery${p.mediaNote?.let { " ($it)" } ?: ""}. The first pictures follow this result if you can see images. Otherwise go by the title, post text and comments.\n")
            "link", "video link" -> append("Post type: link to ${p.linkUrl}\n")
            "poll" -> append("Post type: poll\n")
        }
        if (p.poll.isNotEmpty()) {
            append("Poll options:\n")
            p.poll.forEach { (text, votes) -> append("- $text${votes?.let { " (${"%,d".format(it)} votes)" } ?: ""}\n") }
        }
        if (p.selfText.isNotBlank()) append("Post text:\n${clip(p.selfText, 4_000)}\n")
        if (linked != null) {
            append("\nThe linked page (read for you): ${linked.title}\nURL: ${linked.url}\n")
            append(clip(linked.text, LINKED_BUDGET)).append("\n")
        } else if (p.linkUrl != null && p.kind != "image") {
            append("(The linked page couldn't be read; summarize it from the title and comments, and say so.)\n")
        }
        append("\n")
        if (t.singleCommentThread) append("The user linked to one comment, so only that part of the thread is below.\n")
        if (t.comments.isEmpty()) {
            append("No comments could be read (the thread may be new, or comments are hidden).\n")
            return@buildString
        }
        val total = p.numComments
        val lines = commentLines(t.comments)
        append("Top comments (highest-voted first; ↳ = reply; [N ↑] = upvotes; OP = the poster")
        if (total != null && total > lines.count) append("; showing ${lines.count} of ${"%,d".format(total)}")
        append("):\n")
        append(lines.text)
    }

    private class Lines(val text: String, val count: Int)

    /**
     * Picks comments for the AI, most upvoted first, each with its best replies (replies often hold the
     * pushback or the correction), until [COMMENT_BUDGET] is used up.
     */
    private fun commentLines(comments: List<RedditComment>): Lines {
        val sb = StringBuilder()
        var count = 0
        val tops = comments.sortedByDescending { it.score ?: Int.MIN_VALUE }
        fun line(c: RedditComment, depth: Int, max: Int) {
            sb.append("  ".repeat(depth))
            if (depth > 0) sb.append("↳ ")
            sb.append("[").append(c.score?.let { compact(it) } ?: "?").append(" ↑]")
            if (c.isOp) sb.append(" OP")
            c.flair?.let { sb.append(" (flair: ").append(it).append(")") }
            if (c.controversial) sb.append(" (controversial)")
            sb.append(": ").append(clip(c.body.replace(Regex("""\s*\n+\s*"""), " / "), max)).append('\n')
            count++
        }
        for ((i, top) in tops.withIndex()) {
            if (sb.length >= COMMENT_BUDGET) break
            line(top, 0, if (i < 6) 900 else 450)
            val bar = maxOf(2, ((top.score ?: 0) * 0.04).toInt())
            val replies = top.replies.sortedByDescending { it.score ?: 0 }
                .filter { it.isOp || (it.score ?: 0) >= bar }.take(if (i < 10) 3 else 1)
            for (r in replies) {
                if (sb.length >= COMMENT_BUDGET) break
                line(r, 1, 350)
                r.replies.sortedByDescending { it.score ?: 0 }
                    .firstOrNull { it.isOp || (it.score ?: 0) >= maxOf(bar, ((r.score ?: 0) * 0.2).toInt()) }
                    ?.let { if (sb.length < COMMENT_BUDGET) line(it, 2, 250) }
            }
        }
        return Lines(sb.toString(), count)
    }

    // ------------------------------------------------------------------ helpers

    private val DATE = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US)

    fun ago(ms: Long): String {
        val m = abs(ms) / 60_000
        return when {
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} hours ago"
            m < 60L * 24 * 60 -> "${m / (24 * 60)} days ago"
            else -> "${m / (30L * 24 * 60)} months ago"
        }
    }

    fun compact(n: Int): String = when {
        abs(n) >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
        abs(n) >= 100_000 -> "${n / 1000}k"
        abs(n) >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0).replace(".0k", "k")
        else -> n.toString()
    }

    private fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.lastIndexOf(' ', max).takeIf { it > max * 0.7 } ?: max
        return s.substring(0, cut).trimEnd() + "…"
    }

    private fun parseIso(s: String): Long? = runCatching {
        java.time.OffsetDateTime.parse(s.replace(Regex("""(\+\d{2})(\d{2})$"""), "$1:$2")).toInstant().toEpochMilli()
    }.getOrNull()

    private fun JsonElement.children(): List<JsonObject> =
        (((this as? JsonObject)?.get("data") as? JsonObject)?.get("children") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.data(): JsonObject? = this["data"] as? JsonObject
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
}
