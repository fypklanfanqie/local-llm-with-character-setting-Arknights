package com.rhodesisland.terminal.provider

import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.remote.LlmTokenUsage

/**
 * 聊天 Provider 统一接口
 *
 * 聊天页面统一调用 ChatProvider，不直接调用具体模型。
 * 实现类：
 *  - CloudChatProvider：保持 CloudRun /chat 逻辑
 *  - LocalChatProvider：调用 MNN（本地 .mnn 模型推理）
 */
interface ChatProvider {

    val type: ChatProviderType

    /**
     * 流式聊天
     * @param messages 消息列表（含 system prompt）
     * @param onChunk  流式回调（累积文本）
     * @param onUsage  云端 token 用量回调（仅云端有有效回调；本地推理忽略）
     * @return 完整回复文本
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        onChunk: (String) -> Unit,
        onUsage: (suspend (LlmTokenUsage) -> Unit)? = null,
    ): String

    /** 取消当前推理 */
    fun cancel()
}
