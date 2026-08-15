package com.rhodesisland.terminal.ui.chat

import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.MessageCompletionState
import com.rhodesisland.terminal.data.model.SeedanceConfig

/**
 * 自动视频触发快照（Task 7）。
 *
 * 在 `sendMessage` 发送起点捕获一次，供回复完成后判定是否创建自动视频任务：
 * Provider 切换、会话自动视频开关变化、API/Seedance 配置变化都不会影响本次判定
 * （「生成期间切 Provider 使用捕获时的请求快照」）。
 * [userMessageId] 为用户消息落库后的行 ID（发送起点捕获配置，落库后回填）。
 */
data class AutoVideoTriggerSnapshot(
    val provider: ChatProviderType,
    val enabled: Boolean,
    val userMessageId: Long,
    val apiConfig: ApiConfig,
    val seedanceConfig: SeedanceConfig,
)

/**
 * 纯触发策略（Task 7）：是否应为该助手回复创建自动视频任务。
 *
 * 仅当全部成立才触发：
 *  - [AutoVideoTriggerSnapshot.provider] == [ChatProviderType.CLOUD]（本地聊天不触发）；
 *  - [AutoVideoTriggerSnapshot.enabled]（会话级自动视频开关，新会话默认关闭）；
 *  - 助手回复 [ChatMessage.completionState] == [MessageCompletionState.COMPLETE]
 *    （用户停止/超时/截断的部分回复不触发）；
 *  - 助手回复正文非空白（无实际输出不触发）。
 *
 * 纯 Kotlin，无 Android/Room 依赖，可 JVM 单测。
 */
fun shouldCreateAutoVideo(
    snapshot: AutoVideoTriggerSnapshot,
    assistant: ChatMessage,
): Boolean =
    snapshot.provider == ChatProviderType.CLOUD &&
        snapshot.enabled &&
        assistant.completionState == MessageCompletionState.COMPLETE &&
        assistant.content.isNotBlank()
