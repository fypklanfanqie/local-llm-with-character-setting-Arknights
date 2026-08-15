package com.rhodesisland.terminal.ui.video

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 「邂逅」沉浸式历史流 instrumentation 测试（Task 9，仅 CI/真机执行）。
 *
 * 覆盖计划验收：
 * 1. 通讯 feed 的「邂逅」入口 + 全屏竖滑历史流（createdAt DESC 最新在前）；
 * 2. 仅落定页 READY 挂载播放器，前一页表面让出（同一时刻至多一个 PlayerView）；
 * 3. 切页后上一页暂停、新页接管（activePath / playWhenReady 断言）；
 * 4. 聊天被删除后，任务快照（角色名 / 用户 / 助手原文 / 提示词）仍可见；
 * 5. 详情弹层展示最终提示词、模型/分辨率/画幅/时长/水印、错误阶段/码/信息；
 * 6. 失败任务动作（继续查询 / 取消 / 保存到本地 / 详情）；
 * 7. 空状态；8. 无障碍（参考图 contentDescription）。
 *
 * 说明：feed 入口与仓库 DESC 排序分别在 [com.rhodesisland.terminal.ui.feed.CharacterFeedScreen]
 * 与 [com.rhodesisland.terminal.data.local.SeedanceVideoDaoTest.observeAll_ordersByCreatedAtDescending]
 * 覆盖；本测试聚焦页面与播放门控。
 */
@RunWith(AndroidJUnit4::class)
class EncounterScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    // ===== 页面级门控：仅「落定 + READY + 非空 player」挂载播放器表面 =====

    @Test
    fun settledReady_withPlayer_mountsPlayerSurface() {
        val player = rule.runOnUiThread { androidx.media3.exoplayer.ExoPlayer.Builder(context).build() }
        try {
            rule.setContent {
                EncounterVideoPage(
                    video = video(1L, state = SeedanceVideoState.READY, localPath = tempVideo("s1.mp4").absolutePath),
                    settled = true,
                    player = player,
                    onOpenDetails = {},
                )
            }
            rule.onNodeWithTag(SEEDANCE_ENCOUNTER_PLAYER_TAG).assertExists()
        } finally {
            rule.runOnUiThread { player.release() }
        }
    }

    @Test
    fun settledReady_withoutPlayer_showsBackdropOnly() {
        rule.setContent {
            EncounterVideoPage(
                video = video(1L, state = SeedanceVideoState.READY),
                settled = true,
                player = null,
                onOpenDetails = {},
            )
        }
        rule.onNodeWithTag(SEEDANCE_ENCOUNTER_PLAYER_TAG).assertDoesNotExist()
    }

    @Test
    fun nonSettled_neverMountsPlayerSurface() {
        val player = rule.runOnUiThread { androidx.media3.exoplayer.ExoPlayer.Builder(context).build() }
        try {
            rule.setContent {
                EncounterVideoPage(
                    video = video(1L, state = SeedanceVideoState.READY, localPath = tempVideo("s2.mp4").absolutePath),
                    settled = false,
                    player = player,
                    onOpenDetails = {},
                )
            }
            rule.onNodeWithTag(SEEDANCE_ENCOUNTER_PLAYER_TAG).assertDoesNotExist()
        } finally {
            rule.runOnUiThread { player.release() }
        }
    }

    @Test
    fun settledNonReady_neverMountsPlayerSurface() {
        val player = rule.runOnUiThread { androidx.media3.exoplayer.ExoPlayer.Builder(context).build() }
        try {
            rule.setContent {
                EncounterVideoPage(
                    video = video(1L, state = SeedanceVideoState.RUNNING),
                    settled = true,
                    player = player,
                    onOpenDetails = {},
                )
            }
            rule.onNodeWithTag(SEEDANCE_ENCOUNTER_PLAYER_TAG).assertDoesNotExist()
        } finally {
            rule.runOnUiThread { player.release() }
        }
    }

    // ===== 快照展示：聊天被删除后快照仍可见 + 提示词摘要 =====

    @Test
    fun deletedChatSnapshot_stillVisible_alongWithPromptSummary() {
        val v = video(
            id = 7L,
            state = SeedanceVideoState.READY,
            characterName = "阿米娅",
            userText = "今天我们去看流星雨吧",
            assistantText = "好呀，罗德岛的夜空最合适了。",
            finalPrompt = "流星雨下的阿米娅，远景，9:16",
        )
        rule.setContent {
            EncounterVideoPage(video = v, settled = true, player = null, onOpenDetails = {})
        }
        rule.onNodeWithText("阿米娅").assertExists()
        rule.onNodeWithText("“今天我们去看流星雨吧”").assertExists()
        rule.onNodeWithText("好呀，罗德岛的夜空最合适了。").assertExists()
        rule.onNodeWithText("提示词：流星雨下的阿米娅，远景，9:16").assertExists()
    }

    // ===== 页面动作：失败/排队/就绪 =====

    @Test
    fun failedQuery_showsContinueQuery_andInvokes() {
        var retried = false
        rule.setContent {
            EncounterVideoPage(
                video = video(2L, state = SeedanceVideoState.FAILED_QUERY, errorMessage = "查询失败"),
                settled = true,
                player = null,
                onOpenDetails = {},
                onRetry = { retried = true },
            )
        }
        rule.onNodeWithText("查询失败").assertExists()
        rule.onNodeWithText("继续查询").performClick()
        rule.waitForIdle()
        assertTrue("继续查询回调未触发", retried)
    }

    @Test
    fun queued_showsCancel_andInvokes() {
        var cancelled = false
        rule.setContent {
            EncounterVideoPage(
                video = video(3L, state = SeedanceVideoState.QUEUED),
                settled = true,
                player = null,
                onOpenDetails = {},
                onCancel = { cancelled = true },
            )
        }
        rule.onNodeWithText("已排队").assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.waitForIdle()
        assertTrue("取消回调未触发", cancelled)
    }

    @Test
    fun ready_showsSaveToLocal_andInvokes() {
        var exported = false
        rule.setContent {
            EncounterVideoPage(
                video = video(4L, state = SeedanceVideoState.READY, localPath = tempVideo("s4.mp4").absolutePath),
                settled = true,
                player = null,
                onOpenDetails = {},
                onExport = { exported = true },
            )
        }
        rule.onNodeWithText("保存到本地").performClick()
        rule.waitForIdle()
        assertTrue("保存到本地回调未触发", exported)
    }

    @Test
    fun detailsButton_invokesCallback() {
        var opened = false
        rule.setContent {
            EncounterVideoPage(
                video = video(5L, state = SeedanceVideoState.RUNNING),
                settled = true,
                player = null,
                onOpenDetails = { opened = true },
            )
        }
        rule.onNodeWithText("详情").performClick()
        rule.waitForIdle()
        assertTrue("详情回调未触发", opened)
    }

    // ===== 无障碍：参考图 contentDescription =====

    @Test
    fun referenceImage_hasContentDescription() {
        val img = tempFile("ref.png")
        val v = video(
            6L,
            state = SeedanceVideoState.RUNNING,
            characterName = "陈",
            characterImagePath = img.absolutePath,
        )
        rule.setContent {
            EncounterVideoPage(video = v, settled = true, player = null, onOpenDetails = {})
        }
        rule.onNodeWithContentDescription("陈 参考图").assertExists()
    }

    // ===== 分页器：最新在前 + 仅落定页播放 + 切页让出上一页 =====

    @Test
    fun pager_newestFirst_autoPlaysSettledReadyVideo_singleSurface() {
        val newest = video(
            10L,
            state = SeedanceVideoState.READY,
            localPath = tempVideo("p_newest.mp4").absolutePath,
            characterName = "最新任务",
            createdAt = 300,
        )
        val older = video(
            11L,
            state = SeedanceVideoState.READY,
            localPath = tempVideo("p_older.mp4").absolutePath,
            characterName = "更早任务",
            createdAt = 100,
        )
        val controller = rule.runOnUiThread { SeedancePlaybackController(context, lifecycle = null) }
        try {
            // createdAt DESC（仓库已排序）-> 第一页 = 最新任务
            rule.setContent {
                EncounterVideoPager(
                    videos = listOf(newest, older),
                    playbackController = controller,
                    onOpenDetails = {},
                    onExport = {},
                    onCancel = {},
                    onRetry = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
            rule.waitForIdle()
            // 第一页（最新）自动播放
            assertEquals("应自动播放最新视频", newest.localVideoPath, controller.activePath.value)
            assertTrue("最新视频应处于播放状态", controller.player.playWhenReady)
            rule.onNodeWithText("最新任务").assertExists()
            // 同一时刻至多一个 PlayerView 表面（仅落定页挂载）
            rule.onAllNodesWithTag(SEEDANCE_ENCOUNTER_PLAYER_TAG).assertCountEquals(1)
        } finally {
            rule.runOnUiThread { controller.release() }
        }
    }

    @Test
    fun pagerTransition_previousPagePaused_newPagePlays_onlyOneSurface() {
        val first = video(
            20L,
            state = SeedanceVideoState.READY,
            localPath = tempVideo("q_first.mp4").absolutePath,
            characterName = "第一页",
            createdAt = 200,
        )
        val second = video(
            21L,
            state = SeedanceVideoState.READY,
            localPath = tempVideo("q_second.mp4").absolutePath,
            characterName = "第二页",
            createdAt = 100,
        )
        val controller = rule.runOnUiThread { SeedancePlaybackController(context, lifecycle = null) }
        try {
            var settledIndex by mutableStateOf(0)
            rule.setContent {
                val videos = listOf(first, second)
                // 复现 EncounterVideoPager 的落定播放门控（生产函数，非测试复制）
                LaunchedEffect(settledIndex) {
                    settleEncounterPlayback(videos.getOrNull(settledIndex), controller)
                }
                Column(Modifier.fillMaxSize()) {
                    EncounterVideoPage(
                        video = first,
                        settled = settledIndex == 0,
                        player = if (settledIndex == 0) controller.player else null,
                        onOpenDetails = {},
                        modifier = Modifier.weight(1f),
                    )
                    EncounterVideoPage(
                        video = second,
                        settled = settledIndex == 1,
                        player = if (settledIndex == 1) controller.player else null,
                        onOpenDetails = {},
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            rule.waitForIdle()
            // 落定页 0：仅页 0 挂表面，播放 first
            assertEquals("初始应播放第一页", first.localVideoPath, controller.activePath.value)
            rule.onAllNodesWithTag(SEEDANCE_ENCOUNTER_PLAYER_TAG).assertCountEquals(1)

            // 落定到页 1：新页接管播放，仅页 1 挂表面，上一页表面让出
            rule.runOnUiThread { settledIndex = 1 }
            rule.waitForIdle()
            assertEquals("切页后应播放第二页", second.localVideoPath, controller.activePath.value)
            assertTrue("第二页应处于播放状态", controller.player.playWhenReady)
            rule.onNodeWithText("第二页").assertExists()
            rule.onAllNodesWithTag(SEEDANCE_ENCOUNTER_PLAYER_TAG).assertCountEquals(1)
        } finally {
            rule.runOnUiThread { controller.release() }
        }
    }

    @Test
    fun pagerTransition_settledOnNonReady_pausesAndDropsSurface() {
        val ready = video(
            30L,
            state = SeedanceVideoState.READY,
            localPath = tempVideo("r_ready.mp4").absolutePath,
            characterName = "已生成",
            createdAt = 200,
        )
        val failed = video(
            31L,
            state = SeedanceVideoState.FAILED_QUERY,
            characterName = "查询失败页",
            errorMessage = "查询失败",
            createdAt = 100,
        )
        val controller = rule.runOnUiThread { SeedancePlaybackController(context, lifecycle = null) }
        try {
            var settledIndex by mutableStateOf(0)
            rule.setContent {
                val videos = listOf(ready, failed)
                // 复现 EncounterVideoPager 的落定播放门控（生产函数）
                LaunchedEffect(settledIndex) {
                    settleEncounterPlayback(videos.getOrNull(settledIndex), controller)
                }
                Column(Modifier.fillMaxSize()) {
                    EncounterVideoPage(
                        video = ready,
                        settled = settledIndex == 0,
                        player = if (settledIndex == 0) controller.player else null,
                        onOpenDetails = {},
                        modifier = Modifier.weight(1f),
                    )
                    EncounterVideoPage(
                        video = failed,
                        settled = settledIndex == 1,
                        player = if (settledIndex == 1) controller.player else null,
                        onOpenDetails = {},
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            rule.waitForIdle()
            assertEquals("初始应播放 READY 页", ready.localVideoPath, controller.activePath.value)

            // 落定到失败页：无播放器表面 + 暂停
            rule.runOnUiThread { settledIndex = 1 }
            rule.waitForIdle()
            rule.onAllNodesWithTag(SEEDANCE_ENCOUNTER_PLAYER_TAG).assertCountEquals(0)
            assertFalse("落定到非 READY 页应暂停", controller.isPlaying.value)
            rule.onNodeWithText("查询失败").assertExists()
        } finally {
            rule.runOnUiThread { controller.release() }
        }
    }

    // ===== 空状态 =====

    @Test
    fun emptyState_showsWhenNoVideos() {
        rule.setContent { EncounterEmptyState(Modifier.fillMaxSize()) }
        rule.onNodeWithText("还没有视频故事").assertExists()
        rule.onNodeWithText("开启角色会话的自动视频后，生成的视频会出现在这里").assertExists()
    }

    // ===== 详情弹层：提示词 / 参数 / 错误 / 动作 =====

    @Test
    fun detailsDialog_showsPromptParamsAndErrors() {
        val v = video(
            40L,
            state = SeedanceVideoState.FAILED_REMOTE,
            characterName = "阿米娅",
            userText = "用户原文",
            assistantText = "助手原文",
            finalPrompt = "最终提示词内容",
            errorStage = "generation",
            errorCode = "1001",
            errorMessage = "远端生成失败",
            requiresCostConfirmation = true,
        )
        rule.setContent {
            EncounterDetailsDialog(
                video = v,
                onExport = null,
                onCancel = null,
                onRetry = {},
                onContinueQuery = {},
                onRetryDownload = {},
                onDismiss = {},
            )
        }
        rule.onNodeWithText("用户原文").assertExists()
        rule.onNodeWithText("助手原文").assertExists()
        rule.onNodeWithText("最终提示词内容").assertExists()
        rule.onNodeWithText(SeedanceModelVariant.STANDARD.modelId).assertExists()
        rule.onNodeWithText(SeedanceResolution.P720.storageKey).assertExists()
        rule.onNodeWithText(SeedanceRatio.PORTRAIT.storageKey).assertExists()
        rule.onNodeWithText("5 秒").assertExists()
        // 关闭按钮是 Icon（contentDescription 语义键），并非 Text 节点。
        rule.onNodeWithContentDescription("关闭").assertExists()
        rule.onNodeWithText("generation").assertExists()
        rule.onNodeWithText("1001").assertExists()
        rule.onNodeWithText("远端生成失败").assertExists()
        // 费用性重试：先弹确认
        rule.onNodeWithText("重新生成").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("该操作可能产生费用，确认重新生成？").assertExists()
    }

    @Test
    fun detailsDialog_failedQuery_continueQueryInvokes() {
        var continued = false
        val v = video(41L, state = SeedanceVideoState.FAILED_QUERY, errorMessage = "查询失败")
        rule.setContent {
            EncounterDetailsDialog(
                video = v,
                onExport = null,
                onCancel = null,
                onRetry = {},
                onContinueQuery = { continued = true },
                onRetryDownload = {},
                onDismiss = {},
            )
        }
        rule.onNodeWithText("继续查询").performClick()
        rule.waitForIdle()
        assertTrue("详情弹层继续查询未触发", continued)
    }

    @Test
    fun detailsDialog_queued_showsCancel() {
        val v = video(42L, state = SeedanceVideoState.QUEUED)
        rule.setContent {
            EncounterDetailsDialog(
                video = v,
                onExport = null,
                onCancel = {},
                onRetry = {},
                onContinueQuery = {},
                onRetryDownload = {},
                onDismiss = {},
            )
        }
        rule.onNodeWithText("取消").assertExists()
    }

    // ===== 辅助 =====

    private fun tempVideo(name: String): File = tempFile(name)

    private fun tempFile(name: String): File {
        val f = File(context.cacheDir, name)
        f.writeBytes(byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
        return f
    }

    private fun video(
        id: Long,
        state: SeedanceVideoState = SeedanceVideoState.QUEUED,
        localPath: String? = null,
        characterName: String = "阿米娅",
        characterImagePath: String? = null,
        userText: String = "你好",
        assistantText: String = "回答",
        finalPrompt: String? = null,
        errorStage: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
        requiresCostConfirmation: Boolean = false,
        createdAt: Long = 1L,
    ) = SeedanceVideo(
        id = id,
        taskUuid = "uuid-$id",
        triggerType = "auto",
        sourceConversationId = 1L,
        sourceUserMessageId = 100L,
        sourceAssistantMessageId = 200L,
        characterIdSnapshot = "char-1",
        characterNameSnapshot = characterName,
        characterRoleSnapshot = "罗德岛领袖",
        characterSystemPromptSnapshot = "你是阿米娅。",
        userTextSnapshot = userText,
        assistantTextSnapshot = assistantText,
        sceneDescriptionSnapshot = "",
        promptBaseUrlSnapshot = "https://api.example.com/v1",
        promptModelSnapshot = "doubao-text-pro",
        promptJson = null,
        finalPrompt = finalPrompt,
        characterImageSourceSnapshot = "asset://amiya.png",
        backgroundImageSourceSnapshot = null,
        characterImagePath = characterImagePath,
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
        localVideoPath = localPath,
        videoMime = if (localPath != null) "video/mp4" else null,
        videoByteSize = null,
        videoSha256 = null,
        downloadedAt = null,
        automaticRetryCount = 0,
        nextRetryAt = null,
        errorStage = errorStage,
        errorCode = errorCode,
        errorMessage = errorMessage,
        retryDisposition = null,
        requiresCostConfirmation = requiresCostConfirmation,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
