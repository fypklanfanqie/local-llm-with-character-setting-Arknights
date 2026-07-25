package com.rhodesisland.terminal.manager

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.model.TtsLanguage
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.tts.VolcTtsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS 管理器
 *
 * 保持原小程序 utils/tts.js 逻辑：
 * 1. 调用 CloudRun /tts 获取 base64 mp3
 * 2. 写入临时文件
 * 3. MediaPlayer 播放
 *
 * 云端回复和本地回复都调用此 TTS。
 */
class TtsManager(
    private val context: Context,
    private val client: VolcTtsClient,
    private val settings: SettingsRepository,
) {

    companion object {
        private const val TAG = "TtsManager"
    }

    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var isPlaying = false
    /** 当前播放的临时音频文件；中途 stopAll 时 onCompletion 不会触发，需主动删除避免泄漏 */
    private var currentFile: File? = null

    /** 串行化 speak，避免并发调用互相打断导致状态错乱/资源泄漏 */
    private val mutex = Mutex()

    val playing: Boolean get() = isPlaying

    /** 检查 TTS 凭据是否已配置 */
    suspend fun hasCredentials(): Boolean {
        val config = settings.getTtsConfigNow()
        return client.hasCredentials(config)
    }

    /**
     * 合成并播放语音
     * @param text 待朗读文本
     * @param characterId 角色 ID（用于选择音色）
     */
    suspend fun speak(text: String, characterId: String) = mutex.withLock {
        if (text.isBlank()) throw Exception("没有可朗读的文本")
        if (isPlaying) stopAll()

        val language = settings.getTtsLanguageNow()
        val ttsConfig = settings.getTtsConfigNow()
        val volume = settings.ttsVolume.first()

        if (!client.hasCredentials(ttsConfig)) {
            throw Exception("请先在设置页配置火山引擎 TTS 凭据")
        }

        // 角色音色映射：优先使用用户配置的音色 ID，留空则由服务端默认选择
        val voiceMap = settings.getTtsVoiceMapNow()
        val voice = voiceMap[characterId]?.let { pair ->
            (if (language == TtsLanguage.JA) pair.ja else pair.zh).takeIf { it.isNotBlank() }
        }

        // 清理括号内容
        val cleanText = cleanTtsText(text)

        // 合成（网络 IO，由 Retrofit 调度）
        val audioBytes = client.synthesize(cleanText, language.code, characterId, ttsConfig, voice)

        // 写入临时文件（磁盘 IO，切到 IO 调度器）
        val tempFile = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
        withContext(Dispatchers.IO) { tempFile.writeBytes(audioBytes) }

        // 播放必须在主线程：MediaPlayer 依赖创建线程的 Looper 投递回调，
        // 否则 onCompletion/onError 永不触发，导致 player 不释放、临时文件不删除
        withContext(Dispatchers.Main) { playAudio(tempFile, volume) }
    }

    private fun playAudio(file: File, volume: Int) {
        stopAll()

        val player = MediaPlayer()
        mediaPlayer = player
        currentFile = file

        val vol = (volume / 100f).coerceIn(0f, 1f)

        player.setDataSource(file.absolutePath)
        player.setVolume(vol, vol)
        player.setOnCompletionListener { mp ->
            isPlaying = false
            mediaPlayer = null
            try { mp.release() } catch (e: Exception) {}
            file.delete()
        }
        player.setOnErrorListener { mp, what, extra ->
            Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
            isPlaying = false
            mediaPlayer = null
            try { mp.release() } catch (e: Exception) {}
            file.delete()
            true
        }
        player.prepare()
        player.start()
        isPlaying = true
    }

    fun stopAll() {
        mediaPlayer?.let {
            // release() 在任意状态均合法，直接释放即可；避免 isPlaying/stop 抛异常导致 release 被跳过
            try { it.release() } catch (e: Exception) {
                Log.w(TAG, "Release MediaPlayer: ${e.message}")
            }
        }
        mediaPlayer = null
        isPlaying = false
        // 中途停止时 onCompletion 不会触发，主动删除临时文件避免泄漏
        currentFile?.let { runCatching { it.delete() } }
        currentFile = null
    }

    /** 清理 TTS 文本：去除括号内容（保持原 cleanTtsText 逻辑） */
    fun cleanTtsText(text: String): String {
        return text
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")   // 剥掉 Qwen3 思考块，不朗读
            .replace(Regex("</?think>"), "")                    // 兜底：残留的未闭合标签
            .replace(Regex("[（(][^）)]*[）)]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
