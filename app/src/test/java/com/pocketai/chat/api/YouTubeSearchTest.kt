package com.pocketai.chat.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSearchTest {
    private val html = """
        <html><script>var ytInitialData = {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
          {"itemSectionRenderer":{"contents":[
            {"adSlotRenderer":{"x":1}},
            {"videoRenderer":{"videoId":"0-WuSH5TwxE","title":{"runs":[{"text":"TVB 台慶劇 "},{"text":"璀璨之城"}]},
              "ownerText":{"runs":[{"text":"八八卦卦"}]},"lengthText":{"simpleText":"21:05"},
              "viewCountText":{"simpleText":"12,345 views"},"publishedTimeText":{"simpleText":"2 days ago"}}},
            {"shelfRenderer":{"content":{"verticalListRenderer":{"items":[
              {"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Another video"},"longBylineText":{"runs":[{"text":"Chan"}]}}}]}}}},
            {"videoRenderer":{"videoId":"0-WuSH5TwxE","title":{"simpleText":"duplicate"}}}
          ]}}]}}}}};</script></html>
    """.trimIndent()

    @Test fun findsVideos() {
        val v = YouTubeSearch.parse(html)
        assertEquals(2, v.size)
        assertEquals("TVB 台慶劇 璀璨之城", v[0].title)
        assertEquals("八八卦卦", v[0].channel)
        assertEquals("https://www.youtube.com/watch?v=0-WuSH5TwxE", v[0].link)
        assertEquals("Chan", v[1].channel)
        val text = YouTubeSearch.format("TVB", v)
        assertTrue(text.contains("1. TVB 台慶劇 璀璨之城\n   Channel: 八八卦卦 · 21:05 · 12,345 views · 2 days ago\n   https://www.youtube.com/watch?v=0-WuSH5TwxE"))
    }

    @Test fun searchLinks() {
        assertEquals("tvb drama 2026", YouTubeSearch.query("https://www.youtube.com/results?search_query=tvb+drama+2026"))
        assertEquals("璀璨之城 八八卦卦", YouTubeSearch.query(YouTubeSearch.searchUrl("璀璨之城 八八卦卦")))
        assertNull(YouTubeSearch.query("https://www.youtube.com/watch?v=0-WuSH5TwxE"))
        assertNull(YouTubeSearch.query("https://example.com/results?search_query=x"))
    }

    @Test fun matchesTheVideoOnScreen() {
        val v = YouTubeSearch.parse(html)
        // A chapter title shown over the player, or the full title, both find it; an unrelated line doesn't.
        assertEquals("0-WuSH5TwxE", YouTubeSearch.bestMatch("TVB 台慶劇 璀璨之城", v)?.id)
        assertEquals("0-WuSH5TwxE", YouTubeSearch.bestMatch("台慶劇璀璨之城 full episode recap and more", v.reversed())?.id)
        assertEquals("abcdefghijk", YouTubeSearch.bestMatch("Another video", v)?.id)
        assertNull(YouTubeSearch.bestMatch("Completely different words here", v))
    }
}
