package com.rhodesisland.terminal.ui.video

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 屏幕级播放控制器 CompositionLocal（Task 8）。
 *
 * 由 [com.rhodesisland.terminal.ui.chat.ChatScreen] 提供；[SeedanceVideoCard] 用它判定
 * 「本卡是否为活动内联视频」并驱动全屏，避免把控制器逐层透传进每条消息气泡。
 */
val LocalSeedancePlaybackController = staticCompositionLocalOf<SeedancePlaybackController?> { null }

/**
 * 全局 BGM 播放器鸭子接口（Task 8）。
 *
 * 由 [com.rhodesisland.terminal.manager.AudioManager] 适配注入：视频开始播放时暂停 BGM，
 * 停止/结束/释放时若此前正在播放则恢复。绝不复用 BGM 的 ExoPlayer 实例。
 */
interface BgmDuck {
    fun isPlaying(): Boolean
    fun pause()
    fun resume()
}

/**
 * 屏幕级（一个聊天屏一个实例）Seedance 视频播放控制器（Task 8）。
 *
 * 拥有整个聊天屏唯一的 [ExoPlayer]：卡片内联与全屏预览共用同一播放器，任意时刻只允许
 * 一个 [PlayerView] 表面挂载（见 [SeedanceVideoPlayer]）。只播放本地内部归档文件
 * （[com.rhodesisland.terminal.data.model.SeedanceVideo.localVideoPath]），绝不播放远端 URL。
 *
 * 音频策略：开始播放时申请音频焦点并暂停全局 BGM 与应用内 TTS；暂停/自然结束/释放时归还
 * 焦点并恢复 BGM/TTS。生命周期：[lifecycle] 进入 ON_PAUSE/ON_STOP 时自动暂停（退后台静音）；
 * [release] 释放播放器与焦点并恢复 BGM/TTS（屏幕销毁时调用）。
 */
class SeedancePlaybackController(
    private val context: Context,
    private val bgm: BgmDuck? = null,
    private val onAcquireAudio: () -> Unit = {},
    private val onReleaseAudio: () -> Unit = {},
    lifecycle: Lifecycle? = null,
) {
    private val systemAudio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 聊天屏唯一 ExoPlayer（本地文件播放）。 */
    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** 当前已加载的本地视频绝对路径（未加载为 null）。 */
    private val _activePath = MutableStateFlow<String?>(null)
    val activePath: StateFlow<String?> = _activePath.asStateFlow()

    /** 全屏预览是否开启（开启时内联卡片表面让出，仅全屏表面挂载播放器）。 */
    private val _fullScreen = MutableStateFlow(false)
    val fullScreen: StateFlow<Boolean> = _fullScreen.asStateFlow()

    private var bgmWasPlaying = false

    /** release() 后置位：页面销毁与异步回调（焦点监听/轮询/settle effect）竞态时，
     *  所有触碰 player 的入口直接短路，杜绝 IllegalStateException。 */
    @Volatile
    private var released = false

    /** API 26+ 的音频焦点请求句柄（acquire 时创建，release 时归还）。 */
    private var focusRequest: android.media.AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseInternal()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.volume = 0.3f
            AudioManager.AUDIOFOCUS_GAIN -> player.volume = 1f
        }
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP -> pause()
            else -> {}
        }
    }

    private var attachedLifecycle: Lifecycle? = lifecycle

    init {
        attachedLifecycle?.addObserver(lifecycleObserver)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                // 视频自然播完：归还音频焦点并恢复 BGM/TTS
                if (state == Player.STATE_ENDED) releaseAudio()
            }

            override fun onPlayerError(error: PlaybackException) {
                // 加载/解码错误会把播放器打进 STATE_IDLE，STOP_ENDED 永不触发：
                // 归还焦点并恢复 BGM/TTS、复位状态，保证下一次 play() 从干净状态重新准备。
                _activePath.value = null
                _isPlaying.value = false
                releaseAudio()
            }
        })
    }

    /** 加载并播放本地文件；同一文件重复调用视为继续/恢复播放（自然播完后重播回到片头）。
     *  文件不存在/不可读（存储清理后 READY 任务的残留路径）直接忽略——不抛异常、
     *  不把播放器打进错误态，UI 保持原状态。 */
    fun play(file: File) {
        if (released) return
        if (!file.isFile || !file.canRead()) return
        val path = file.absolutePath
        try {
            if (_activePath.value != path) {
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                _activePath.value = path
            } else if (player.playbackState == Player.STATE_ENDED) {
                // 自然播完后再次点击：seekTo 回片头重播，而不是停留在 STATE_ENDED 上 setPlayWhenReady。
                player.seekTo(0)
            }
            acquireAudio()
            player.play()
        } catch (_: Exception) {
            // setMediaItem/prepare 同步抛错（罕见 ROM/损坏文件）：复位活动路径，
            // 下一次 play() 走完整加载；焦点在 acquireAudio 前未申请，无需归还。
            _activePath.value = null
        }
    }

    /** 内联播放开关：当前文件正在播放则暂停（已自然播完视为非播放态，触发重播），否则加载/恢复播放。 */
    fun toggle(file: File) {
        if (released) return
        if (_activePath.value == file.absolutePath &&
            player.playWhenReady &&
            player.playbackState != Player.STATE_ENDED
        ) {
            pause()
        } else {
            play(file)
        }
    }

    fun pause() {
        if (released) return
        pauseInternal()
    }

    private fun pauseInternal() {
        runCatching { player.pause() }
        releaseAudio()
    }

    /** 全屏开关。开启时内联卡片表面让出，仅全屏表面挂载播放器。 */
    fun setFullScreen(enabled: Boolean) {
        _fullScreen.value = enabled
    }

    /** 释放播放器、归还音频焦点并恢复 BGM（屏幕销毁/离开时调用）。幂等。 */
    fun release() {
        if (released) return
        released = true
        attachedLifecycle?.removeObserver(lifecycleObserver)
        attachedLifecycle = null
        runCatching { player.release() }
        _isPlaying.value = false
        releaseAudio()
    }

    private fun acquireAudio() {
        if (bgm != null && bgm.isPlaying() && !bgmWasPlaying) {
            bgmWasPlaying = true
            bgm.pause()
        }
        onAcquireAudio()
        // API 26+ 用 AudioFocusRequest（三参 requestAudioFocus 已弃用）；<26 仍走弃用重载。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val req = android.media.AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN,
            )
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .build()
            focusRequest = req
            systemAudio.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            systemAudio.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    private fun releaseAudio() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            focusRequest?.let { systemAudio.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            systemAudio.abandonAudioFocus(focusListener)
        }
        if (bgmWasPlaying) {
            bgmWasPlaying = false
            bgm?.resume()
        }
        onReleaseAudio()
    }
}
