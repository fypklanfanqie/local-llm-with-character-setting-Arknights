package com.rhodesisland.terminal.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 单条聊天消息（OpenAI 兼容格式）。
 * content 为纯文本 JsonPrimitive 或多模态数组（image_url + text），直接透传给对话商。
 */
@Serializable
data class ChatMessageDto(
    val role: String,
    val content: kotlinx.serialization.json.JsonElement,
)

/**
 * 直连对话商 OpenAI 兼容 API 客户端（不经任何服务器代理）。
 *
 * - [chatStream]：SSE 流式对话，逐 token 回调累积文本（与 ChatProvider 契约一致）。
 * - [chatOnce]：非流式一次性调用（翻译 / 文档提取用）。
 *
 * 取消：在当前协程 [Job] 上注册 invokeOnCompletion，取消时关闭底层 [Call]；
 * 调用方也可经 [chatStream] 的 onCall 持有 [Call] 主动 cancel。
 */
class DirectLlmClient(
    private val client: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    },
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** SSE 流式对话。onChunk 收到累积文本，返回完整文本。
     *  deepThinking=true 时解析 reasoning_content 并以 <think>...</think> 注入累积文本（复用本地思考展示），
     *  并对支持的供应商注入 enable_thinking。 */
    suspend fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        onChunk: (String) -> Unit,
        onCall: ((Call) -> Unit)? = null,
        deepThinking: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val request = buildRequest(
            endpoint = buildEndpoint(baseUrl),
            apiKey = apiKey,
            body = buildBody(model, messages, stream = true, baseUrl = baseUrl, deepThinking = deepThinking),
            accept = "text/event-stream",
        )
        executeStreaming(request, onChunk, onCall, deepThinking)
    }

    /** 非流式一次性对话。返回 choices[0].message.content。 */
    suspend fun chatOnce(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
    ): String = chatOnceInternal(baseUrl, apiKey, model, messages, responseFormatJson = false)

    /** 非流式一次性对话，可请求结构化 JSON 输出。
     *  仅当 [responseFormatJson]=true 且供应商在白名单内时才注入 response_format=json_object，
     *  否则与 [chatOnce] 完全一致（仍依赖严格文本 JSON 指令）。 */
    suspend fun chatOnceStructured(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        responseFormatJson: Boolean,
    ): String = chatOnceInternal(baseUrl, apiKey, model, messages, responseFormatJson)

    private suspend fun chatOnceInternal(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        responseFormatJson: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val request = buildRequest(
            endpoint = buildEndpoint(baseUrl),
            apiKey = apiKey,
            body = buildBody(
                model = model,
                messages = messages,
                stream = false,
                baseUrl = baseUrl,
                deepThinking = false,
                responseFormatJson = responseFormatJson && supportsJsonObjectResponse(baseUrl, model),
            ),
            accept = null,
        )
        val call = client.newCall(request)
        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw Exception(parseError(response.code, raw))
                parseFullContent(raw)
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive() // 被取消（call.cancel 触发流关闭）时抛 CancellationException
            throw Exception("网络错误: ${e.message ?: "请求失败"}", e)
        } finally {
            handle?.dispose()
            call.cancel()
        }
    }

    private suspend fun executeStreaming(
        request: Request,
        onChunk: (String) -> Unit,
        onCall: ((Call) -> Unit)?,
        deepThinking: Boolean,
    ): String {
        val call = client.newCall(request)
        onCall?.invoke(call)
        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        // 双缓冲：reasoningBuf 收推理内容（包装为 <think>），contentBuf 收正文。复用本地思考展示通道。
        val reasoningBuf = StringBuilder()
        val contentBuf = StringBuilder()
        var contentStarted = false
        try {
            call.execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    val raw = body?.string().orEmpty()
                    throw Exception(parseError(response.code, raw))
                }
                val isSse = body.contentType()?.subtype
                    ?.equals("event-stream", ignoreCase = true) == true
                if (isSse) {
                    val source = body.source()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        coroutineContext.ensureActive()
                        if (line.isBlank() || line.startsWith(":")) continue
                        if (!line.startsWith("data:", ignoreCase = true)) continue
                        val data = line.substringAfter("data:").trim()
                        if (data == "[DONE]") break
                        val (content, reasoning) = parseDelta(data)
                        if (!reasoning.isNullOrEmpty() && deepThinking) reasoningBuf.append(reasoning)
                        if (!content.isNullOrEmpty()) {
                            contentStarted = true
                            contentBuf.append(content)
                        }
                        onChunk(renderAccumulated(reasoningBuf, contentBuf, contentStarted))
                    }
                } else {
                    // 个别供应商忽略 stream:true，返回整段 JSON
                    val raw = body.string()
                    val content = parseFullContent(raw)
                    if (content.isNotEmpty()) {
                        contentStarted = true
                        contentBuf.append(content)
                        onChunk(renderAccumulated(reasoningBuf, contentBuf, contentStarted))
                    }
                }
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive() // 被取消时抛 CancellationException
            throw Exception("网络错误: ${e.message ?: "请求失败"}", e)
        } finally {
            handle?.dispose()
            call.cancel()
        }
        return renderAccumulated(reasoningBuf, contentBuf, contentStarted)
    }

    /**
     * 构建 OpenAI 兼容 chat/completions 端点。
     *
     * 自定义模型提供商（中转站/网关）常把「Base URL」直接填成完整 chat/completions 端点
     * （与 Seedance 中转站「可填完整地址或只填主机」一致）。此时若再无条件追加
     * /chat/completions 会拼成 .../chat/completions/chat/completions 导致 404。
     * 因此：已含该后缀则原样使用；否则按 OpenAI 约定追加。先 trim 空白，
     * 避免粘贴带入空格/换行生成非法 URL。
     */
    private fun buildEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions", ignoreCase = true)) {
            base
        } else {
            "$base/chat/completions"
        }
    }

    private fun buildBody(
        model: String,
        messages: List<ChatMessageDto>,
        stream: Boolean,
        baseUrl: String,
        deepThinking: Boolean,
        responseFormatJson: Boolean = false,
    ): String {
        val obj = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
            put("stream", stream)
            // 深度思考：对支持开关的供应商注入 enable_thinking（开=请求思考，关=显式停止）
            if (supportsThinkingToggle(model)) {
                put("enable_thinking", deepThinking)
            }
            // 结构化输出：仅显式请求 JSON 模式时注入（白名单判定已在调用侧完成，见 supportsJsonObjectResponse）
            if (responseFormatJson) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }
        return obj.toString()
    }

    private fun buildRequest(endpoint: String, apiKey: String, body: String, accept: String?): Request {
        val reqBody = body.toRequestBody(jsonMediaType)
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .apply { if (accept != null) header("Accept", accept) }
            .post(reqBody)
            .build()
    }

    /** 从一条 SSE data 负载解析 choices[0].delta.content；无 content 返回 null。 */
    private fun parseDeltaContent(data: String): String? = try {
        val obj = json.parseToJsonElement(data).jsonObject
        val choices = obj["choices"]?.jsonArray ?: return null
        val delta = choices.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return null
        delta["content"]?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }

    /** 从一条 SSE data 负载解析 (content, reasoning)；DeepSeek/Qwen 用 reasoning_content，部分用 reasoning。 */
    private fun parseDelta(data: String): Pair<String?, String?> = try {
        val obj = json.parseToJsonElement(data).jsonObject
        val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
        val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
        val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
        content to reasoning
    } catch (e: Exception) {
        null to null
    }

    /** 渲染累积文本：有推理内容时包装为 <think>...</think>（未开始正文时不闭合 -> 流式思考段）。 */
    private fun renderAccumulated(
        reasoningBuf: StringBuilder,
        contentBuf: StringBuilder,
        contentStarted: Boolean,
    ): String {
        val reasoning = reasoningBuf.toString()
        val content = contentBuf.toString()
        if (reasoning.isEmpty()) return content
        return if (contentStarted) "<think>$reasoning</think>$content" else "<think>$reasoning"
    }

    /** 是否支持 enable_thinking 参数（Qwen3 / QwQ 系模型才支持思考开关）。
     *  按模型能力判断、不按 baseUrl：baseUrl 命中 dashscope/siliconflow 但模型是 Qwen2.5 等
     *  不支持思考开关的模型时（如免费对话的 Qwen/Qwen2.5-7B-Instruct、自定义硅基端点），
     *  inject enable_thinking 会被上游拒收（400）。DeepSeek/GLM/OpenAI/其余自定义端点不注入。 */
    private fun supportsThinkingToggle(model: String): Boolean {
        val m = model.lowercase()
        return m.contains("qwen3") || m.contains("qwq")
    }

    /** 是否支持 response_format=json_object（结构化输出）。
     *  仅白名单供应商：OpenAI（api.openai.com / gpt-*）、DeepSeek（baseUrl 或模型含 deepseek）、
     *  Qwen（dashscope / siliconflow / qwen 系模型）。其余端点保守不注入，避免未知参数 400；
     *  生成器对返回内容仍严格解析，不依赖本白名单兜底。 */
    private fun supportsJsonObjectResponse(baseUrl: String, model: String): Boolean {
        val b = baseUrl.lowercase()
        val m = model.lowercase()
        return b.contains("api.openai.com") ||
            b.contains("deepseek") ||
            b.contains("dashscope") ||
            b.contains("siliconflow") ||
            m.startsWith("gpt-") ||
            m.startsWith("deepseek-") ||
            m.startsWith("qwen") ||
            m.startsWith("qwq")
    }

    /** 解析非流式 JSON 完整回复；若实为 SSE 文本则退化为逐行解析。 */
    private fun parseFullContent(raw: String): String = try {
        val obj = json.parseToJsonElement(raw).jsonObject
        val choices = obj["choices"]?.jsonArray ?: return parseSseToContent(raw)
        choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
    } catch (e: Exception) {
        parseSseToContent(raw)
    }

    /** 从一段 SSE 文本中拼接所有 delta.content（供应商忽略 stream 时兜底）。 */
    private fun parseSseToContent(raw: String): String {
        val sb = StringBuilder()
        raw.lineSequence().forEach { line ->
            if (!line.startsWith("data:", ignoreCase = true)) return@forEach
            val data = line.substringAfter("data:").trim()
            if (data.isEmpty() || data == "[DONE]") return@forEach
            parseDeltaContent(data)?.let { sb.append(it) }
        }
        return sb.toString()
    }

    /** 从错误响应体提取人类可读信息：error.message / error / message / HTTP {code}。 */
    private fun parseError(code: Int, raw: String): String {
        val msg = try {
            val obj = json.parseToJsonElement(raw).jsonObject
            when (val err = obj["error"]) {
                is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> err.contentOrNull
                else -> obj["message"]?.jsonPrimitive?.contentOrNull
            }
        } catch (e: Exception) {
            null
        }
        return if (msg.isNullOrBlank()) "HTTP $code" else "HTTP $code: $msg"
    }
}
