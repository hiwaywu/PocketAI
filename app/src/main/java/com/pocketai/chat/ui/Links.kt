package com.pocketai.chat.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import com.pocketai.chat.data.Conversation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opens a link from the chat: in its own app when one is installed (YouTube at the right moment, Reddit,
 * X…), otherwise in a Chrome Custom Tab over the chat, so Back returns to the conversation.
 */
fun openLink(context: Context, url: String) {
    val uri = Uri.parse(url.trim())
    if (uri.scheme != "http" && uri.scheme != "https") return
    if (Build.VERSION.SDK_INT >= 30) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
            )
            return
        } catch (_: ActivityNotFoundException) {
            // No app for this link: open it in a tab.
        }
    }
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (_: Exception) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** The Android share sheet (Messages, WhatsApp, Notes, Gmail…). */
fun shareText(context: Context, text: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text.take(100_000))
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** A chat as readable Markdown text, for sharing or saving. */
fun exportChat(c: Conversation): String = buildString {
    append("# ").append(c.title).append("\n")
    append("_Pocket AI chat · ").append(SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(c.updatedAt))).append("_\n\n")
    c.messages.forEach { m ->
        if (m.role == "user") {
            append("**You:** ")
            if (m.images.isNotEmpty()) append("[${m.images.size} photo(s)] ")
            m.pageTitle?.let { append("[page: $it] ") }
            append(m.text.trim()).append("\n\n")
        } else {
            val text = (m.finalContent?.ifBlank { null } ?: m.text).trim()
            if (text.isEmpty() && m.error == null) return@forEach
            append("**AI").append(m.model?.let { " ($it)" } ?: "").append(":**\n")
            append(text.ifEmpty { "(error: ${m.error})" }).append("\n")
            if (m.sources.isNotEmpty()) append("\nSources: ").append(m.sources.joinToString(", ") { it.url }).append("\n")
            append("\n")
        }
    }
}
