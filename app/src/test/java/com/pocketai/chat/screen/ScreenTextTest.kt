package com.pocketai.chat.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {
    @Test fun addressBars() {
        assertEquals(
            "https://nypost.com/2026/11/08/us-news/story/" to "nypost.com",
            ScreenText.linkFromBar("nypost.com/2026/11/08/us-news/story/"),
        )
        assertEquals(
            "https://www.reddit.com/r/x/comments/abc/t/" to "reddit.com",
            ScreenText.linkFromBar("https://www.reddit.com/r/x/comments/abc/t/"),
        )
        // Chrome usually shows just the site: no link to read, but we know the site.
        assertEquals(null to "nytimes.com", ScreenText.linkFromBar("nytimes.com"))
        assertEquals(null to null, ScreenText.linkFromBar("Search or type web address"))
        assertEquals(null to null, ScreenText.linkFromBar(null))
    }

    @Test fun cleansScreenText() {
        val text = ScreenText.clean(
            listOf(
                "Reply", "Reply", "  Share​ ", "",
                "Making pancake tacos and a breakfast sandwich out of that is honestly genius",
                "Making pancake tacos and a breakfast sandwich out of that is honestly genius",
                "Reply",
            )
        )
        assertEquals("Reply\nShare\nMaking pancake tacos and a breakfast sandwich out of that is honestly genius\nReply", text)
    }

    @Test fun capsLength() {
        val text = ScreenText.clean(List(5_000) { i -> "Comment number $i says something fairly long about the post" })
        assertTrue(text.length <= ScreenText.MAX_CHARS)
        assertTrue(text.startsWith("Comment number 0"))
    }

    @Test fun appNames() {
        assertEquals("Firefox", ScreenText.appName("org.mozilla.firefox", null, null))
        assertEquals("Reddit", ScreenText.appName("com.reddit.frontpage", "reddit", null))
        assertEquals("Weather Pro", ScreenText.appName("com.example.weather", "Weather Pro", null))
        assertEquals("Weather", ScreenText.appName("com.example.weather", null, null))
        assertTrue("org.mozilla.firefox" in ScreenText.BROWSERS)
        assertFalse("com.reddit.frontpage" in ScreenText.BROWSERS)
    }

    @Test fun labels() {
        assertEquals("Firefox screen · nypost.com", ScreenCapture("screen", "Firefox", url = "https://nypost.com/a/b", text = "x").label)
        assertEquals("Chrome screen · nytimes.com", ScreenCapture("screen", "Chrome", site = "nytimes.com", text = "x").label)
        assertEquals("Reddit screen", ScreenCapture("screen", "Reddit", text = "x").label)
        assertEquals("Text from Reddit", ScreenCapture("selection", "Reddit", text = "x").label)
        assertNull(ScreenCapture("screen", "Reddit", text = "x").url)
    }

    @Test fun youTubeTranscriptPanel() {
        val segs = ScreenText.transcriptSegments(listOf("Transcript", "0:00", "Hello everyone", "0:04", "Today we talk about TVB", "1:02:03 a long video line", "Search transcript"))
        assertEquals(listOf("0:00" to "Hello everyone", "0:04" to "Today we talk about TVB", "1:02:03" to "a long video line"), segs)
        assertTrue(ScreenText.isTimestamp("11:35"))
        assertFalse(ScreenText.isTimestamp("2.1k views"))
    }

    @Test fun addressShapes() {
        assertTrue(ScreenText.looksLikeAddress("scmp.com/tech/big-tech/article/3333333/menlo-report"))
        assertTrue(ScreenText.looksLikeAddress("https://www.reddit.com/r/x/comments/abc/"))
        assertTrue(ScreenText.looksLikeAddress("nytimes.com"))
        assertFalse(ScreenText.looksLikeAddress("Search or enter address"))
        assertFalse(ScreenText.looksLikeAddress("US\$77.3 billion"))
        assertFalse(ScreenText.looksLikeAddress("2.1k"))
        assertFalse(ScreenText.looksLikeAddress("v4.1"))
    }
}
