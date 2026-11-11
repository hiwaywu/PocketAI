package com.pocketai.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that runs while a reply is being written. Without it Android freezes or
 * cuts the network of an app soon after the user leaves it, which killed replies mid-way.
 * It shows a quiet "Pocket AI is replying…" notification, holds a CPU wake lock so the reply keeps
 * streaming with the screen off, and stops itself as soon as the reply is finished.
 */
class ReplyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chat = (application as PocketApp).chat
        val first = ongoing(this, chat.state.value.statuses.values.firstOrNull())
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ONGOING_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ONGOING_ID, first)
        } catch (e: Exception) {
            // Not allowed right now (e.g. started from the background); the reply still runs, just unprotected.
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketAI:reply")
                .apply { setReferenceCounted(false); acquire(MAX_WAKE_MS) }
        }
        if (watcher == null) {
            watcher = scope.launch {
                // Any chat with a reply in progress keeps the service up, not just the open one.
                chat.state.map { st -> st.running.isNotEmpty() to (st.status ?: st.statuses.values.firstOrNull()) }
                    .distinctUntilChanged().collect { (streaming, status) ->
                    if (!streaming) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notify(this@ReplyService, ONGOING_ID, ongoing(this@ReplyService, status))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits data-sync services to 6 hours a day; a reply never needs that long. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CH_ONGOING = "replying"
        private const val CH_DONE = "replies"
        private const val ONGOING_ID = 1
        private const val DONE_ID = 2
        private const val ACTION_ID_BASE = 1000
        private const val MAX_WAKE_MS = 15 * 60 * 1000L
        const val EXTRA_OPEN_CHAT = "com.pocketai.chat.OPEN_CHAT"

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_ONGOING, "Reply in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Pocket AI finishes a reply in the background"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "Finished replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A reply finished while you were in another app"
                }
            )
        }

        /** Call when a reply starts (the user just pressed Send, so the app is in the foreground). */
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, ReplyService::class.java)) }
        }

        /** Tells the user their answer is ready when they're in another app. Tapping opens that chat. */
        fun notifyDone(ctx: Context, convId: String, chatTitle: String, preview: String, failed: Boolean) {
            val n = NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (failed) "Reply failed · $chatTitle" else chatTitle)
                .setContentText(preview)
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setContentIntent(openIntent(ctx, convId))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build()
            notify(ctx, DONE_ID, n)
        }

        /**
         * A phone action (alarm, timer) that couldn't open the Clock app because Pocket AI was in the background:
         * Android only lets apps open other apps from the foreground, so the user taps this to finish it.
         */
        fun notifyTapToRun(ctx: Context, title: String, text: String, action: Intent) {
            val pi = PendingIntent.getActivity(
                ctx, text.hashCode(), action, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            notify(ctx, ACTION_ID_BASE + (text.hashCode() and 0xFFFF), n)
        }

        fun clearDone(ctx: Context) {
            NotificationManagerCompat.from(ctx).cancel(DONE_ID)
        }

        private fun ongoing(ctx: Context, status: String?): Notification =
            NotificationCompat.Builder(ctx, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Pocket AI is replying…")
                .setContentText(status ?: "Writing the answer")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openIntent(ctx, null))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

        private fun openIntent(ctx: Context, convId: String?): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (convId != null) putExtra(EXTRA_OPEN_CHAT, convId)
            }
            return PendingIntent.getActivity(
                ctx, convId?.hashCode() ?: 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        @Suppress("MissingPermission")
        private fun notify(ctx: Context, id: Int, n: Notification) {
            // Without the notification permission this is silently skipped; the reply still runs.
            runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
        }
    }
}
