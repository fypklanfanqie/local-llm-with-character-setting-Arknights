package com.rhodesisland.terminal.manager

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * MediaPlayer 路径的音频焦点小封装。
 *
 * ExoPlayer BGM 用 Media3 内置 `setAudioAttributes(handleAudioFocus=true)` 自动管理；
 * 本应用内两处裸 [android.media.MediaPlayer]（[AudioManager.playVoice] 角色语音、
 * [TtsManager.playAudio] 云端 TTS）没有内置焦点管理——其他 App 放歌时会叠音。此封装在
 * 播放前请求瞬时焦点（允许 duck），播放结束/出错时归还，避免与系统其他音频源互抢声道。
 *
 * - API 26+：[AudioFocusRequest]（新 API，可指定 willPauseWhenDucked）；
 * - API <26：弃用的 `requestAudioFocus` 三参重载（minSdk 24 仍需支持）。
 * 全部 runCatching：个别 ROM 的 AudioService 异常不应阻断播放本身。
 */
class AudioFocusHelper(context: Context) {

    companion object {
        private const val TAG = "AudioFocusHelper"

        /** 焦点变化：仅日志（语音/TTS 是短促一次性播放，被抢焦点时直接停由调用方 onCompletion 处理）。 */
        fun buildChangeListener(onLoss: () -> Unit = {}): AudioManager.OnAudioFocusChangeListener =
            AudioManager.OnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> onLoss()
                }
            }
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var focusRequest: AudioFocusRequest? = null

    /** 播放前申请焦点；返回是否成功（失败不阻断播放，只记日志）。 */
    @Suppress("DEPRECATION")
    fun request(): Boolean {
        val am = audioManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .build()
                focusRequest = req
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus failed: ${e.message}")
            false
        }
    }

    /** 播放结束/出错后归还焦点。幂等。 */
    @Suppress("DEPRECATION")
    fun abandon() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "abandonAudioFocus failed: ${e.message}")
        }
    }
}
