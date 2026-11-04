package com.pocketai.chat.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val DEFAULT_SYSTEM_PROMPT =
    "You are a helpful, friendly assistant chatting with the user on their Android phone. " +
        "Answer the question directly first, then add useful detail. Keep a natural, conversational tone, " +
        "be concise, and use Markdown (lists, tables, bold) when it helps readability."

/** The default instructions shipped in 1.0/1.1. */
private const val OLD_DEFAULT_PROMPT =
    "You are a helpful, friendly assistant chatting with the user on their Android phone. " +
        "Be clear and concise, and use Markdown formatting when it helps readability."

/** Quick-fill addresses shown under the API address field. The user can type any address. */
val ADDRESS_SUGGESTIONS = listOf(
    "MWAPI" to "https://api.mwapi.dev",
    "NT Media" to "https://api.ntmedia.store",
    "OpenCode Go" to "https://opencode.ai/inference/go/v1",
    "OpenAI" to "https://api.openai.com/v1",
    "Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai",
    "OpenRouter" to "https://openrouter.ai/api/v1",
    "DeepSeek" to "https://api.deepseek.com",
    "Grok" to "https://api.x.ai/v1",
    "Groq" to "https://api.groq.com/openai/v1",
)

@Serializable
enum class SearchEngine(val label: String) {
    DEEPSEEK("DeepSeek Search - best results (DeepSeek key)"),
    DUCKDUCKGO("DuckDuckGo (free, no key)"),
    BRAVE("Brave Search (API key)"),
    TAVILY("Tavily (API key)"),
}

/** One saved API: address + key + the model last used with it. */
@Serializable
data class ApiProfile(
    val id: String = "api-" + UUID.randomUUID().toString().take(8),
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val models: List<String> = emptyList(),
)

@Serializable
data class AppSettings(
    // The connection chats use right now (may or may not be saved as a profile).
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val models: List<String> = emptyList(),
    val profiles: List<ApiProfile> = emptyList(),
    val activeProfileId: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val webTools: Boolean = true,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val searchKeys: Map<String, String> = emptyMap(),
    /** Endpoints that rejected `stream_options`; we stop sending it to them. */
    val noStreamUsage: List<String> = emptyList(),
) {
    val activeProfile: ApiProfile? get() = profiles.firstOrNull { it.id == activeProfileId }
    /** Key for the chosen search engine. DeepSeek falls back to a saved DeepSeek API's key. */
    val searchKey: String
        get() = searchKeys[searchEngine.name].orEmpty().ifBlank {
            if (searchEngine == SearchEngine.DEEPSEEK) deepSeekKey else ""
        }

    /** DeepSeek key: the one typed in search settings, else the current or any saved DeepSeek API's key. */
    val deepSeekKey: String
        get() = searchKeys[SearchEngine.DEEPSEEK.name].orEmpty().ifBlank {
            (if (baseUrl.contains("deepseek.com", true)) apiKey else null)
                ?: profiles.firstOrNull { it.baseUrl.contains("deepseek.com", true) }?.apiKey.orEmpty()
        }
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** Short name for the current connection, e.g. "MWAPI" or "api.openai.com". */
    val connectionName: String
        get() = activeProfile?.takeIf { it.baseUrl == baseUrl && it.apiKey == apiKey }?.name
            ?: hostOf(baseUrl).ifBlank { "No API" }

    /** True when the connection fields match a saved profile exactly. */
    fun matchesSavedProfile(): Boolean {
        val p = activeProfile ?: return false
        return p.baseUrl == baseUrl && p.apiKey == apiKey
    }
}

fun hostOf(url: String): String = runCatching { Uri.parse(url.trim()).host.orEmpty() }
    .getOrDefault("").removePrefix("www.")

fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')

/**
 * Settings are stored as one JSON blob encrypted with an Android Keystore key,
 * so saved API keys never sit on disk in plain text.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pocketai_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): AppSettings {
        val enc = prefs.getString(KEY, null) ?: return AppSettings()
        val plain = Crypto.decrypt(enc) ?: return AppSettings()
        val s = runCatching { json.decodeFromString<AppSettings>(plain) }.getOrDefault(AppSettings())
        // Upgrade the built-in instructions from older versions; edited instructions are left alone.
        return if (s.systemPrompt.trim() == OLD_DEFAULT_PROMPT) s.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT) else s
    }

    fun save(settings: AppSettings) {
        prefs.edit().putString(KEY, Crypto.encrypt(json.encodeToString(AppSettings.serializer(), settings))).apply()
    }

    private companion object {
        const val KEY = "settings_v1"
    }
}

object Crypto {
    private const val ALIAS = "pocketai_settings_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()
}
