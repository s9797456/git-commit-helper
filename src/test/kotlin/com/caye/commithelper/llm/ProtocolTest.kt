package com.caye.commithelper.llm

import com.caye.commithelper.settings.ProviderConfig
import com.caye.commithelper.settings.ProviderKind
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolRequestTest {

    private fun config(baseUrl: String, model: String) = ProviderConfig().apply {
        this.baseUrl = baseUrl
        this.model = model
        temperature = 0.2
        maxTokens = 512
        timeoutSeconds = 30
    }

    @Test
    fun `openai compatible request targets chat completions with bearer auth`() {
        val spec = OpenAiCompatProtocol.buildRequest(
            config("https://api.deepseek.com/v1/", "deepseek-chat"),
            apiKey = "sk-test",
            prompt = "PROMPT",
            maxTokens = 256,
            temperature = 0.3,
        )
        assertEquals("https://api.deepseek.com/v1/chat/completions", spec.url)
        assertEquals("Bearer sk-test", spec.headers["Authorization"])

        val body = JsonParser.parseString(spec.body).asJsonObject
        assertEquals("deepseek-chat", body.get("model").asString)
        assertEquals(256, body.get("max_tokens").asInt)
        assertEquals(0.3, body.get("temperature").asDouble, 1e-9)
        val message = body.getAsJsonArray("messages")[0].asJsonObject
        assertEquals("system", message.get("role").asString)
        assertEquals("PROMPT", message.get("content").asString)
    }

    @Test
    fun `anthropic request uses the messages endpoint and api version header`() {
        val spec = AnthropicProtocol.buildRequest(
            config("https://api.anthropic.com", "claude-3-5-haiku-latest"),
            apiKey = "ak-test",
            prompt = "PROMPT",
            maxTokens = 256,
            temperature = 0.3,
        )
        assertEquals("https://api.anthropic.com/v1/messages", spec.url)
        assertEquals("ak-test", spec.headers["x-api-key"])
        assertEquals(AnthropicProtocol.API_VERSION, spec.headers["anthropic-version"])
        assertFalse(spec.headers.containsKey("Authorization"), "Anthropic must not use bearer auth")

        val body = JsonParser.parseString(spec.body).asJsonObject
        assertEquals(256, body.get("max_tokens").asInt)
        assertEquals("user", body.getAsJsonArray("messages")[0].asJsonObject.get("role").asString)
    }

    @Test
    fun `gemini request puts the model in the path and the key in a header`() {
        val spec = GeminiProtocol.buildRequest(
            config("https://generativelanguage.googleapis.com", "gemini-2.0-flash"),
            apiKey = "gk-test",
            prompt = "PROMPT",
            maxTokens = 256,
            temperature = 0.3,
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            spec.url,
        )
        assertEquals("gk-test", spec.headers["x-goog-api-key"])
        assertFalse(spec.url.contains("gk-test"), "the key must not leak into the URL")

        val body = JsonParser.parseString(spec.body).asJsonObject
        assertEquals(
            "PROMPT",
            body.getAsJsonArray("contents")[0].asJsonObject
                .getAsJsonArray("parts")[0].asJsonObject.get("text").asString,
        )
        assertEquals(256, body.getAsJsonObject("generationConfig").get("maxOutputTokens").asInt)
    }

    @Test
    fun `local only has no protocol`() {
        assertEquals(null, LlmProtocols.of(ProviderKind.LOCAL_ONLY))
        assertTrue(LlmProtocols.of(ProviderKind.OPENAI_COMPATIBLE) is OpenAiCompatProtocol)
    }
}

class ProtocolResponseTest {

    @Test
    fun `openai compatible extracts the assistant message`() {
        val text = OpenAiCompatProtocol.parseResponse(
            """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}""",
        )
        assertEquals("hello", text)
    }

    @Test
    fun `openai compatible rejects an error payload`() {
        assertThrows(LlmException::class.java) {
            OpenAiCompatProtocol.parseResponse("""{"error":{"message":"bad key"}}""")
        }
    }

    @Test
    fun `anthropic joins text blocks`() {
        val text = AnthropicProtocol.parseResponse(
            """{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}""",
        )
        assertEquals("ab", text)
    }

    @Test
    fun `anthropic rejects a payload without text blocks`() {
        assertThrows(LlmException::class.java) {
            AnthropicProtocol.parseResponse("""{"content":[]}""")
        }
    }

    @Test
    fun `gemini extracts the first candidate text`() {
        val text = GeminiProtocol.parseResponse(
            """{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}""",
        )
        assertEquals("hi", text)
    }

    @Test
    fun `gemini reports a blocked prompt`() {
        val thrown = assertThrows(LlmException::class.java) {
            GeminiProtocol.parseResponse("""{"promptFeedback":{"blockReason":"SAFETY"},"candidates":[]}""")
        }
        assertTrue(thrown.message!!.contains("SAFETY"))
    }

    @Test
    fun `llm client refuses to run without base url or model`() {
        val config = ProviderConfig().apply { baseUrl = ""; model = "m" }
        assertThrows(LlmException::class.java) {
            LlmClient(OpenAiCompatProtocol, config, "key").complete("prompt")
        }
    }

    @Test
    fun `llm client sanitizes the api key out of error messages`() {
        val config = ProviderConfig().apply { baseUrl = "http://127.0.0.1:1"; model = "m"; timeoutSeconds = 1 }
        val thrown = assertThrows(LlmException::class.java) {
            LlmClient(OpenAiCompatProtocol, config, "sk-secret").complete("prompt")
        }
        assertFalse(thrown.message.orEmpty().contains("sk-secret"))
    }
}
