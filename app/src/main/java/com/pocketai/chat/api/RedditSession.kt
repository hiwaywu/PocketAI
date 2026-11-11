package com.pocketai.chat.api

import android.webkit.CookieManager

/**
 * The owner's own Reddit sign-in, kept in the app's WebView cookies (2.21). Logged-out visitors often get a thread
 * without its comments; signed in, the hidden browser that reads shared threads sees them like the owner does.
 * The password is typed into Reddit's own login page and never seen by Pocket AI.
 */
object RedditSession {
    const val LOGIN_URL = "https://www.reddit.com/login/"
    private val SITES = listOf("https://www.reddit.com", "https://reddit.com", "https://old.reddit.com")

    fun signedIn(): Boolean = runCatching {
        SITES.any { CookieManager.getInstance().getCookie(it).orEmpty().contains("reddit_session=") }
    }.getOrDefault(false)

    /** Removes Reddit's cookies (signs Pocket AI out of Reddit; other sites' cookies stay). */
    fun signOut() {
        val cm = CookieManager.getInstance()
        for (site in SITES) {
            val names = cm.getCookie(site).orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
            for (name in names) {
                cm.setCookie(site, "$name=; Max-Age=0; Path=/")
                cm.setCookie(site, "$name=; Max-Age=0; Path=/; Domain=.reddit.com")
            }
        }
        cm.flush()
    }
}
