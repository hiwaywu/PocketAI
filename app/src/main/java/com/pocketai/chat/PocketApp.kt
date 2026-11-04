package com.pocketai.chat

import android.app.Application

/**
 * Holds the one [ChatViewModel] for the whole process instead of tying it to the Activity.
 * Replies therefore keep running when the user leaves the app, swipes it out of Recents, or the
 * Fold is opened/closed; [ReplyService] keeps the process alive and online meanwhile.
 */
class PocketApp : Application() {
    val chat: ChatViewModel by lazy { ChatViewModel(this) }

    /** True while a Pocket AI screen is visible (set by MainActivity). */
    @Volatile var visible = false

    override fun onCreate() {
        super.onCreate()
        ReplyService.createChannels(this)
    }
}
