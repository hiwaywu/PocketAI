package com.pocketai.chat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

@Serializable
data class ToolEvent(
    val id: String = UUID.randomUUID().toString(),
    val kind: String, // "search" | "fetch"
    val label: String,
    val failed: Boolean = false,
)

@Serializable
data class Source(val title: String, val url: String)

/**
 * What one AI reply cost. Input/output/cached come from the provider; the four est* fields are
 * the app's estimate of how the input tokens split between chat history, web results, photos
 * and pages shared from the old built-in browser (scaled so they add up to the real input total).
 */
@Serializable
data class ReplyUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val steps: Int = 1,
    val searches: Int = 0,
    val pages: Int = 0,
    val estHistory: Long = 0,
    val estWeb: Long = 0,
    val estPhotos: Long = 0,
    val estShared: Long = 0,
    /** True when the provider didn't report token counts and these are the app's estimates. */
    val estimated: Boolean = false,
    /** How long the whole reply took, including searches and page reads. */
    val seconds: Int = 0,
    /** The provider's price multiplier for this model when the reply was written (NT Media), else null. */
    val multiplier: Double? = null,
    /** Which API answered: its saved name (e.g. "NT Media") and address host. Null before 1.10. */
    val apiName: String? = null,
    val apiHost: String? = null,
    /** Only written by 1.10; no longer used. */
    val keyHint: String? = null,
) {
    val total: Long get() = input + output
    /** Tokens as the provider bills them (total × multiplier); equals [total] when there's no multiplier. */
    val billed: Long get() = ModelRates.billed(total, multiplier)

    /** Groups replies by API name. Replies from before 1.10 only know NT Media (from the multiplier) or nothing. */
    val apiGroup: String
        get() = apiName ?: if (multiplier != null) NT_MEDIA_OLD else EARLIER

    companion object {
        const val NT_MEDIA_OLD = "\u0000ntmedia"
        const val EARLIER = "\u0000earlier"
    }
}

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" | "assistant"
    val text: String = "",
    val images: List<String> = emptyList(),
    val pageTitle: String? = null,
    val pageUrl: String? = null,
    val pageText: String? = null,
    /** Where the page came from: null = the old built-in browser, "screen" = the Pocket AI button, "selection" = selected text. */
    val pageSource: String? = null,
    /** The app a screen or selection came from (package name), to tell whether a new tap is about the same thing. */
    val pageApp: String? = null,
    val toolEvents: List<ToolEvent> = emptyList(),
    val sources: List<Source> = emptyList(),
    /** Tool-call turns (OpenAI format) that led to this answer, replayed in later requests. */
    val apiTrail: List<JsonObject> = emptyList(),
    /** Text of the last round only; what gets sent back as this assistant turn. */
    val finalContent: String? = null,
    val error: String? = null,
    val model: String? = null,
    val usage: ReplyUsage? = null,
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    /** Pinned chats stay at the top of the chat list (2.2). */
    val pinned: Boolean = false,
)

class ChatRepository(context: Context) {
    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    val imageDir = File(context.filesDir, "images").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(): List<Conversation> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { json.decodeFromString<Conversation>(f.readText()) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun save(c: Conversation) {
        val tmp = File(dir, "${c.id}.tmp")
        tmp.writeText(json.encodeToString(Conversation.serializer(), c))
        tmp.renameTo(File(dir, "${c.id}.json"))
    }

    fun delete(c: Conversation) {
        File(dir, "${c.id}.json").delete()
        c.messages.flatMap { it.images }.forEach { File(it).delete() }
    }
}

object ImageUtil {
    private const val MAX_DIM = 1568

    /** Copies a picked/captured photo into app storage as a downscaled, upright JPEG. */
    fun importImage(context: Context, uri: Uri, outDir: File): File? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIM) sample *= 2
        val decoded = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
        var bmp = scaleDown(decoded, MAX_DIM)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val file = File(outDir, UUID.randomUUID().toString() + ".jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file
    }.getOrNull()

    private fun scaleDown(b: Bitmap, maxDim: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxDim) return b
        val f = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt(), (b.height * f).roundToInt(), true)
    }

    fun toDataUrl(path: String): String? = runCatching {
        "data:image/jpeg;base64," + Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    fun loadBitmap(path: String, maxDim: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
