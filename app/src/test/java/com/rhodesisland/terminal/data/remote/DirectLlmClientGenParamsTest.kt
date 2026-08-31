package com.rhodesisland.terminal.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 云端生成参数（温度 / 单次输出上限）注入测试。
 *
 * 契约（设置页「自定义生成参数」开关语义，默认关）：
 * - 参数为 null 时请求体**完全不含** temperature / max_tokens 字段（走服务商默认，
 *   与「默认不开启」需求一致）；提供哪个就只注入哪个；
 * - 字段为 OpenAI 标准协议、全端点兼容，无需白名单；
 * - Anthropic 路径：显式 maxTokens 覆盖内置 ANTHROPIC_MAX_TOKENS(8192)；temperature 同样注入。
 */
class DirectLlmClientGenParamsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DirectLlmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DirectLlmClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 指向 MockWebServer 的 baseUrl（DirectLlmClient 会追加 /chat/completions）。 */
    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun messages() = listOf(ChatMessageDto("user", JsonPrimitive("hi")))

    private fun completionBody(content: String): String {
        val encoded = JsonPrimitive(content).toString()
        return """{"choices":[{"message":{"role":"assistant","content":$encoded}}]}"""
    }

    // ===== OpenAI 兼容路径 =====

    @Test
    fun genParams_injectedOnlyWhenProvided() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(completionBody("ok")))
        client.chatOnce(baseUrl(), "k", "m", messages(), temperature = 0.7f, maxTokens = 512)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"temperature\":0.7"))
        assertTrue(body.contains("\"max_tokens\":512"))
    }

    @Test
    fun genParams_absentByDefault_neverSent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(completionBody("ok")))
        val out = client.chatOnce(baseUrl(), "k", "m", messages())
        assertEquals("ok", out)

        val body = server.takeRequest().body.readUtf8()
        assertFalse("默认必须不带生成参数（服务商默认值语义）", body.contains("temperature"))
        assertFalse(body.contains("max_tokens"))
    }

    @Test
    fun genParams_partialProvided_onlyThatFieldInjected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(completionBody("ok")))
        client.chatOnce(baseUrl(), "k", "m", messages(), temperature = 0.2f)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"temperature\":0.2"))
        assertFalse(body.contains("max_tokens"))
    }

    @Test
    fun streamingBody_alsoCarriesGenParams() = runBlocking {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\ndata: [DONE]\n\n"
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse),
        )
        client.chatStream(
            baseUrl(), "k", "m", messages(),
            onChunk = {}, onCall = null, deepThinking = false,
            temperature = 1.1f, maxTokens = 256,
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"temperature\":1.1"))
        assertTrue(body.contains("\"max_tokens\":256"))
    }

    // ===== Anthropic 路径 =====

    @Test
    fun anthropic_explicitMaxTokensOverridesBuiltIn_andTemperatureInjected() {
        val body = client.buildAnthropicBody(
            model = "claude-x", messages = messages(), stream = true,
            maxTokens = 777, baseUrl = "https://api.anthropic.com/v1",
            temperature = 0.5f,
        )
        assertTrue(body.contains("\"max_tokens\":777"))
        assertTrue(body.contains("\"temperature\":0.5"))
    }

    @Test
    fun anthropic_defaultMaxTokens_noTemperatureField() {
        val body = client.buildAnthropicBody(
            model = "claude-x", messages = messages(), stream = true,
            maxTokens = 8192, baseUrl = "https://api.anthropic.com/v1",
        )
        assertTrue(body.contains("\"max_tokens\":8192"))
        assertFalse(body.contains("temperature"))
    }
}
