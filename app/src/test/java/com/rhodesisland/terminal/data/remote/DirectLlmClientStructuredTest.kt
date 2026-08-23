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
        // 白名单判定按端点域名（生产语义变更后不再看模型名）；MockWebServer 地址恒为
        // localhost，端到端正向路径不可达，故谓词直测（internal）+ 负向 e2e 兜底。
        assertTrue(client.supportsJsonObjectResponse("https://api.openai.com/v1", "gpt-4o"))
        assertTrue(client.supportsJsonObjectResponse("https://api.deepseek.com/chat/completions", "any"))
        assertTrue(client.supportsJsonObjectResponse("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-max"))
        assertTrue(client.supportsJsonObjectResponse("https://api.siliconflow.cn/v1", "deepseek-chat"))
        assertFalse("localhost 测试端点不在白名单", client.supportsJsonObjectResponse(baseUrl(), "deepseek-chat"))

        server.enqueue(MockResponse().setResponseCode(200).setBody(completionBody("""{"subject":"x"}""")))
        val result = client.chatOnceStructured(
            baseUrl(), "test-key", "deepseek-chat", messages(), responseFormatJson = true,
        )
        assertEquals("""{"subject":"x"}""", result)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertFalse("非白名单端点即使模型名匹配也不注入 response_format", body.contains("\"response_format\""))
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
