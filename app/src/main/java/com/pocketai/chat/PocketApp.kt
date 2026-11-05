package com.pocketai.chat

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Holds the one [ChatViewModel] for the whole process instead of tying it to the Activity.
 * Replies therefore keep running when the user leaves the app, swipes it out of Recents, or the
 * Fold is opened/closed; [ReplyService] keeps the process alive and online meanwhile.
 */
class PocketApp : Application() {
    val chat: ChatViewModel by lazy { ChatViewModel(this) }

    /**
     * Firefox's engine for the in-app browser. Only one may exist per process, so it lives here and is
     * created the first time the browser is shown. The phone's font size isn't applied on top of the
     * browser's own text size (that made page text overflow its boxes).
     */
    val gecko: GeckoRuntime by lazy {
        GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder()
                .automaticFontSizeAdjustment(false)
                .consoleOutput(false)
                .build(),
        )
    }

    /** True while a Pocket AI screen is visible (set by MainActivity). */
    @Volatile var visible = false

    override fun onCreate() {
        super.onCreate()
        ReplyService.createChannels(this)
    }
}
