package com.pocketai.chat.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.coroutineContext

class ApiException(val code: Int, message: String) : Exception(message)

data class ToolCall(val id: String, val name: String, val arguments: String)

data class StreamResult(
    val content: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val promptTokens: Long,
    val completionTokens: Long,
    /** Input tokens the provider served from its cache (billed cheaper), when reported. */
    val cachedTokens: Long = 0,
)

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

/**
 * Same rule as NovelAI's providerEndpoint():
 *  - an address already ending in the path is used as-is
 *  - an address ending in /v1 (or /api/v1, /v1beta/openai, …) gets the path appended
 *  - anything else gets /v1 + path
 */
fun providerEndpoint(baseUrl: String, path: String): String {
    val base = baseUrl.trim().trimEnd('/')
    if (base.lowercase().endsWith(path)) return base
    if (Regex("""/(api/)?v\d+[a-z0-9]*(/openai)?$""", RegexOption.IGNORE_CASE).containsMatchIn(base) ||
        base.lowercase().endsWith("/openai")
    ) return base + path
    return "$base/v1$path"
}

/** True for OpenCode Zen / Go, which route and cache by an `x-opencode-session` header. */
fun isOpenCode(baseUrl: String): Boolean =
    baseUrl.toHttpUrlOrNull()?.host?.lowercase()?.let { it == "opencode.ai" || it.endsWith(".opencode.ai") } == true

/** Client for any OpenAI-compatible Chat Completions API (MWAPI, OpenAI, Gemini, OpenRouter, …). */
class ChatClient(private val http: OkHttpClient, private val userAgent: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    private fun checkedUrl(url: String): String {
        url.toHttpUrlOrNull() ?: throw ApiException(0, "That API address doesn't look valid: $url")
        return url
    }

    /** Asks the provider which models this key can use (GET …/v1/models), like NovelAI's "Load available models". */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ApiException(401, "Enter your API key before loading models.")
        val request = Request.Builder()
            .url(checkedUrl(providerEndpoint(baseUrl, "/models")))
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("x-api-key", apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .build()
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, describeError(resp.code, raw, "Could not load models"))
            val payload = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: throw ApiException(resp.code, "The provider's model list wasn't valid JSON.")
            val result = payload["result"] as? JsonObject
            val items = listOfNotNull(
                payload["data"] as? JsonArray,
                payload["models"] as? JsonArray,
                result?.get("data") as? JsonArray,
                result?.get("models") as? JsonArray,
            ).flatten()
            items.mapNotNull { el ->
                when (el) {
                    is JsonObject -> el.str("id") ?: el.str("name") ?: el.str("model")
                    is JsonPrimitive -> el.content
                    else -> null
                }
            }.map { it.removePrefix("models/").trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        }
    }

    /**
     * Streams one chat completion. [onText] receives visible text as it arrives.
     * [sessionId] (the chat's id) is sent as `x-opencode-session` to OpenCode, which requires a
     * stable id per conversation for routing and prompt caching.
     */
    suspend fun streamChat(
        baseUrl: String,
        apiKey: String,
        body: JsonObject,
        sessionId: String,
        onText: (String) -> Unit,
        onReasoning: () -> Unit,
    ): StreamResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(checkedUrl(providerEndpoint(baseUrl, "/chat/completions")))
            .header("User-Agent", userAgent)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "text/event-stream, application/json")
            .apply { if (isOpenCode(baseUrl)) header("x-opencode-session", sessionId) }
            .post(body.toString().toRequestBody(jsonType))
            .build()
        val call = http.newCall(request)
        coroutineScope {
            // Pressing Stop cancels the coroutine; this cancels the HTTP call so the blocking read ends.
            val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                readStream(call, onText, onReasoning)
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                throw e
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun readStream(call: Call, onText: (String) -> Unit, onReasoning: () -> Unit): StreamResult {
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                val raw = resp.body?.string().orEmpty()
                throw ApiException(resp.code, describeError(resp.code, raw, "Request failed"))
            }
            val body = resp.body ?: throw ApiException(resp.code, "The provider sent an empty response.")
            val contentType = resp.header("Content-Type").orEmpty()
            if (contentType.contains("application/json")) return parseWhole(body.string(), onText)

            val text = StringBuilder()
            val calls = sortedMapOf<Int, Builder>()
            var finish: String? = null
            var promptTokens = 0L
            var completionTokens = 0L
            var cachedTokens = 0L
            val source = body.source()
            var done = false
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") { done = true; break }
                if (data.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                obj["error"]?.let { throw ApiException(resp.code, "The provider reported an error: ${errorText(it)}") }
                (obj["usage"] as? JsonObject)?.let { u ->
                    promptTokens = u.long("prompt_tokens") ?: promptTokens
                    completionTokens = u.long("completion_tokens") ?: completionTokens
                    cachedTokens = cachedOf(u) ?: cachedTokens
                }
                val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
                choice.str("finish_reason")?.let { finish = it }
                val delta = choice["delta"] as? JsonObject ?: continue
                delta.str("content")?.let { if (it.isNotEmpty()) { text.append(it); onText(it) } }
                if (!delta.str("reasoning_content").isNullOrEmpty() || !delta.str("reasoning").isNullOrEmpty()) onReasoning()
                (delta["tool_calls"] as? JsonArray)?.forEach { el ->
                    val tc = el as? JsonObject ?: return@forEach
                    val id = tc.str("id").orEmpty()
                    val index = (tc["index"] as? JsonPrimitive)?.intOrNull
                        ?: if (id.isNotEmpty() && calls.values.none { it.id == id }) calls.size else (calls.keys.lastOrNull() ?: 0)
                    val b = calls.getOrPut(index) { Builder() }
                    if (id.isNotEmpty()) b.id = id
                    (tc["function"] as? JsonObject)?.let { f ->
                        f.str("name")?.let { if (b.name.isEmpty()) b.name = it }
                        f.str("arguments")?.let { b.args.append(it) }
                        // Some providers send arguments as an object instead of a string.
                        (f["arguments"] as? JsonObject)?.let { b.args.append(it.toString()) }
                    }
                }
            }
            // A stream cut off by a dropped connection just ends; don't mistake that for a finished reply.
            if (!done && finish == null) throw IOException("The connection closed before the reply finished.")
            return StreamResult(
                text.toString(),
                calls.values.filter { it.name.isNotEmpty() }.map { ToolCall(it.id, it.name, it.args.toString()) },
                finish, promptTokens, completionTokens, cachedTokens,
            )
        }
    }

    /** Fallback when a provider ignores stream=true and returns one JSON object. */
    private fun parseWhole(raw: String, onText: (String) -> Unit): StreamResult {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw ApiException(0, "Unexpected response from the provider.")
        obj["error"]?.let { throw ApiException(0, errorText(it)) }
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val msg = choice?.get("message") as? JsonObject
        val content = msg?.str("content").orEmpty()
        if (content.isNotEmpty()) onText(content)
        val calls = (msg?.get("tool_calls") as? JsonArray)?.mapNotNull { el ->
            val tc = el as? JsonObject ?: return@mapNotNull null
            val f = tc["function"] as? JsonObject ?: return@mapNotNull null
            ToolCall(tc.str("id").orEmpty(), f.str("name").orEmpty(), f.str("arguments") ?: f["arguments"]?.toString().orEmpty())
        }.orEmpty()
        val usage = obj["usage"] as? JsonObject
        return StreamResult(
            content, calls, choice?.str("finish_reason"),
            usage?.long("prompt_tokens") ?: 0, usage?.long("completion_tokens") ?: 0, usage?.let { cachedOf(it) } ?: 0,
        )
    }

    /**
     * OpenAI reports prompt_tokens_details.cached_tokens; some proxies use cache_read_input_tokens;
     * OpenCode puts it in usage.opencode.tokens.cache_read.
     */
    private fun cachedOf(u: JsonObject): Long? =
        (u["prompt_tokens_details"] as? JsonObject)?.long("cached_tokens")
            ?: u.long("cache_read_input_tokens")
            ?: ((u["opencode"] as? JsonObject)?.get("tokens") as? JsonObject)?.long("cache_read")

    private class Builder {
        var id = ""
        var name = ""
        val args = StringBuilder()
    }

    private fun errorText(el: JsonElement): String = when (el) {
        is JsonObject -> el.str("message") ?: el.str("type") ?: el.toString()
        is JsonPrimitive -> el.content
        else -> el.toString()
    }

    private fun describeError(code: Int, raw: String, prefix: String): String {
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        val obj = when (parsed) {
            is JsonObject -> parsed
            is JsonArray -> parsed.firstOrNull() as? JsonObject
            else -> null
        }
        val detail = obj?.let { o -> o["error"]?.let { errorText(it) } ?: o.str("message") }
            ?: raw.take(300).ifBlank { "no details" }
        val hint = when (code) {
            401, 403 -> " — check the API key."
            404 -> " — check the API address and model name."
            429 -> " — rate limit or out of credit."
            else -> ""
        }
        return "$prefix (HTTP $code): $detail$hint"
    }
}
