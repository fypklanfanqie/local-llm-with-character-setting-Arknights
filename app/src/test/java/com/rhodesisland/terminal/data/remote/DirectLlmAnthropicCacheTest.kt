package com.rhodesisland.terminal.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic 请求体的前缀缓存断点测试。
 *
 * 背景：Claude 端点的前缀缓存需要显式 cache_control 断点，无断点则永远全量重算。
 * 契约：仅官方 api.anthropic.com 域注入（第三方 Claude 中转对块数组 system 的兼容性
 * 不一，保守白名单防 400）；稳定头折叠为一个带 ephemeral 断点的顶层 system 块数组；
 * 非官方端点保持纯字符串 system、请求体零 cache_control。
 */
class DirectLlmAnthropicCacheTest {

    private val client = DirectLlmClient(okhttp3.OkHttpClient())

    private fun messages() = listOf(
        ChatMessageDto("system", JsonPrimitive("世界书静态头+人设")),
        ChatMessageDto("system", JsonPrimitive("[设定参考]绿灯尾块")),
        ChatMessageDto("user", JsonPrimitive("hi")),
    )

    // ==================== 白名单谓词 ====================

    @Test
    fun promptCacheWhitelist_officialHostOnly() {
        assertTrue(client.supportsAnthropicPromptCache("https://api.anthropic.com/v1"))
        assertTrue(client.supportsAnthropicPromptCache("https://api.anthropic.com/v1/messages"))
        assertFalse("第三方 Claude 中转不注入（兼容性不可控）", client.supportsAnthropicPromptCache("https://relay.example.com/claude/v1/messages"))
        assertFalse("localhost 测试端点不在白名单", client.supportsAnthropicPromptCache("http://localhost:8080/v1/messages"))
    }

    // ==================== 请求体形态 ====================

    @Test
    fun officialHost_systemBecomesEphemeralBlockArray() {
        val body = client.buildAnthropicBody(
            model = "claude-x", messages = messages(), stream = true,
            maxTokens = 8192, baseUrl = "https://api.anthropic.com/v1",
        )
        val obj = Json.parseToJsonElement(body).jsonObject
        // system 为块数组：单块包裹全部 system 文本，附 ephemeral 断点
        val system = obj["system"]!!.jsonArray
        assertEquals(1, system.size)
        val block = system.first().jsonObject
        assertEquals("text", block["type"]?.toString()?.trim('"'))
        assertTrue("块必须携带 ephemeral 缓存断点", body.contains("\"cache_control\":{\"type\":\"ephemeral\"}"))
        assertEquals(
            "两条 system 应合并进同一断点块（稳定头+尾块保持既有 join 语义）",
            "\"世界书静态头+人设\\n[设定参考]绿灯尾块\"",
            block["text"].toString(),
        )
        // 消息区不得出现缓存字段
        assertFalse("messages 数组内不得有第二个断点", obj["messages"].toString().contains("cache_control"))
    }

    @Test
    fun nonOfficialHost_plainStringSystem_noCacheControl() {
        val body = client.buildAnthropicBody(
            model = "claude-x", messages = messages(), stream = true,
            maxTokens = 8192, baseUrl = "https://relay.example.com/claude/v1/messages",
        )
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("非官方端点保持字符串 system", "\"世界书静态头+人设\\n[设定参考]绿灯尾块\"", obj["system"].toString())
        assertFalse("请求体不得含任何 cache_control", body.contains("cache_control"))
    }
}
