package com.pocketai.chat.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedditTextLinkTest {
    @Test
    fun findsTheArticleInAPostsText() {
        val body = "Ferris Acres is No. 2 in the world!\n\n[Patch](https://patch.com/connecticut/newtown/ferris-acres-ranked).\n" +
            "Pic: https://i.redd.it/abc.jpg"
        assertEquals("https://patch.com/connecticut/newtown/ferris-acres-ranked", Reddit.firstLinkInText(body))
    }

    @Test
    fun skipsRedditAndImageLinks() {
        assertNull(Reddit.firstLinkInText("See https://www.reddit.com/r/x/comments/1/ and https://i.imgur.com/a.png"))
        assertEquals("https://www.nbcconnecticut.com/news/a", Reddit.firstLinkInText("old thread https://redd.it/1 news https://www.nbcconnecticut.com/news/a."))
    }

    @Test
    fun textPostUsesItsLinkLinkPostItsTarget() {
        val text = RedditPost(subreddit = "r/Connecticut", title = "t", author = "a", kind = "text", selfText = "read https://patch.com/x")
        assertEquals("https://patch.com/x", Reddit.articleLink(text))
        val link = RedditPost(subreddit = "r/x", title = "t", author = "a", kind = "link", linkUrl = "https://bbc.com/n", selfText = "also https://patch.com/x")
        assertEquals("https://bbc.com/n", Reddit.articleLink(link))
    }
}
