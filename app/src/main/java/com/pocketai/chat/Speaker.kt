package com.pocketai.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Reads replies aloud with the phone's own text-to-speech voice (no download, no tokens).
 * [speaking] is the id of the message being read, so its button can show Stop.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null
    private val _speaking = MutableStateFlow<String?>(null)
    val speaking: StateFlow<String?> = _speaking.asStateFlow()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            if (utteranceId != null && utteranceId.endsWith(END) && utteranceId.removeSuffix(END) == _speaking.value) _speaking.value = null
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (utteranceId?.substringBefore('#') == _speaking.value) _speaking.value = null
        }
    }

    /** Starts reading [markdown] (message [id]), or stops if that message is already being read. */
    fun toggle(id: String, markdown: String) {
        if (_speaking.value == id) {
            stop()
            return
        }
        val text = plainText(markdown)
        if (text.isBlank()) return
        _speaking.value = id
        val t = tts
        when {
            t == null -> {
                pending = id to text
                tts = TextToSpeech(app) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    if (!ready) {
                        _speaking.value = null
                        pending = null
                        return@TextToSpeech
                    }
                    tts?.setOnUtteranceProgressListener(listener)
                    pending?.let { (pid, ptext) -> pending = null; say(pid, ptext) }
                }
            }
            ready -> say(id, text)
            else -> pending = id to text
        }
    }

    fun stop() {
        pending = null
        _speaking.value = null
        tts?.stop()
    }

    private fun say(id: String, text: String) {
        val t = tts ?: return
        val locale = localeFor(text)
        if (t.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) t.language = locale
        val chunks = chunk(text, TextToSpeech.getMaxSpeechInputLength() - 200)
        chunks.forEachIndexed { i, c ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            t.speak(c, mode, null, if (i == chunks.lastIndex) id + END else "$id#$i")
        }
    }

    private fun localeFor(text: String): Locale {
        val letters = text.count { it.isLetter() }.coerceAtLeast(1)
        val han = text.count { it.code in 0x4E00..0x9FFF }
        val kana = text.count { it.code in 0x3040..0x30FF }
        val hangul = text.count { it.code in 0xAC00..0xD7AF }
        return when {
            kana * 5 > letters -> Locale.JAPANESE
            hangul * 3 > letters -> Locale.KOREAN
            han * 3 > letters -> Locale.forLanguageTag("zh-HK")
            Locale.getDefault().language in listOf("zh", "ja", "ko") -> Locale.US
            else -> Locale.getDefault()
        }
    }

    private fun chunk(text: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (para in text.split("\n")) {
            if (cur.length + para.length + 1 > max && cur.isNotEmpty()) {
                out += cur.toString()
                cur.clear()
            }
            if (para.length > max) para.chunked(max).forEach { out += it } else cur.append(para).append('\n')
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }

    companion object {
        private const val END = "#end"

        /** Markdown → what should be read out: link text without addresses, no symbols, no code blocks. */
        fun plainText(md: String): String = md
            .replace(Regex("""```[\s\S]*?```"""), " ")
            .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""^\s*\|?\s*:?-{2,}.*$""", RegexOption.MULTILINE), "")
            .replace(Regex("""^#{1,6}\s*""", RegexOption.MULTILINE), "")
            .replace(Regex("""^\s*[-*+]\s+""", RegexOption.MULTILINE), "")
            .replace(Regex("""[*_`#>|~]"""), " ")
            .replace("↑", " upvotes")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
