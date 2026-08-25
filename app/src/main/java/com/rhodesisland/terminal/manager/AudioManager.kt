package com.rhodesisland.terminal.manager

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rhodesisland.terminal.data.repository.AssetRepository
import com.rhodesisland.terminal.data.repository.BgmTrack
import com.rhodesisland.terminal.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * 音频管理器
 *
 * 对应小程序 utils/audio.js：
 * - 角色语音播放（MediaPlayer）
 * - BGM 背景音乐播放（ExoPlayer）
 */
class AudioManager(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    companion object {
        private const val TAG = "AudioManager"

        /** 顺序播放：单曲播完停止。 */
        const val REPEAT_SEQUENTIAL = 0
        /** 列表循环：单曲播完自动切下一首，末尾回到开头。 */
        const val REPEAT_ALL = 1
        /** 单曲循环：当前曲目无限循环（ExoPlayer REPEAT_MODE_ONE）。 */
        const val REPEAT_ONE = 2
    }

    // ===== 角色语音 =====
    private var voicePlayer: MediaPlayer? = null

    /** 语音播放的音频焦点助手：播放前申请瞬时焦点（可 duck），结束/出错归还，防与其他 App 叠音。 */
    private val voiceFocus = AudioFocusHelper(context)

    suspend fun playVoice(url: String, volume: Int = 60) {
        if (url.isBlank()) return

        stopVoice()
        voiceFocus.request()

        val player = MediaPlayer()
        voicePlayer = player
        try {
            // 本地 asset:/// 是 ExoPlayer 专用协议，MediaPlayer 原生不识别 ->
            // 需经 AssetFileDescriptor（或压缩资源的临时文件）喂给 MediaPlayer。
            // 外链 http(s)/CDN URL 仍直传 setDataSource(String)。
            if (url.startsWith("asset:///")) {
                val relPath = url.removePrefix("asset:///")
                withContext(Dispatchers.IO) {
                    try {
                        // 未压缩资源：openFd 零拷贝（配合 build.gradle noCompress 'wav'）
                        context.assets.openFd(relPath).use { afd ->
                            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        }
                    } catch (e: IOException) {
                        // 资源被压缩时 openFd 抛错 -> 拷贝到缓存临时文件后播放（兜底，正常不走到）
                        val temp = File(context.cacheDir, "voice_${relPath.hashCode()}.bin")
                        if (temp.length() == 0L) {
                            context.assets.open(relPath).use { input ->
                                temp.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        player.setDataSource(temp.absolutePath)
                    }
                }
            } else {
                player.setDataSource(url)
            }
            val v = (volume / 100f).coerceIn(0f, 1f)
            player.setVolume(v, v)
            player.setOnCompletionListener { mp ->
                try { mp.release() } catch (e: Exception) {}
                voicePlayer = null
                voiceFocus.abandon()
            }
            player.setOnErrorListener { mp, _, _ ->
                Log.w(TAG, "Voice play failed: $url")
                try { mp.release() } catch (e: Exception) {}
                voicePlayer = null
                voiceFocus.abandon()
                true
            }
            // 异步准备：网络 URL 时 prepare() 会阻塞主线程导致 ANR，改用 prepareAsync
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Voice play error: ${e.message}")
            try { player.release() } catch (e: Exception) {}
            voicePlayer = null
        }
    }

    fun stopVoice() {
        voiceFocus.abandon()
        voicePlayer?.let {
            // stop() 在 IDLE/ERROR 等状态会抛 IllegalStateException，单独 try 以保证 release() 一定执行
            try { it.stop() } catch (e: Exception) {}
            try { it.release() } catch (e: Exception) {}
        }
        voicePlayer = null
    }

    // ===== 背景音乐 =====
    private var bgmPlayer: ExoPlayer? = null
    private var bgmIndex = 0
    private var bgmPlaylist: List<BgmTrack> = emptyList()
    private var repeatMode = REPEAT_SEQUENTIAL  // 0=顺序播放, 1=列表循环, 2=单曲循环

    /** 随机播放开关（手动 next/prev 随机选索引实现，不依赖 ExoPlayer shuffleMode） */
    private val _isShuffle = MutableStateFlow(false)
    val isShuffleFlow: StateFlow<Boolean> = _isShuffle

    /** 最近一次错误（资源缺失/加载失败），供 UI 提示 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 真实播放状态（由 ExoPlayer 回调驱动，供 UI 订阅，避免与播放器状态脱节） */
    private val _isPlaying = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlaying

    /** 当前曲目下标（加载/自动切歌时更新，供 UI 订阅） */
    private val _currentIndex = MutableStateFlow(0)
    val currentIndexFlow: StateFlow<Int> = _currentIndex

    /** 播放器是否已初始化（用于 MusicScreen 判断首次进入/再次进入） */
    fun isPlayerInitialized(): Boolean = bgmPlayer != null

    fun initBgm(playlist: List<BgmTrack>) {
        bgmPlaylist = playlist
        if (bgmPlayer == null) {
            // 复合数据源：同时支持 http(s) 网易云外链与本地 asset:/// 曲目
            val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                .setAllowCrossProtocolRedirects(true)
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
                context,
                httpDataSourceFactory,  // 网络数据源（网易云）
            )
            // 本地 asset:/// 由 DefaultDataSource 内部自动处理
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            bgmPlayer = ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
            // 音频焦点交给 Media3 内置管理：其他 App（音乐/视频/导航）请求焦点时自动 duck/暂停，
            // 焦点归还后恢复；handleAudioBecomingNoisy：拔耳机/断蓝牙时自动暂停（外放突兀）。
            bgmPlayer?.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            bgmPlayer?.setHandleAudioBecomingNoisy(true)
            // 列表循环用 REPEAT_MODE_OFF（播完触发 STATE_ENDED 后手动切下一首）；
            // 单曲循环用 REPEAT_MODE_ONE（ExoPlayer 自身循环，不会进入 STATE_ENDED）
            bgmPlayer?.repeatMode = if (repeatMode == REPEAT_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            bgmPlayer?.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.w(TAG, "BGM play error: ${error.message}")
                    _error.value = "播放失败，请检查音频资源或网络"
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                }

                override fun onPlaybackStateChanged(state: Int) {
                    // 列表循环模式下，当前曲目自然播完则自动切到下一首（shuffle 时 nextTrack 随机选）
                    if (state == Player.STATE_ENDED && repeatMode == REPEAT_ALL && bgmPlaylist.isNotEmpty()) {
                        nextTrack(bgmPlaylist)?.let { bgmPlayer?.play() }
                    }
                }
            })
        }
    }

    fun loadTrack(index: Int, playlist: List<BgmTrack>): BgmTrack? {
        bgmPlaylist = playlist
        if (playlist.isEmpty()) {
            _error.value = "播放列表为空"
            return null
        }
        val actualIndex = ((index % playlist.size) + playlist.size) % playlist.size
        bgmIndex = actualIndex
        _currentIndex.value = actualIndex
        val track = playlist[actualIndex]
        if (track.file.isBlank()) {
            _error.value = "音频资源缺失：${track.name} 暂无可用音频源，请在音乐页导入本地文件或检查网络"
            return null
        }

        try {
            bgmPlayer?.let { player ->
                player.setMediaItem(MediaItem.fromUri(track.file))
                player.prepare()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Load track failed: ${e.message}")
            _error.value = "音频加载失败：${track.name}"
            return null
        }
        return track
    }

    fun playMusic() {
        bgmPlayer?.play()
    }

    fun pauseMusic() {
        bgmPlayer?.pause()
    }

    fun togglePlay() {
        bgmPlayer?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun prevTrack(playlist: List<BgmTrack>): BgmTrack? {
        if (playlist.isEmpty()) return null
        val index = if (_isShuffle.value && playlist.size > 1) randomIndex(playlist.size) else bgmIndex - 1
        return loadTrack(index, playlist)
    }

    fun nextTrack(playlist: List<BgmTrack>): BgmTrack? {
        if (playlist.isEmpty()) return null
        val index = if (_isShuffle.value && playlist.size > 1) randomIndex(playlist.size) else bgmIndex + 1
        return loadTrack(index, playlist)
    }

    /** 随机一个 != 当前下标的索引（shuffle 切歌用）。 */
    private fun randomIndex(size: Int): Int {
        var r = bgmIndex
        while (r == bgmIndex && size > 1) r = Random.nextInt(size)
        return r
    }

    /**
     * 播放列表增删后重映射当前下标：
     * - 列表空 → 暂停并复位；
     * - 当前曲 key 仍在 → 仅重映射下标，不打断播放；
     * - 当前曲被删 → 加载邻近曲，若之前在播放则继续播放。
     */
    fun syncIndex(newPlaylist: List<BgmTrack>) {
        if (newPlaylist.isEmpty()) {
            bgmPlaylist = emptyList()
            bgmIndex = 0
            _currentIndex.value = 0
            bgmPlayer?.pause()
            return
        }
        val oldKey = bgmPlaylist.getOrNull(bgmIndex)?.key
        bgmPlaylist = newPlaylist
        val newIndex = oldKey?.let { key -> newPlaylist.indexOfFirst { it.key == key }.takeIf { it >= 0 } }
        if (newIndex != null) {
            bgmIndex = newIndex
            _currentIndex.value = newIndex
            return
        }
        val wasPlaying = bgmPlayer?.isPlaying == true
        val fallback = bgmIndex.coerceIn(0, newPlaylist.lastIndex)
        loadTrack(fallback, newPlaylist)?.let { if (wasPlaying) bgmPlayer?.play() }
    }

    fun setShuffle(enabled: Boolean) {
        _isShuffle.value = enabled
    }

    fun isShuffleEnabled(): Boolean = _isShuffle.value

    fun seekTo(position: Long) {
        bgmPlayer?.seekTo(position)
    }

    suspend fun setVolume(vol: Int) {
        val v = (vol / 100f).coerceIn(0f, 1f)
        bgmPlayer?.volume = v
        voicePlayer?.setVolume(v, v)
        settings.setVolume(vol)
    }

    /**
     * 仅应用音量到播放器（不持久化）。
     * 供音乐页音量滑块拖动时实时反馈使用，松手时再调用 [setVolume] 持久化。
     */
    fun applyVolume(vol: Int) {
        val v = (vol / 100f).coerceIn(0f, 1f)
        bgmPlayer?.volume = v
        voicePlayer?.setVolume(v, v)
    }

    fun getCurrentPosition(): Long = bgmPlayer?.currentPosition ?: 0
    fun getDuration(): Long = bgmPlayer?.duration ?: 0
    val isPlaying: Boolean get() = bgmPlayer?.isPlaying == true

    fun getCurrentTrack(playlist: List<BgmTrack>): BgmTrack? =
        if (bgmPlaylist.isNotEmpty() && bgmIndex in playlist.indices) playlist.getOrNull(bgmIndex) else null

    fun setRepeatMode(mode: Int) {
        repeatMode = mode
        // 单曲循环用 REPEAT_MODE_ONE（ExoPlayer 自身循环）；顺序/列表循环用 REPEAT_MODE_OFF（列表循环由 STATE_ENDED 手动切歌）
        bgmPlayer?.repeatMode = if (mode == REPEAT_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun getRepeatMode(): Int = repeatMode

    /** 清除错误提示 */
    fun clearError() {
        _error.value = null
    }

    fun release() {
        stopVoice()
        bgmPlayer?.release()
        bgmPlayer = null
    }
}
