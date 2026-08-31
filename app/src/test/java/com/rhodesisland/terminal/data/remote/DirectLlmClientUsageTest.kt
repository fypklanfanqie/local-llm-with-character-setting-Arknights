package com.rhodesisland.terminal.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 云端 token 用量观测测试（MockWebServer）。
 *
 * 背景：此前全项目不读响应 usage、不发 stream_options.include_usage，云端 prompt
 * 前缀缓存命中率完全不可观测——锚定布局等优化的效果无法验证。
 * 契约：
 * - OpenAI 路径对已知兼容端点注入 stream_options.include_usage；未知端点绝不注入；
 *   无论是否请求，响应中的 usage 一律解析并回调（cached_tokens 是命中的核心字段）。
 * - Anthropic 路径从 message_start（流）/顶层 usage（非流）解析 input/output/cache_read/
 *   cache_creation 字段。
 * - 所有成功调用进 [LlmUsageStats] 环形统计（进程级命中率观测入口）。
 */
class DirectLlmClientUsageTest {

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
        LlmUsageStats.clear()
    }

    /** 指向 MockWebServer 的 baseUrl（DirectLlmClient 会追加 /chat/completions）。 */
    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun messages() = listOf(
        ChatMessageDto("system", JsonPrimitive("你是测试")),
        ChatMessageDto("user", JsonPrimitive("hi")),
    )

    /** 组装一次非流式 OpenAI chat completion 响应体；可选附加 usage 对象原文。 */
    private fun completionBody(content: String, usageJson: String? = null): String {
        val encoded = JsonPrimitive(content).toString()
        val usage = usageJson?.let { ",\"usage\":$it" }.orEmpty()
        return """{"choices":[{"message":{"role":"assistant","content":$encoded}}]$usage}"""
    }

    // ==================== 白名单谓词 ====================

    @Test
    fun streamedUsageWhitelist_matchesKnownOpenAiCompatibleHostsOnly() {
        assertTrue(client.supportsStreamedUsage("https://api.deepseek.com/v1"))
        assertTrue(client.supportsStreamedUsage("https://api.openai.com/v1"))
        assertTrue(client.supportsStreamedUsage("https://dashscope.aliyuncs.com/compatible-mode/v1"))
        assertTrue(client.supportsStreamedUsage("https://api.siliconflow.cn/v1"))
        assertFalse(
            "未知端点/中转站不得注入 stream_options（可能被上游拒收 400）",
            client.supportsStreamedUsage(baseUrl()),
        )
    }

    // ==================== usage 解析器（JVM 直测 internal）====================

    @Test
    fun openAiUsageParser_readsCachedPromptTokens() {
        val obj = Json.parseToJsonElement(
            """{"prompt_tokens":120,"completion_tokens":18,"prompt_tokens_details":{"cached_tokens":96}}""",
        ).jsonObject
        assertEquals(LlmTokenUsage(promptTokens = 120, completionTokens = 18, cachedTokens = 96), parseOpenAiUsage(obj))
    }

    @Test
    fun openAiUsageParser_defaultsCacheToZero_whenDetailsMissing() {
        val obj = Json.parseToJsonElement("""{"prompt_tokens":10,"completion_tokens":3}""").jsonObject
        assertEquals(LlmTokenUsage(10, 3, 0, 0), parseOpenAiUsage(obj))
    }

    @Test
    fun openAiUsageParser_returnsNull_whenNoTokenCounts() {
        val obj = Json.parseToJsonElement("""{"unexpected":"x"}""").jsonObject
        assertNull(parseOpenAiUsage(obj))
    }

    @Test
    fun anthropicUsageParser_mapsAllFourFields() {
        val obj = Json.parseToJsonElement(
            """{"input_tokens":210,"output_tokens":35,"cache_read_input_tokens":180,"cache_creation_input_tokens":12}""",
        ).jsonObject
        assertEquals(LlmTokenUsage(210, 35, 180, 12), parseAnthropicUsage(obj))
    }

    @Test
    fun anthropicUsageParser_toleratesMissingCacheFields() {
        val obj = Json.parseToJsonElement("""{"input_tokens":5,"output_tokens":2}""").jsonObject
        assertEquals(LlmTokenUsage(5, 2, 0, 0), parseAnthropicUsage(obj))
    }

    // ==================== e2e · OpenAI 流式 ====================

    @Test
    fun stream_neverInjectsStreamOptionsOnUnknownEndpoint_butParsesUsageChunkAnyway() = runBlocking {
        // OpenAI 分块协议中 usage 附着在最后一个含空 delta 的 chunk 上（include_usage 语义）；
        // 这里即便没请求也照常解析——命中率观测不应依赖上游是否配合。
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":30}}}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse))

        var reported: LlmTokenUsage? = null
        val out = client.chatStream(
            baseUrl(), "k", "m", messages(),
            onChunk = {}, onCall = null, deepThinking = false,
            onUsage = { reported = it },
        )
        assertEquals("你好", out)
        assertNotNull("响应携带 usage 时必须回调", reported)
        assertEquals(50, reported!!.promptTokens)
        assertEquals(30, reported!!.cachedTokens)

        val body = server.takeRequest().body.readUtf8()
        assertFalse("未知端点不得注入 stream_options", body.contains("stream_options"))
        assertTrue("回调成功即应记入进程级统计", LlmUsageStats.snapshot().any { it.promptTokens == 50 })
    }

    @Test
    fun stream_swallowsSilentlyWhenServerNeverReturnsUsage() = runBlocking {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse))
        var callbackFired = false
        val out = client.chatStream(
            baseUrl(), "k", "m", messages(),
            onChunk = {}, onCall = null, deepThinking = false,
            onUsage = { callbackFired = true },
        )
        assertEquals("ok", out)
        assertFalse("无 usage 不应触发回调", callbackFired)
        assertTrue("无有效用量不得污染统计", LlmUsageStats.snapshot().isEmpty())
    }

    // ==================== e2e · OpenAI 非流式 ====================

    @Test
    fun chatOnce_reportsUsageFromPlainJson() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(completionBody("好", """{"prompt_tokens":88,"completion_tokens":9,"prompt_tokens_details":{"cached_tokens":40}}""")),
        )
        var reported: LlmTokenUsage? = null
        val out = client.chatOnce(baseUrl(), "k", "m", messages(), onUsage = { reported = it })
        assertEquals("好", out)
        assertEquals(LlmTokenUsage(88, 9, 40, 0), reported)
    }

    // ==================== e2e · Anthropic 流式 ====================

    @Test
    fun anthropicStream_parsesMessageStartUsageIncludingCacheFields() = runBlocking {
        val sse = buildString {
            append("data: {\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"usage\":{\"input_tokens\":210,\"output_tokens\":1,\"cache_read_input_tokens\":180,\"cache_creation_input_tokens\":12}}}\n\n")
            append("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"嗨\"}}\n\n")
            append("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":35}}\n\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse))

        var reported: LlmTokenUsage? = null
        val out = client.chatStream(
            // 以路径命中 Anthropic 路由判定（isAnthropicEndpoint 对 /v1/messages 后缀放行）
            server.url("/v1/messages").toString().trimEnd('/'), "k", "claude-x", messages(),
            onChunk = {}, onCall = null, deepThinking = false,
            onUsage = { reported = it },
        )
        assertEquals("嗨", out)
        assertNotNull("message_start.usage 必须被解析回调", reported)
        assertEquals(210, reported!!.promptTokens)
        assertEquals("message_delta 的 output_tokens 应覆盖为最终值", 35, reported!!.completionTokens)
        assertEquals(180, reported!!.cachedTokens)
        assertEquals(12, reported!!.cacheWriteTokens)
    }

    // ==================== 统计环形缓冲 ====================

    @Test
    fun usageStats_ringBufferKeepsMostRecentWithinCapacity() {
        repeat(40) { LlmUsageStats.record(LlmTokenUsage(it, 0, it, 0)) }
        val snap = LlmUsageStats.snapshot()
        assertEquals(32, snap.size)
        assertEquals("容量满后应保留最新的 32 条", 39, snap.last().promptTokens)
        assertEquals(8, snap.first().promptTokens)
    }
}
