package com.caye.commithelper.llm

import com.caye.commithelper.settings.ProviderConfig
import com.caye.commithelper.settings.ProviderKind
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A fully built HTTP call. Kept separate from transport so it can be unit-tested. */
data class HttpRequestSpec(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * One wire protocol. Implementations must be pure (no I/O) so request construction is testable.
 */
interface LlmProtocol {
    val displayName: String

    fun buildRequest(
        config: ProviderConfig,
        apiKey: String,
        prompt: String,
        maxTokens: Int,
        temperature: Double,
    ): HttpRequestSpec

    /** Extracts the assistant text, or throws [LlmException] when the payload is unusable. */
    fun parseResponse(responseBody: String): String
}

internal fun joinUrl(baseUrl: String, path: String): String =
    baseUrl.trimEnd('/') + "/" + path.trimStart('/')

object OpenAiCompatProtocol : LlmProtocol {
    override val displayName = "OpenAI-compatible"

    override fun buildRequest(
        config: ProviderConfig,
        apiKey: String,
        prompt: String,
        maxTokens: Int,
        temperature: Double,
    ): HttpRequestSpec {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("temperature", temperature)
            addProperty("max_tokens", maxTokens)
            add("messages", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", prompt)
                })
            })
        }.toString()

        return HttpRequestSpec(
            url = joinUrl(config.baseUrl, "chat/completions"),
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $apiKey",
            ),
            body = body,
        )
    }

    override fun parseResponse(responseBody: String): String {
        val root = JsonParser.parseString(responseBody)
        val choices = root.asJsonObject.getAsJsonArray("choices")
            ?: throw LlmException("OpenAI-compatible response has no 'choices'")
        if (choices.size() == 0) throw LlmException("OpenAI-compatible response has empty 'choices'")
        val message = choices[0].asJsonObject.getAsJsonObject("message")
            ?: throw LlmException("OpenAI-compatible response has no 'message'")
        val content = message.get("content")
        if (content == null || content.isJsonNull) throw LlmException("OpenAI-compatible response has no content")
        return content.asString
    }
}

object AnthropicProtocol : LlmProtocol {
    override val displayName = "Anthropic"

    const val API_VERSION = "2023-06-01"

    override fun buildRequest(
        config: ProviderConfig,
        apiKey: String,
        prompt: String,
        maxTokens: Int,
        temperature: Double,
    ): HttpRequestSpec {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("max_tokens", maxTokens)
            addProperty("temperature", temperature)
            add("messages", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            })
        }.toString()

        return HttpRequestSpec(
            url = joinUrl(config.baseUrl, "v1/messages"),
            headers = mapOf(
                "Content-Type" to "application/json",
                "x-api-key" to apiKey,
                "anthropic-version" to API_VERSION,
            ),
            body = body,
        )
    }

    override fun parseResponse(responseBody: String): String {
        val content = JsonParser.parseString(responseBody).asJsonObject.getAsJsonArray("content")
            ?: throw LlmException("Anthropic response has no 'content'")
        val sb = StringBuilder()
        for (element in content) {
            val obj = element.asJsonObject
            if (obj.get("type")?.asString == "text") {
                sb.append(obj.get("text")?.asString.orEmpty())
            }
        }
        if (sb.isEmpty()) throw LlmException("Anthropic response contains no text block")
        return sb.toString()
    }
}

object GeminiProtocol : LlmProtocol {
    override val displayName = "Gemini"

    override fun buildRequest(
        config: ProviderConfig,
        apiKey: String,
        prompt: String,
        maxTokens: Int,
        temperature: Double,
    ): HttpRequestSpec {
        val body = JsonObject().apply {
            add("contents", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    add("parts", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", prompt) })
                    })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", temperature)
                addProperty("maxOutputTokens", maxTokens)
            })
        }.toString()

        return HttpRequestSpec(
            url = joinUrl(config.baseUrl, "v1beta/models/${config.model}:generateContent"),
            headers = mapOf(
                "Content-Type" to "application/json",
                "x-goog-api-key" to apiKey,
            ),
            body = body,
        )
    }

    override fun parseResponse(responseBody: String): String {
        val root = JsonParser.parseString(responseBody).asJsonObject
        val candidates = root.getAsJsonArray("candidates")
            ?: throw LlmException("Gemini response has no 'candidates'")
        if (candidates.size() == 0) {
            val blockReason = root.getAsJsonObject("promptFeedback")?.get("blockReason")?.asString
            throw LlmException("Gemini returned no candidates" + (blockReason?.let { " (blocked: $it)" } ?: ""))
        }
        val parts = candidates[0].asJsonObject
            .getAsJsonObject("content")
            ?.getAsJsonArray("parts")
            ?: throw LlmException("Gemini response has no parts")
        val sb = StringBuilder()
        for (element in parts) {
            element.asJsonObject.get("text")?.let { if (!it.isJsonNull) sb.append(it.asString) }
        }
        if (sb.isEmpty()) throw LlmException("Gemini response contains no text part")
        return sb.toString()
    }
}

/** Executes a protocol request. The only place that performs network I/O. */
class LlmClient(
    private val protocol: LlmProtocol,
    private val config: ProviderConfig,
    private val apiKey: String,
    private val httpClient: HttpClient = defaultClient(config.timeoutSeconds),
) {

    fun complete(prompt: String, maxTokens: Int = config.maxTokens, temperature: Double = config.temperature): String {
        if (config.baseUrl.isBlank()) throw LlmException("Base URL is not configured")
        if (config.model.isBlank()) throw LlmException("Model is not configured")

        val spec = protocol.buildRequest(config, apiKey, prompt, maxTokens, temperature)
        val request = HttpRequest.newBuilder(URI.create(spec.url))
            .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong().coerceAtLeast(1)))
            .apply { spec.headers.forEach { (k, v) -> header(k, v) } }
            .POST(HttpRequest.BodyPublishers.ofString(spec.body))
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw LlmException("Request failed: ${e.message}", e)
        }

        val status = response.statusCode()
        if (status !in 200..299) {
            throw LlmException("${protocol.displayName} returned HTTP $status: ${sanitize(response.body())}")
        }
        return protocol.parseResponse(response.body())
    }

    /** Never echo the API key back into a user-visible message. */
    private fun sanitize(body: String): String {
        val clipped = body.replace(apiKey, "***").trim()
        return if (clipped.length > 400) clipped.take(400) + "..." else clipped
    }

    companion object {
        fun defaultClient(timeoutSeconds: Int): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}

object LlmProtocols {
    fun of(kind: ProviderKind): LlmProtocol? = when (kind) {
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatProtocol
        ProviderKind.ANTHROPIC -> AnthropicProtocol
        ProviderKind.GEMINI -> GeminiProtocol
        ProviderKind.LOCAL_ONLY -> null
    }
}
