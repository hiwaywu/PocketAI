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

    @Test fun youTubeTitleCandidates() {
        val screen = listOf(
            "2:04 / 21:25", "張曦雯7分鐘脫衣戲被剪剩2秒：TVB宣傳榨乾 臨門卸責", "6,597 views 2 hours ago ...more",
            "八八卦卦", "88.4K subscribers", "Subscribe", "Like this video along with 120 other people", "Share",
            "Comments 45", "Kelly Cheung", "This is what people should know about TVB dramas",
        )
        val c = ScreenText.titleCandidates(screen)
        assertEquals("張曦雯7分鐘脫衣戲被剪剩2秒：TVB宣傳榨乾 臨門卸責", c.first())
        assertFalse(c.any { it.contains("views") || it.startsWith("Like") || it == "Subscribe" })
        assertTrue(ScreenText.isMoreLink("6,597 views 2 hours ago ...more"))
        assertTrue(ScreenText.isMoreLink("…more"))
        assertFalse(ScreenText.isMoreLink("Show transcript"))
    }

    @Test fun spokenTranscriptTimes() {
        assertEquals("1:49" to "就全林場度", ScreenText.leadingTime("1 minute, 49 seconds 就全林場度"))
        assertEquals("0:05" to "", ScreenText.leadingTime("5 seconds"))
        assertEquals("1:02:03" to "words", ScreenText.leadingTime("1 hour, 2 minutes, 3 seconds words"))
        assertEquals("2:06" to "", ScreenText.leadingTime("2:06"))
        assertNull(ScreenText.leadingTime("2:04 / 21:25"))
        assertNull(ScreenText.leadingTime("2 hours ago"))
        assertNull(ScreenText.leadingTime("Reply"))
        val rows = ScreenText.transcriptSegments(
            listOf("1 minute, 49 seconds 就全林場度一時又話", "1:49", "就全林場度一時又話", "1 minute, 56 seconds", "儀出面開佢")
        )
        assertEquals(listOf("1:49" to "就全林場度一時又話", "1:49" to "就全林場度一時又話", "1:56" to "儀出面開佢"), rows)
    }

    @Test fun videoLengthFromPlayer() {
        assertEquals(21 * 60 + 25, ScreenText.videoLength(listOf("Pause video", "2:04 / 21:25")))
        assertEquals(144, ScreenText.videoLength(listOf("0 minutes 54 seconds of 2 minutes 24 seconds")))
        assertEquals(3723, ScreenText.videoLength(listOf("10:00 / 1:02:03")))
        assertNull(ScreenText.videoLength(listOf("Reply", "1:49")))
    }

    @Test fun youTubeComments() {
        val row = listOf(
            "@kellyfan88 · 2 days ago", "TVB really milked this for promotion and then dumped her.", "Read more",
            "Like this comment along with 1.2K other people", "1.2K", "Dislike", "Reply", "23 replies",
        )
        assertEquals("[1.2K likes] TVB really milked this for promotion and then dumped her. (23 replies)", ScreenText.formatComment(row))
        assertEquals("[0 likes] So true", ScreenText.formatComment(listOf("@a_b", "So true", "Reply")))
        assertNull(ScreenText.formatComment(listOf("Comments", "Top", "Newest")))
        assertNull(ScreenText.formatComment(listOf("Some video title", "1.2M views")))
        assertTrue(ScreenText.isCommentAuthor("@kellyfan88 · 2 days ago"))
        assertFalse(ScreenText.isCommentAuthor("八八卦卦"))
    }

    @Test fun youTubeLabelsFromTheFold() {
        // Exactly what the YouTube app exposed on the owner's Fold (Nov 2026).
        val screen = listOf(
            "5 minutes 58 seconds elapsed of 14 minutes 36 seconds", "5:58", "/ 14:36",
            "5 minutes 58 seconds of 14 minutes 36 seconds", "Transcript",
            "5 minutes, 42 seconds two legs on this side of his body. I'm not sure how he lost two of his legs",
            "5 minutes, 51 seconds to rest after the pinch test.",
            "6 minutes, 7 seconds All right, could you help me outside?",
        )
        assertNull(ScreenText.leadingTime(screen[0]))
        assertNull(ScreenText.leadingTime(screen[3]))
        assertEquals(
            listOf("5:42", "5:51", "6:07"),
            ScreenText.transcriptSegments(screen).map { it.first },
        )
        assertEquals("two legs on this side of his body. I'm not sure how he lost two of his legs", ScreenText.transcriptSegments(screen)[0].second)
        assertEquals(14 * 60 + 36, ScreenText.videoLength(screen))
    }

    @Test fun redditAppTitle() {
        // What the Reddit app showed in the owner's screenshot.
        val screen = listOf(
            "r/NowInTech", "u/skaza02 • 11h • thenextweb.com", "Top 1% Poster",
            "Anthropic buys \$3.4bn of gas generators for its data centres, FT reports", "91", "36", "Join",
            "the8bit 9h", "Oh cool so joining Elon's \" I don't care about the environment, I just need to power these data centers to make a profit!\"",
        )
        assertEquals("Anthropic buys \$3.4bn of gas generators for its data centres, FT reports", ScreenText.redditTitleCandidates(screen).first())
    }
}
