package com.pocketai.chat

import android.app.Application
import java.io.File

/**
 * Holds the one [ChatViewModel] for the whole process instead of tying it to the Activity.
 * Replies therefore keep running when the user leaves the app, swipes it out of Recents, or the
 * Fold is opened/closed; [ReplyService] keeps the process alive and online meanwhile.
 */
class PocketApp : Application() {
    val chat: ChatViewModel by lazy { ChatViewModel(this) }

    /** Reads replies aloud; created the first time it's used. */
    val speaker: Speaker by lazy { Speaker(this) }

    /** True while a Pocket AI screen is visible (set by MainActivity). */
    @Volatile var visible = false

    override fun onCreate() {
        super.onCreate()
        ReplyService.createChannels(this)
        removeOldBrowserData()
    }

    /**
     * 2.0–2.1 had a Firefox-engine browser; its profile (add-ons, cookies, site data) and cache can take
     * tens of MB. Deleted once after updating; chats, photos and settings are elsewhere and stay.
     */
    private fun removeOldBrowserData() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("oldBrowserRemoved", false)) return
        Thread {
            runCatching {
                File(filesDir, "mozilla").deleteRecursively()
                cacheDir.listFiles()?.filter { it.name != "camera" }?.forEach { it.deleteRecursively() }
                deleteSharedPreferences("browser")
            }
            prefs.edit().putBoolean("oldBrowserRemoved", true).apply()
        }.start()
    }
}
