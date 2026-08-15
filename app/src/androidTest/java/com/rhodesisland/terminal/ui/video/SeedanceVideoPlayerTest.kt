package com.rhodesisland.terminal.ui.video

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Seedance 播放控制器 / 内联播放器 instrumentation 测试（Task 8，仅 CI/真机执行）。
 *
 * 覆盖：
 * - 一个聊天屏只存在一个 ExoPlayer（每个控制器独占实例，内联/全屏共用）；
 * - 内联播放 / 暂停（playWhenReady 翻转）；
 * - 全屏切换让出内联表面（同一时刻至多一个 PlayerView 表面）；
 * - 后台暂停（生命周期 ON_STOP）；
 * - 释放：release 后播放器进入 IDLE、焦点/BGM 释放。
 *
 * 说明：ExoPlayer 所有操作必须在主线程，控制器相关断言包在 [rule.runOnUiThread] 内。
 */
@RunWith(AndroidJUnit4::class)
class SeedanceVideoPlayerTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    // ===== 控制器契约（一个播放器 / 播放暂停 / 后台暂停 / 释放） =====

    @Test
    fun singleController_ownsSingleExoPlayer() = rule.runOnUiThread {
        val a = SeedancePlaybackController(context, lifecycle = null)
        val b = SeedancePlaybackController(context, lifecycle = null)
        try {
            assertSame("同一控制器必须复用同一 ExoPlayer", a.player, a.player)
            assertNotSame("不同控制器不得共享播放器（每屏一个）", a.player, b.player)
        } finally {
            a.release()
            b.release()
        }
    }

    @Test
    fun playPause_togglesPlayWhenReady() = rule.runOnUiThread {
        val controller = SeedancePlaybackController(context, lifecycle = null)
        try {
            controller.play(tempVideo("toggle.mp4"))
            assertTrue("play() 后应 playWhenReady", controller.player.playWhenReady)
            controller.pause()
            assertFalse("pause() 后应 !playWhenReady", controller.player.playWhenReady)
        } finally {
            controller.release()
        }
    }

    @Test
    fun toggle_activeAndPaused_resumes_activeAndPlaying_pauses() = rule.runOnUiThread {
        val controller = SeedancePlaybackController(context, lifecycle = null)
        try {
            val f = tempVideo("toggle2.mp4")
            controller.toggle(f)
            assertTrue(controller.player.playWhenReady)
            controller.toggle(f)
            assertFalse("再次 toggle 应暂停", controller.player.playWhenReady)
            controller.toggle(f)
            assertTrue("暂停后 toggle 应恢复播放", controller.player.playWhenReady)
        } finally {
            controller.release()
        }
    }

    @Test
    fun fullScreen_flag_toggles() = rule.runOnUiThread {
        val controller = SeedancePlaybackController(context, lifecycle = null)
        try {
            assertFalse(controller.fullScreen.value)
            controller.setFullScreen(true)
            assertTrue(controller.fullScreen.value)
            controller.setFullScreen(false)
            assertFalse(controller.fullScreen.value)
        } finally {
            controller.release()
        }
    }

    @Test
    fun backgroundPause_onLifecycleStop() {
        val owner = TestLifecycleOwner()
        val controller = rule.runOnUiThread {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            val c = SeedancePlaybackController(context, lifecycle = owner.registry)
            c.play(tempVideo("bg.mp4"))
            assertTrue(c.player.playWhenReady)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertFalse("退到后台应暂停播放", c.player.playWhenReady)
            c
        }
        rule.runOnUiThread { controller.release() }
    }

    @Test
    fun release_releasesPlayerAndState() = rule.runOnUiThread {
        val controller = SeedancePlaybackController(context, lifecycle = null)
        controller.play(tempVideo("dispose.mp4"))
        controller.release()
        assertFalse(controller.player.playWhenReady)
        assertTrue("release 后播放器应回到 IDLE", controller.player.playbackState == Player.STATE_IDLE)
    }

    // ===== 内联/全屏表面：同一时刻至多一个 PlayerView =====

    @Test
    fun playerSurface_onlyRendersWithNonNullPlayer() {
        val controller = rule.runOnUiThread { SeedancePlaybackController(context, lifecycle = null) }
        try {
            rule.setContent {
                Column {
                    SeedanceVideoPlayer(
                        player = controller.player,
                        modifier = Modifier,
                        testTag = SEEDANCE_INLINE_PLAYER_TAG,
                    )
                    SeedanceVideoPlayer(
                        player = null,
                        modifier = Modifier,
                        testTag = SEEDANCE_INLINE_PLAYER_TAG,
                    )
                }
            }
            rule.onAllNodesWithTag(SEEDANCE_INLINE_PLAYER_TAG).assertCountEquals(1)
        } finally {
            rule.runOnUiThread { controller.release() }
        }
    }

    @Test
    fun multipleCards_exactlyOneInlineSurface_fullScreenDetaches() {
        val f1 = tempVideo("one.mp4")
        val f2 = tempVideo("two.mp4")
        val controller = rule.runOnUiThread { SeedancePlaybackController(context, lifecycle = null) }
        try {
            rule.setContent {
                CompositionLocalProvider(LocalSeedancePlaybackController provides controller) {
                    Column {
                        SeedanceVideoCard(
                            video = video(1L, f1.absolutePath),
                            onPlay = { controller.play(f1) },
                            onFullScreen = { controller.play(f1); controller.setFullScreen(true) },
                        )
                        SeedanceVideoCard(
                            video = video(2L, f2.absolutePath),
                            onPlay = { controller.play(f2) },
                            onFullScreen = { controller.play(f2); controller.setFullScreen(true) },
                        )
                    }
                }
            }
            // 初始：无活动内联表面
            rule.onAllNodesWithTag(SEEDANCE_INLINE_PLAYER_TAG).assertCountEquals(0)
            // 播放卡 1 → 仅卡 1 出现内联表面
            rule.onAllNodesWithText("播放")[0].performClick()
            rule.waitForIdle()
            rule.onAllNodesWithTag(SEEDANCE_INLINE_PLAYER_TAG).assertCountEquals(1)
            // 播放卡 2 → 表面交接，仍只有一个
            rule.onAllNodesWithText("播放")[1].performClick()
            rule.waitForIdle()
            rule.onAllNodesWithTag(SEEDANCE_INLINE_PLAYER_TAG).assertCountEquals(1)
            // 卡 2 全屏 → 内联表面让出（全屏表面由 ChatScreen 单独挂载）
            rule.onAllNodesWithText("全屏")[1].performClick()
            rule.waitForIdle()
            rule.onAllNodesWithTag(SEEDANCE_INLINE_PLAYER_TAG).assertCountEquals(0)
            assertTrue(controller.fullScreen.value)
        } finally {
            rule.runOnUiThread { controller.release() }
        }
    }

    // ===== 辅助 =====

    private fun tempVideo(name: String): File {
        val f = File(context.cacheDir, name)
        f.writeBytes(byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
        return f
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun video(
        id: Long,
        localPath: String,
        state: SeedanceVideoState = SeedanceVideoState.READY,
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
        localVideoPath = localPath,
        videoMime = "video/mp4",
        videoByteSize = 8L,
        videoSha256 = null,
        downloadedAt = null,
        automaticRetryCount = 0,
        nextRetryAt = null,
        errorStage = null,
        errorCode = null,
        errorMessage = null,
        retryDisposition = null,
        requiresCostConfirmation = false,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
