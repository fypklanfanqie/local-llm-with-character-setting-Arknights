package com.rhodesisland.terminal.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ChatInputBar] 停止按钮行为 instrumentation 测试（Task 8）。
 *
 * 覆盖：空闲显示「发送」；生成中显示「停止生成」且点击一次只触发一次 onStop；
 * stopRequested 后显示「正在停止」并禁用重复点击；停止完成后恢复「发送」。
 *
 * 需在真机/模拟器运行：`./gradlew connectedDebugAndroidTest`。
 */
@RunWith(AndroidJUnit4::class)
class ChatStopGenerationTest {

    @get:Rule
    val rule = createComposeRule()

    private fun chatInputBar(
        isStreaming: Boolean,
        stopRequested: Boolean,
        onSend: () -> Unit = {},
        onStop: () -> Unit = {},
    ) {
        rule.setContent {
            ChatInputBar(
                text = "",
                isStreaming = isStreaming,
                stopRequested = stopRequested,
                images = emptyList(),
                files = emptyList(),
                onTextChange = {},
                onSend = onSend,
                onStop = onStop,
                onPickImage = {},
                onPickFile = {},
                onRemoveImage = {},
                onRemoveFile = {},
            )
        }
    }

    @Test
    fun idleShowsSendAndSends() {
        var sendClicks = 0
        var stopClicks = 0
        chatInputBar(isStreaming = false, stopRequested = false, onSend = { sendClicks++ }, onStop = { stopClicks++ })
        rule.onNodeWithContentDescription("发送").assertExists()
        rule.onNodeWithContentDescription("停止生成").assertDoesNotExist()
        rule.onNodeWithContentDescription("发送").performClick()
        rule.waitForIdle()
        assertEquals(1, sendClicks)
        assertEquals(0, stopClicks)
    }

    @Test
    fun streamingShowsStop_andClickCallsOnStopOnce() {
        var sendClicks = 0
        var stopClicks = 0
        var stopRequested by mutableStateOf(false)
        rule.setContent {
            ChatInputBar(
                text = "",
                isStreaming = true,
                stopRequested = stopRequested,
                images = emptyList(),
                files = emptyList(),
                onTextChange = {},
                onSend = { sendClicks++ },
                onStop = { stopClicks++; stopRequested = true },
                onPickImage = {},
                onPickFile = {},
                onRemoveImage = {},
                onRemoveFile = {},
            )
        }
        rule.onNodeWithContentDescription("发送").assertDoesNotExist()
        rule.onNodeWithContentDescription("停止生成").assertExists().performClick()
        rule.waitForIdle()
        // 点击一次 -> onStop 恰好一次；之后进入「正在停止」并禁用，避免重复触发。
        assertEquals(1, stopClicks)
        assertEquals(0, sendClicks)
        rule.onNodeWithContentDescription("正在停止").assertExists().assertIsNotEnabled()
        rule.onNodeWithContentDescription("停止生成").assertDoesNotExist()
    }

    @Test
    fun stopRequestedDisablesRepeatClicks() {
        var stopClicks = 0
        chatInputBar(isStreaming = true, stopRequested = true, onStop = { stopClicks++ })
        // 正在停止状态：按钮存在且禁用（clickable(enabled=false) 移除 click 动作，无法再触发 onStop）。
        rule.onNodeWithContentDescription("正在停止").assertExists().assertIsNotEnabled()
    }

    @Test
    fun afterStopCompletesRestoresSend() {
        var isStreaming by mutableStateOf(true)
        var stopRequested by mutableStateOf(false)
        rule.setContent {
            ChatInputBar(
                text = "",
                isStreaming = isStreaming,
                stopRequested = stopRequested,
                images = emptyList(),
                files = emptyList(),
                onTextChange = {},
                onSend = {},
                onStop = { stopRequested = true; isStreaming = false },
                onPickImage = {},
                onPickFile = {},
                onRemoveImage = {},
                onRemoveFile = {},
            )
        }
        rule.onNodeWithContentDescription("停止生成").assertExists()
        // 模拟停止完成：isStreaming=false -> 恢复发送按钮。
        isStreaming = false
        rule.waitForIdle()
        rule.onNodeWithContentDescription("发送").assertExists()
        rule.onNodeWithContentDescription("停止生成").assertDoesNotExist()
    }
}
