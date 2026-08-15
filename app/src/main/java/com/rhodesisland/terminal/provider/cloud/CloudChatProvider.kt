package com.rhodesisland.terminal.provider.cloud

import com.rhodesisland.terminal.config.isFreeProxyBaseUrl
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.remote.ChatMessageDto
import com.rhodesisland.terminal.data.remote.DirectLlmClient
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.provider.ChatProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call

/**
 * 云端聊天 Provider —— 直连对话商 OpenAI 兼容 API（不经任何服务器代理）。
 *
 * 流程：
 * 1. 读取 [SettingsRepository] 的 ApiConfig（baseUrl / apiKey / model）。
 * 2. 构建 OpenAI 消息（content 为纯文本或多模态 image_url 数组）。
 * 3. [DirectLlmClient.chatStream] 发起 SSE 流式请求，逐 token 回调累积文本。
 *
 * 取消：经 onCall 持有底层 [Call]，[cancel] 直接 cancel；
 * 另由协程 invokeOnCompletion 兜底（见 DirectLlmClient）。
 */
class CloudChatProvider(
    private val client: DirectLlmClient,
    private val settings: SettingsRepository,
) : ChatProvider {

    override val type: ChatProviderType = ChatProviderType.CLOUD

    @Volatile private var activeCall: Call? = null

    override suspend fun chat(
        messages: List<ChatMessage>,
        onChunk: (String) -> Unit,
    ): String {
        activeCall = null

        val apiConfig = settings.getApiConfigNow()
        // 内置免费服务商（Cloudflare 代理）无需客户端 key（key 由代理注入）；其余服务商必须配置
        if (apiConfig.apiKey.isBlank() && !isFreeProxyBaseUrl(apiConfig.baseUrl)) {
            throw Exception("请先在设置页配置 API Key")
        }

        val apiMessages = messages.map { msg ->
            ChatMessageDto(
                role = msg.role,
                content = toJsonElement(msg),
            )
        }

        val content = client.chatStream(
            baseUrl = apiConfig.baseUrl,
            apiKey = apiConfig.apiKey,
            model = apiConfig.model,
            messages = apiMessages,
            onChunk = onChunk,
            onCall = { activeCall = it },
            deepThinking = settings.getDeepThinkingNow(),
        )
        if (content.isBlank()) throw Exception("API 返回空内容")
        return content
    }

    override fun cancel() {
        activeCall?.cancel()
    }

    /**
     * 构建 content：纯文本 -> JsonPrimitive；含图片 -> OpenAI 多模态数组
     * （image_url + text）。多模态数组直接透传给兼容 OpenAI 格式的对话商 API。
     */
    private fun toJsonElement(msg: ChatMessage): JsonElement {
        val images = msg.multimodalImages
        if (images.isEmpty()) return JsonPrimitive(msg.content)
        return buildJsonArray {
            for (b64 in images) {
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        // 按 base64 魔术字节推断真实 MIME，避免把 PNG/WebP 一律标成 image/jpeg
                        put("url", "data:${detectImageMime(b64)};base64,$b64")
                    }
                })
            }
            add(buildJsonObject {
                put("type", "text")
                put("text", msg.content.ifBlank { "请描述这张图片的内容。" })
            })
        }
    }

    /** 根据 base64 数据的魔术字节推断图片 MIME，避免一律标记为 image/jpeg */
    private fun detectImageMime(b64: String): String = when {
        b64.startsWith("/9j/") -> "image/jpeg"
        b64.startsWith("iVBORw0KGgo") -> "image/png"
        b64.startsWith("R0lGODlh") -> "image/gif"
        b64.startsWith("UklGR") -> "image/webp"
        b64.startsWith("Qk") -> "image/bmp"
        else -> "image/jpeg"
    }
}
