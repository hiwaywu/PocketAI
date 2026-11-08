package com.pocketai.chat.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameTopicTest {
    private val now = 1_000_000_000L
    private val article = (1..30).joinToString("\n") { "Paragraph $it of the Nvidia story, with enough words to count as text." }
    private fun cap(text: String, pkg: String = "org.mozilla.firefox", url: String? = null, kind: String = "screen") =
        ScreenCapture(kind, "Firefox", pkg, url = url, text = text)

    @Test fun sameArticleScrolled() {
        val last = SameTopic.Last("org.mozilla.firefox", null, article, now - 60_000)
        val scrolled = article.lines().drop(10).joinToString("\n") + "\nReply\nShare"
        assertTrue(SameTopic.isSame(last, cap(scrolled), now))
    }

    @Test fun newWebsite() {
        val last = SameTopic.Last("org.mozilla.firefox", null, article, now - 60_000)
        val other = (1..20).joinToString("\n") { "Line $it of a completely different SCMP piece about chips and trade." }
        assertFalse(SameTopic.isSame(last, cap(other), now))
    }

    @Test fun linksDecideWhenKnown() {
        val last = SameTopic.Last("org.mozilla.firefox", "https://nypost.com/2026/11/08/a/?utm=x", "x", now - 60_000)
        assertTrue(SameTopic.isSame(last, cap("y", url = "https://www.nypost.com/2026/11/08/a"), now))
        assertFalse(SameTopic.isSame(last, cap(article, url = "https://nypost.com/2026/11/08/b/"), now))
        assertEquals("youtube.com/watch?v=abc", SameTopic.pageKey("https://m.youtube.com/watch?v=abc&t=30"))
    }

    @Test fun otherAppOrTooLate() {
        val last = SameTopic.Last("org.mozilla.firefox", null, article, now - 60_000)
        assertFalse(SameTopic.isSame(last, cap(article, pkg = "com.reddit.frontpage"), now))
        assertFalse(SameTopic.isSame(last.copy(at = now - SameTopic.MAX_GAP_MS - 1), cap(article), now))
        assertFalse(SameTopic.isSame(null, cap(article), now))
    }

    @Test fun selectionAndTinyScreensStay() {
        val last = SameTopic.Last("org.mozilla.firefox", null, article, now - 60_000)
        assertTrue(SameTopic.isSame(last, cap("one sentence", kind = "selection"), now))
        assertTrue(SameTopic.isSame(last, cap("2:05\n21:25"), now))
    }
}
