package com.rhodesisland.terminal.data.remote

import kotlinx.coroutines.runBlocking
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
 * [DirectLlmClient.chatOnceStructured] 结构化 JSON 输出测试（Task 4，MockWebServer）。
 *
 * 覆盖：白名单供应商注入 response_format=json_object、非白名单绝不注入、
 * 既有 [DirectLlmClient.chatOnce] 请求体不变（不含 response_format）、
 * Bearer 鉴权头、JSON content 正确提取。
 */
class DirectLlmClientStructuredTest {

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

    private fun messages() = listOf(
        ChatMessageDto("system", JsonPrimitive("你是导演")),
        ChatMessageDto("user", JsonPrimitive("生成 JSON")),
    )

    /** 组装一次非流式 chat completion 响应体；content 用 JsonPrimitive 正确转义。 */
    private fun completionBody(content: String): String {
        val encoded = JsonPrimitive(content).toString()
        return """{"choices":[{"message":{"role":"assistant","content":$encoded}}]}"""
    }

    @Test
    fun allowlistedProvider_injectsResponseFormatJsonObject() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(completionBody("""{"subject":"x"}""")))
        val result = client.chatOnceStructured(
            baseUrl(), "test-key", "deepseek-chat", messages(), responseFormatJson = true,
        )
        assertEquals("""{"subject":"x"}""", result)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue("白名单供应商应注入 response_format", body.contains("\"response_format\""))
        assertTrue("response_format 应为 json_object", body.contains("\"json_object\""))
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun nonAllowlistedProvider_neverInjectsResponseFormat() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(completionBody("""{"subject":"x"}""")))
        val result = client.chatOnceStructured(
            baseUrl(), "test-key", "glm-4-flash", messages(), responseFormatJson = true,
        )
        assertEquals("""{"subject":"x"}""", result)

        val body = server.takeRequest().body.readUtf8()
        assertFalse("非白名单供应商不得注入 response_format", body.contains("response_format"))
    }

    @Test
    fun chatOnceNeverInjectsResponseFormat() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(completionBody("hello")))
        val result = client.chatOnce(baseUrl(), "test-key", "deepseek-chat", messages())
        assertEquals("hello", result)

        val body = server.takeRequest().body.readUtf8()
        assertFalse("既有 chatOnce 不得注入 response_format", body.contains("response_format"))
    }
}
