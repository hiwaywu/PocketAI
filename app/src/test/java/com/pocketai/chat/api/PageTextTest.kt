package com.pocketai.chat.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextTest {
    private val para = "The city council voted 7-2 on Tuesday to approve a new transit plan that will add three bus lines, " +
        "extend subway hours and cost about 2.4 billion dollars over ten years, officials said. "

    @Test fun keepsTheArticleAndDropsTheClutter() {
        val html = """
            <html><head><title>Council approves transit plan | Daily News</title>
            <meta property="og:site_name" content="Daily News">
            <meta property="article:published_time" content="2026-11-07T10:00:00Z">
            </head><body>
            <nav><a href="/">Home</a><a href="/sports">Sports</a></nav>
            <div class="sidebar"><h3>Most read</h3><ul><li><a href="/a">Celebrity spotted at mall</a></li><li><a href="/b">Ten best pizzas</a></li></ul></div>
            <article><h1>Council approves transit plan</h1><p class="byline">By Jane Doe</p>
            <p>${para.repeat(3)}</p><p>${para.repeat(2)}</p><ul><li>Three new bus lines</li><li>Later subway hours</li></ul><p>${para}</p></article>
            <footer>Copyright Daily News. Subscribe now!</footer></body></html>
        """.trimIndent()
        val r = PageText.extract(html, "https://dailynews.example/2026/11/07/transit", 15_000)
        println(r.text)
        assertTrue(r.isArticle)
        assertTrue(r.text.startsWith("Daily News"))
        assertTrue(r.text.contains("Published 2026-11-07"))
        assertTrue(r.text.contains("The city council voted 7-2"))
        assertTrue(r.text.contains("- Three new bus lines"))
        assertFalse(r.text.contains("Celebrity spotted"))
        assertFalse(r.text.contains("Subscribe now"))
    }

    @Test fun usesTheFullStoryFromSearchDataWhenThePageShowsATeaser() {
        val body = para.repeat(8)
        val html = """
            <html><head><title>Paywalled story</title>
            <script type="application/ld+json">{"@context":"https://schema.org","@type":"NewsArticle","datePublished":"2026-11-08","articleBody":"$body"}</script>
            </head><body><article><p>${para}</p><div class="paywall">Subscribe to keep reading</div></article></body></html>
        """.trimIndent()
        val r = PageText.extract(html, "https://paper.example/story", 15_000)
        assertTrue(r.isArticle)
        assertTrue(r.text.length > body.length - 10)
        assertTrue(r.text.contains("Published 2026-11-08"))
    }

    @Test fun cutsLongPages() {
        val r = PageText.clean("line\n".repeat(10_000), 1_000)
        assertTrue(r.length <= 1_000 + 30)
        assertTrue(r.endsWith("[… cut for length]"))
    }

    @Test fun transcriptChunksAndLinks() {
        val lines = (0 until 120).map { i -> "${i * 5 / 60}:${(i * 5 % 60).toString().padStart(2, '0')} sentence number $i" }
        val t = Transcript.format(lines, 100_000)!!
        val first = t.lines().first()
        assertTrue(first.startsWith("[0:00] sentence number 0 sentence number 1"))
        assertEquals(20, t.lines().size) // 10 minutes in 30-second chunks
        assertEquals(84, Transcript.seconds("1:24"))
        assertEquals(3723, Transcript.seconds("1:02:03"))
        assertNull(Transcript.format(listOf("no times here"), 1000))
    }

    @Test fun longTranscriptsAreSampledEvenly() {
        fun ts(sec: Int) = "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
        val lines = (0 until 2000).map { i -> ts(i * 10) + " " + "word ".repeat(30) }
        val t = Transcript.format(lines, 3_000)!!
        assertTrue(t.startsWith("(Long video"))
        assertTrue(Transcript.tokens(t) < 3_600)
        // The end of the video is still there, not just its beginning (the video is 20,000 s long).
        val last = t.lines().last().substringAfter('[').substringBefore(']')
        assertTrue(Transcript.seconds(last) > 19_000)
    }

    @Test fun tokensCountChineseCharacters() {
        assertEquals(4, Transcript.tokens("香港電台"))
        assertEquals(2, Transcript.tokens("abcdefgh"))
    }
}
