package com.rhodesisland.terminal.ui.chat

import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.MessageCompletionState
import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldCreateAutoVideo] 纯触发策略测试（Task 7）。
 *
 * 触发仅当：Provider == CLOUD 且会话自动视频开关已开启 且 助手消息 COMPLETE 且正文非空白。
 * 任一条件不满足（本地 Provider / 开关关闭 / 用户停止 / 空白回复）都不触发。
 * 快照在发送起点捕获：Provider 切换、开关变化、配置变化都不影响本次判定。
 */
class AutoVideoTriggerPolicyTest {

    private fun snapshot(
        provider: ChatProviderType = ChatProviderType.CLOUD,
        enabled: Boolean = true,
        userMessageId: Long = 1L,
    ) = AutoVideoTriggerSnapshot(
        provider = provider,
        enabled = enabled,
        userMessageId = userMessageId,
        apiConfig = ApiConfig(baseUrl = "https://api.deepseek.com/v1", apiKey = "llm-key", model = "deepseek-chat"),
        seedanceConfig = SeedanceConfig(
            apiKey = "seedance-key",
            variant = SeedanceModelVariant.STANDARD,
            resolution = SeedanceResolution.P720,
            ratio = SeedanceRatio.PORTRAIT,
            durationSeconds = 5,
        ),
    )

    private fun assistant(
        content: String = "这是回答",
        completionState: MessageCompletionState = MessageCompletionState.COMPLETE,
    ) = ChatMessage(
        role = "assistant",
        content = content,
        completionState = completionState,
    )

    @Test
    fun cloudCompleteTriggers() {
        assertTrue(shouldCreateAutoVideo(snapshot(), assistant()))
    }

    @Test
    fun localProviderDoesNotTrigger() {
        assertFalse(
            shouldCreateAutoVideo(snapshot(provider = ChatProviderType.LOCAL), assistant()),
        )
    }

    @Test
    fun disabledFlagDoesNotTrigger() {
        assertFalse(shouldCreateAutoVideo(snapshot(enabled = false), assistant()))
    }

    @Test
    fun stoppedAssistantDoesNotTrigger() {
        assertFalse(
            shouldCreateAutoVideo(snapshot(), assistant(completionState = MessageCompletionState.STOPPED_PARTIAL)),
        )
        assertFalse(
            shouldCreateAutoVideo(snapshot(), assistant(completionState = MessageCompletionState.STOPPED_BEFORE_FINAL)),
        )
    }

    @Test
    fun blankContentDoesNotTrigger() {
        assertFalse(shouldCreateAutoVideo(snapshot(), assistant(content = "")))
        assertFalse(shouldCreateAutoVideo(snapshot(), assistant(content = "   \n  ")))
    }

    @Test
    fun snapshotCarriesCapturedUserMessageId() {
        // 快照携带发送起点捕获的 userMessageId（落库后回填），判定不修改它。
        val s = snapshot(userMessageId = 42L)
        assertTrue(shouldCreateAutoVideo(s, assistant()))
        assertEquals(42L, s.userMessageId)
    }

    @Test
    fun enabledButCloudOff_doesNotTrigger() {
        // 双开关：仅会话开关开启还不够，Provider 必须为 CLOUD。
        assertFalse(
            shouldCreateAutoVideo(snapshot(provider = ChatProviderType.LOCAL, enabled = true), assistant()),
        )
    }
}
