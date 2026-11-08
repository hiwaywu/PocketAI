package com.pocketai.chat.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun recognizesRedditLinks() {
        assertTrue(Reddit.isReddit("https://www.reddit.com/r/TikTokCringe/s/UgEPXLhWj9"))
        assertTrue(Reddit.isReddit("https://old.reddit.com/r/x/comments/abc/t/"))
        assertTrue(Reddit.isReddit("https://redd.it/1xqi8jk"))
        assertFalse(Reddit.isReddit("https://notreddit.com/r/x"))
        assertTrue(Reddit.isShortLink("https://www.reddit.com/r/TikTokCringe/s/UgEPXLhWj9"))
        assertFalse(Reddit.isShortLink("https://www.reddit.com/r/TikTokCringe/comments/1xqi8jk/teaching/"))
    }

    @Test fun jsonUrls() {
        assertEquals(
            "https://www.reddit.com/r/TikTokCringe/comments/1xqi8jk/teaching_how/.json?sort=top&limit=200&depth=5&raw_json=1",
            Reddit.jsonUrl("https://www.reddit.com/r/TikTokCringe/comments/1xqi8jk/teaching_how/?share_id=SGC&utm_source=x"),
        )
        assertEquals(
            "https://www.reddit.com/r/a/comments/p1/slug/c9/.json?sort=top&limit=200&depth=5&raw_json=1&context=3",
            Reddit.jsonUrl("https://www.reddit.com/r/a/comments/p1/slug/c9/"),
        )
        assertEquals("https://www.reddit.com/comments/1xqi8jk/.json?sort=top&limit=200&depth=5&raw_json=1", Reddit.jsonUrl("https://redd.it/1xqi8jk"))
        assertNull(Reddit.jsonUrl("https://www.reddit.com/r/TikTokCringe/"))
    }

    @Test fun readableLinks() {
        assertTrue(Reddit.isReadableLink("https://www.nytimes.com/2026/11/08/us/politics/x.html"))
        assertFalse(Reddit.isReadableLink("https://i.redd.it/abc.jpg"))
        assertFalse(Reddit.isReadableLink("https://v.redd.it/abc"))
        assertFalse(Reddit.isReadableLink("https://example.com/photo.PNG"))
        assertFalse(Reddit.isReadableLink("https://www.reddit.com/r/x/comments/abc/"))
    }

    @Test fun parsesThreadFromJson() {
        val t = Reddit.parse(fixture("reddit_thread.json"))
        assertNotNull(t)
        t!!
        assertEquals("r/TikTokCringe", t.post.subreddit)
        assertEquals("video", t.post.kind)
        assertEquals("1:12 long", t.post.mediaNote)
        assertEquals(0.93, t.post.upvoteRatio!!, 0.001)
        // AutoModerator and the removed comment are dropped.
        assertEquals(3, t.comments.size)
        val top = t.comments[0]
        assertEquals(2104, top.score)
        assertTrue(top.replies[0].isOp)
        assertEquals("Verified Pediatric Nurse", t.comments[2].flair)
        assertTrue(t.comments[2].controversial)
    }

    @Test fun formatsForTheModel() {
        val t = Reddit.parse(fixture("reddit_thread.json"))!!
        val now = (1762534320L + 20 * 3600) * 1000
        val text = Reddit.format(t, now, null)
        println(text)
        assertTrue(text.startsWith("[Pocket AI: this is a Reddit thread"))
        assertTrue(text.contains("Reddit post in r/TikTokCringe · flair: Discussion"))
        assertTrue(text.contains("Posted by u/Brokenlingo, 20 hours ago"))
        assertTrue(text.contains("15.2k points, 93% upvoted · 1,874 comments"))
        assertTrue(text.contains("Post type: video (1:12 long). You can't watch it"))
        assertTrue(text.contains("[2.1k ↑]: Making pancake tacos"))
        assertTrue(text.contains("  ↳ [512 ↑] OP: Thanks!"))
        assertTrue(text.contains("    ↳ [400 ↑]: I can already hear"))
        assertTrue(text.contains("Do the kids fight over who gets the biscuit or do they rotate on it? / Asking for my own chaos."))
        assertTrue(text.contains("[-12 ↑] (flair: Verified Pediatric Nurse) (controversial)"))
        assertTrue(text.contains("showing 6 of 1,874"))
        assertFalse(text.contains("AutoModerator"))
        assertFalse(text.contains("Welcome to r/TikTokCringe"))
        assertFalse(text.contains("lol")) // 3 upvotes is below the reply bar
        assertFalse(text.contains("pancake_fan")) // commenters' usernames aren't needed
    }

    @Test fun linkPostIncludesTheArticle() {
        val raw = fixture("reddit_thread.json")
            .replace("\"is_video\":true", "\"is_video\":false")
            .replace("\"domain\":\"v.redd.it\"", "\"domain\":\"nypost.com\"")
            .replace("\"post_hint\":\"hosted:video\"", "\"post_hint\":\"link\"")
            .replace("\"media\":{\"reddit_video\":{\"duration\":72,\"fallback_url\":\"https://v.redd.it/abc123/DASH_720.mp4\"}},", "")
            .replace("https://v.redd.it/abc123", "https://nypost.com/2026/11/08/story/")
        val t = Reddit.parse(raw)!!
        assertEquals("link", t.post.kind)
        assertEquals("https://nypost.com/2026/11/08/story/", t.post.linkUrl)
        val text = Reddit.format(t, System.currentTimeMillis(), FetchedPage("Big story", "https://nypost.com/2026/11/08/story/", "The article body."))
        assertTrue(text.contains("Post type: link to https://nypost.com/2026/11/08/story/"))
        assertTrue(text.contains("The linked page (read for you): Big story"))
        assertTrue(text.contains("The article body."))
    }

    @Test fun parsesRedditWebPage() {
        val html = """
            <html><head><title>Reddit</title></head><body>
            <shreddit-post post-title="Ole Miss students caught on Ring camera" author="Kinnyee" score="5321" comment-count="812"
              created-timestamp="2026-11-08T03:12:44.123000+0000" subreddit-prefixed-name="r/BlackPeopleofReddit" post-type="text">
              <div slot="text-body"><p>These the same ones that listen to our music...</p><p>Mississippi is watching</p></div>
            </shreddit-post>
            <shreddit-comment-tree>
              <shreddit-comment author="a1" score="900" depth="0"><div slot="comment"><p>So much racism from that school.</p></div>
                <shreddit-comment author="Kinnyee" score="300" depth="1"><div slot="comment"><p>Exactly my point.</p></div></shreddit-comment>
              </shreddit-comment>
              <shreddit-comment author="a2" score="120" depth="0"><div slot="comment"><p>People insist the US isn't racist anymore.</p></div></shreddit-comment>
            </shreddit-comment-tree>
            <div class="recommended">Unrelated recommended post about cats</div>
            </body></html>
        """.trimIndent()
        val t = Reddit.parseHtml(html, "https://www.reddit.com/r/BlackPeopleofReddit/comments/abc/x/")!!
        assertEquals("Ole Miss students caught on Ring camera", t.post.title)
        assertEquals(5321, t.post.score)
        assertEquals(2, t.comments.size)
        assertTrue(t.comments[0].replies[0].isOp)
        val text = Reddit.format(t, System.currentTimeMillis(), null)
        assertTrue(text.contains("These the same ones that listen to our music...\nMississippi is watching"))
        assertTrue(text.contains("  ↳ [300 ↑] OP: Exactly my point."))
        assertFalse(text.contains("cats"))
    }

    @Test fun compactNumbers() {
        assertEquals("999", Reddit.compact(999))
        assertEquals("1k", Reddit.compact(1000))
        assertEquals("2.1k", Reddit.compact(2104))
        assertEquals("15.2k", Reddit.compact(15234))
        assertEquals("250k", Reddit.compact(250_400))
        assertEquals("1.5M", Reddit.compact(1_500_000))
    }
}
