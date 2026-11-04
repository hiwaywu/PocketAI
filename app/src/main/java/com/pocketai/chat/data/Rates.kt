package com.pocketai.chat.data

import kotlin.math.roundToLong

/**
 * NT Media (https://api.ntmedia.store) charges each model's tokens times a "usage multiplier"
 * (its /models list says `billing_mode: usage_multiplier` but doesn't include the number).
 * These are the multipliers from NT Media's price list, November 2026. Other providers are
 * shown as plain tokens.
 */
object ModelRates {
    private val NTMEDIA = mapOf(
        // Claude
        "claude-opus-5-5" to 6.0,
        "claude-opus-5" to 5.0,
        "claude-opus-4-8" to 4.0,
        "claude-opus-4-7" to 4.0,
        "claude-opus-4-6" to 4.0,
        "claude-sonnet-5-5" to 3.5,
        "claude-sonnet-5" to 2.5,
        "claude-sonnet-4-6" to 2.0,
        "claude-haiku-5-5" to 1.0,
        "claude-fable-5-1" to 10.0,
        "claude-fable-5" to 10.0,
        // GPT
        "gpt-6.1-sol" to 4.0,
        "gpt-6.1-terra" to 2.5,
        "gpt-6-astra" to 8.0,
        "gpt-6-sol" to 4.0,
        "gpt-6-luna" to 1.5,
        "gpt-5.6-sol" to 4.0,
        "gpt-5.6-terra" to 3.0,
        "gpt-5.6-luna" to 1.7,
        "codex-auto-review" to 4.0,
        // Gemini
        "gemini-4-argon" to 7.0,
        "gemini-3.8-flash" to 2.0,
        "gemini-3.1-pro" to 4.0,
        // Grok
        "grok-4.7" to 3.0,
        "grok-4.6" to 3.0,
        "grok-4.5" to 3.0,
        // DeepSeek
        "deepseek-v4-pro" to 2.0,
        "deepseek-v4-flash" to 1.0,
        "deepseek-v4-flash-uncensored" to 3.0,
        // Kimi
        "kimi-k3.1-pro" to 5.0,
        "kimi-k3" to 5.0,
        "kimi-k2.7-code" to 1.5,
        "kimi-k2.6" to 1.5,
        // Qwen
        "qwen-3.8-max" to 5.0,
        "qwen-3.8-flash-next" to 2.0,
        // GLM
        "glm-5.3" to 4.0,
        "glm-5.3-flash" to 1.5,
        // MiMo
        "mimo-v2.6-pro" to 2.5,
        "mimo-v2.6-flash" to 1.0,
        // Muse
        "muse-wonder" to 4.0,
        "muse-spark-1.3" to 1.5,
        // HY4
        "hy4-preview" to 3.0,
    )

    fun isNtMedia(baseUrl: String): Boolean = hostOf(baseUrl).lowercase().let { it == "ntmedia.store" || it.endsWith(".ntmedia.store") }

    /** The multiplier this provider bills [model] at, or null when it isn't NT Media / the model isn't listed. */
    fun multiplier(baseUrl: String, model: String): Double? =
        if (isNtMedia(baseUrl)) NTMEDIA[model.trim().lowercase()] else null

    /** "×3.5", "×1", "×1.7". */
    fun label(m: Double): String = "×" + if (m % 1.0 == 0.0) m.toLong().toString() else m.toString()

    fun billed(tokens: Long, multiplier: Double?): Long = if (multiplier == null) tokens else (tokens * multiplier).roundToLong()
}
