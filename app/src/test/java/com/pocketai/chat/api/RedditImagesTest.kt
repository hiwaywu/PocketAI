package com.pocketai.chat.api

import org.junit.Assert.assertEquals
import org.junit.Test

class RedditImagesTest {
    private fun thread(post: String) = """[{"data":{"children":[{"kind":"t3","data":$post}]}},{"data":{"children":[]}}]"""

    @Test
    fun imagePostKeepsItsPicture() {
        val t = Reddit.parse(thread("""{"title":"What is this liquid?","subreddit":"whatisit","post_hint":"image",
            "url":"https://i.redd.it/abc123.jpeg","domain":"i.redd.it"}"""))!!
        assertEquals("image", t.post.kind)
        assertEquals(listOf("https://i.redd.it/abc123.jpeg"), t.post.images)
    }

    @Test
    fun galleryKeepsFirstThreeInOrder() {
        val items = (1..5).joinToString(",") { """{"media_id":"m$it"}""" }
        val meta = (1..5).joinToString(",") { """"m$it":{"s":{"u":"https://preview.redd.it/m$it.jpg?width=1&amp;s=x"}}""" }
        val t = Reddit.parse(thread("""{"title":"Found these","is_gallery":true,"gallery_data":{"items":[$items]},
            "media_metadata":{$meta}}"""))!!
        assertEquals("gallery", t.post.kind)
        assertEquals((1..3).map { "https://preview.redd.it/m$it.jpg?width=1&s=x" }, t.post.images)
    }

    @Test
    fun linkPostHasNoPictures() {
        val t = Reddit.parse(thread("""{"title":"News","url":"https://www.bbc.com/news/x","domain":"bbc.com",
            "preview":{"images":[{"source":{"url":"https://external-preview.redd.it/p.jpg"}}]}}"""))!!
        assertEquals("link", t.post.kind)
        assertEquals(emptyList<String>(), t.post.images)
    }
}
