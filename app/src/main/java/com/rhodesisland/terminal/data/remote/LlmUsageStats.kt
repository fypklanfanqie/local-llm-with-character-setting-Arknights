package com.rhodesisland.terminal.data.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 单次云端请求 token 用量（前缀缓存观测核心载体）。
 *
 * [cachedTokens] 是命中率观测的核心字段：OpenAI 兼容端点取
 * `usage.prompt_tokens_details.cached_tokens`，Anthropic 取 `cache_read_input_tokens`。
 * [cacheWriteTokens] 仅 Anthropic 有（cache_creation_input_tokens），OpenAI 恒 0。
 */
data class LlmTokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

/** 进程级环形用量统计：最近 N 次云端调用的命中率长期观测入口（内存态，无 DB 依赖，JVM 可测）。 */
object LlmUsageStats {
    private const val CAPACITY = 32
    private val recent = ArrayDeque<LlmTokenUsage>()

    /** 记录一次有效用量；容量满丢弃最旧。 */
    fun record(usage: LlmTokenUsage) {
        synchronized(recent) {
            if (recent.size >= CAPACITY) recent.removeFirst()
            recent.addLast(usage)
        }
    }

    /** 快照（时间序）。用于调试页/日志汇总，勿在热路径高频拷贝。 */
    fun snapshot(): List<LlmTokenUsage> = synchronized(recent) { recent.toList() }

    fun clear() { synchronized(recent) { recent.clear() } }
}

/** 解析 OpenAI 兼容端点的 usage 对象；无任何 token 计数时返回 null。internal 供 JVM 单测直测。 */
internal fun parseOpenAiUsage(usage: JsonObject): LlmTokenUsage? {
    val prompt = intOf(usage, "prompt_tokens")
    val completion = intOf(usage, "completion_tokens")
    if (prompt == null && completion == null) return null
    // DeepSeek / OpenAI 均把命中数放在 prompt_tokens_details.cached_tokens
    val cached = usage["prompt_tokens_details"]
        ?.let { runCatching { it.jsonObject }.getOrNull() }
        ?.get("cached_tokens")?.jsonPrimitive?.intOrNull
    return LlmTokenUsage(prompt ?: 0, completion ?: 0, cached ?: 0, 0)
}

/** 解析 Anthropic 的 usage 对象；无任何 token 计数时返回 null。internal 供 JVM 单测直测。 */
internal fun parseAnthropicUsage(usage: JsonObject): LlmTokenUsage? {
    val input = intOf(usage, "input_tokens")
    val output = intOf(usage, "output_tokens")
    if (input == null && output == null) return null
    return LlmTokenUsage(
        promptTokens = input ?: 0,
        completionTokens = output ?: 0,
        cachedTokens = intOf(usage, "cache_read_input_tokens") ?: 0,
        cacheWriteTokens = intOf(usage, "cache_creation_input_tokens") ?: 0,
    )
}

private fun intOf(obj: JsonObject, key: String): Int? =
    obj[key]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
