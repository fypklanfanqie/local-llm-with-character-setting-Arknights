package com.rhodesisland.terminal.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import com.rhodesisland.terminal.ui.video.SeedanceVideoCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SeedanceVideoCard] instrumentation 测试（Task 7，仅 CI/真机执行）。
 *
 * 覆盖：
 * - 状态文案（已排队 / 正在生成… / 已取消）；
 * - QUEUED 取消按钮；
 * - FAILED_QUERY「继续查询」直接触发（无费用对话框）；
 * - EXPIRED / 歧义 FAILED_SUBMISSION 必须先弹费用确认对话框，确认后才触发重试；
 * - READY 仅在回调非空时渲染播放/保存按钮（Task 8 接线播放/导出）。
 */
@RunWith(AndroidJUnit4::class)
class SeedanceVideoCardTest {

    @get:Rule
    val rule = createComposeRule()

    private fun video(
        id: Long = 1L,
        state: SeedanceVideoState = SeedanceVideoState.QUEUED,
        requiresCostConfirmation: Boolean = false,
        errorMessage: String? = null,
    ) = SeedanceVideo(
        id = id,
        taskUuid = "uuid-$id",
        triggerType = "auto",
        sourceConversationId = 1L,
        sourceUserMessageId = 100L,
        sourceAssistantMessageId = 200L,
        characterIdSnapshot = "char-1",
        characterNameSnapshot = "阿米娅",
        characterRoleSnapshot = "罗德岛领袖",
        characterSystemPromptSnapshot = "你是阿米娅。",
        userTextSnapshot = "你好",
        assistantTextSnapshot = "回答",
        sceneDescriptionSnapshot = "",
        promptBaseUrlSnapshot = "https://api.example.com/v1",
        promptModelSnapshot = "doubao-text-pro",
        promptJson = null,
        finalPrompt = null,
        characterImageSourceSnapshot = "asset://amiya.png",
        backgroundImageSourceSnapshot = null,
        characterImagePath = null,
        characterImageMime = null,
        characterImageSha256 = null,
        backgroundImagePath = null,
        backgroundImageMime = null,
        backgroundImageSha256 = null,
        modelVariant = SeedanceModelVariant.STANDARD,
        resolution = SeedanceResolution.P720,
        ratio = SeedanceRatio.PORTRAIT,
        durationSeconds = 5,
        generateAudio = true,
        watermark = false,
        state = state,
        remoteStatus = null,
        generationAttempt = 0,
        submissionAttemptId = null,
        submissionStartedAt = null,
        requestFingerprint = null,
        remoteTaskId = null,
        remoteVideoUrl = null,
        remoteVideoUrlObservedAt = null,
        remoteVideoUrlExpiresAt = null,
        remoteRequestId = null,
        previousRemoteTasksJson = "",
        localVideoPath = null,
        videoMime = null,
        videoByteSize = null,
        videoSha256 = null,
        downloadedAt = null,
        automaticRetryCount = 0,
        nextRetryAt = null,
        errorStage = null,
        errorCode = null,
        errorMessage = errorMessage,
        retryDisposition = null,
        requiresCostConfirmation = requiresCostConfirmation,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun queued_showsStateAndCancelButton() {
        var cancelled = false
        rule.setContent {
            SeedanceVideoCard(video = video(state = SeedanceVideoState.QUEUED), onCancel = { cancelled = true })
        }
        rule.onNodeWithText("已排队").assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.waitForIdle()
        assertTrue("点击取消后回调未触发", cancelled)
    }

    @Test
    fun running_showsGeneratingText() {
        rule.setContent {
            SeedanceVideoCard(video = video(state = SeedanceVideoState.RUNNING))
        }
        rule.onNodeWithText("正在生成…").assertExists()
    }

    @Test
    fun failedQuery_showsContinueQuery_withoutCostDialog() {
        var retried = false
        rule.setContent {
            SeedanceVideoCard(
                video = video(state = SeedanceVideoState.FAILED_QUERY, errorMessage = "查询失败"),
                onRetry = { retried = true },
            )
        }
        rule.onNodeWithText("查询失败").assertExists()
        rule.onNodeWithText("继续查询").performClick()
        rule.waitForIdle()
        assertTrue("继续查询未直接触发重试", retried)
        rule.onNodeWithText("该操作可能产生费用，确认重新生成？").assertDoesNotExist()
    }

    @Test
    fun expired_retryShowsCostConfirmationDialog_beforeRetrying() {
        var retried = false
        rule.setContent {
            SeedanceVideoCard(
                video = video(state = SeedanceVideoState.EXPIRED),
                onRetry = { retried = true },
            )
        }
        rule.onNodeWithText("重新生成").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("该操作可能产生费用，确认重新生成？").assertExists()
        assertFalse("确认前不应触发重试", retried)
        rule.onNodeWithText("确认").performClick()
        rule.waitForIdle()
        assertTrue("确认后重试未触发", retried)
    }

    @Test
    fun failedSubmission_ambiguous_showsCostDialog() {
        var retried = false
        rule.setContent {
            SeedanceVideoCard(
                video = video(
                    state = SeedanceVideoState.FAILED_SUBMISSION,
                    requiresCostConfirmation = true,
                    errorMessage = "提交中断，无法确认任务状态",
                ),
                onRetry = { retried = true },
            )
        }
        rule.onNodeWithText("重新提交").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("该操作可能产生费用，确认重新生成？").assertExists()
        rule.onNodeWithText("确认").performClick()
        rule.waitForIdle()
        assertTrue(retried)
    }

    @Test
    fun failedSubmission_clearError_retriesWithoutDialog() {
        var retried = false
        rule.setContent {
            SeedanceVideoCard(
                video = video(
                    state = SeedanceVideoState.FAILED_SUBMISSION,
                    requiresCostConfirmation = false,
                    errorMessage = "参数校验失败",
                ),
                onRetry = { retried = true },
            )
        }
        rule.onNodeWithText("重新提交").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("该操作可能产生费用，确认重新生成？").assertDoesNotExist()
        assertTrue(retried)
    }

    @Test
    fun ready_hidesPlayAndExport_whenCallbacksNull() {
        rule.setContent {
            SeedanceVideoCard(video = video(state = SeedanceVideoState.READY, errorMessage = null))
        }
        rule.onNodeWithText("播放").assertDoesNotExist()
        rule.onNodeWithText("全屏").assertDoesNotExist()
        rule.onNodeWithText("保存到本地").assertDoesNotExist()
    }

    @Test
    fun ready_showsPlayAndExport_whenCallbacksProvided() {
        var played = false
        var exported = false
        rule.setContent {
            SeedanceVideoCard(
                video = video(state = SeedanceVideoState.READY),
                onPlay = { played = true },
                onExport = { exported = true },
            )
        }
        rule.onNodeWithText("播放").assertExists()
        rule.onNodeWithText("全屏").assertExists()
        rule.onNodeWithText("保存到本地").assertExists()
        rule.onNodeWithText("播放").performClick()
        rule.onNodeWithText("保存到本地").performClick()
        rule.waitForIdle()
        assertTrue(played)
        assertTrue(exported)
    }

    @Test
    fun cancelled_showsCancelledText() {
        rule.setContent {
            SeedanceVideoCard(video = video(state = SeedanceVideoState.CANCELLED))
        }
        rule.onNodeWithText("已取消").assertExists()
    }

    @Test
    fun preparingPrompt_showsIdeationText() {
        rule.setContent {
            SeedanceVideoCard(video = video(state = SeedanceVideoState.PROMPT_PENDING))
        }
        rule.onNodeWithText("正在构思视频…").assertExists()
    }
}
