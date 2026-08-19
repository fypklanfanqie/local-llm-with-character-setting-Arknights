package com.rhodesisland.terminal.manager

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.SystemVoiceTemplate
import com.rhodesisland.terminal.data.model.TtsConfig
import com.rhodesisland.terminal.data.model.TtsEngine
import com.rhodesisland.terminal.data.model.TtsLanguage
import com.rhodesisland.terminal.data.model.VoiceConfig
import com.rhodesisland.terminal.data.model.VoicePair
import com.rhodesisland.terminal.data.repository.SettingsRepository
import com.rhodesisland.terminal.tts.SystemTtsEngine
import com.rhodesisland.terminal.tts.VolcTtsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * TTS 管理器：按设置中的引擎分支朗读。
 *
 * - [TtsEngine.SYSTEM]（默认）：手机自带 TextToSpeech（离线、免凭据），声音模板见
 *   [SystemVoiceTemplate]；系统引擎不支持暂停，pause 退化为停止；
 * - [TtsEngine.CLOUD]：保持原小程序逻辑——CloudRun /tts 合成 base64 mp3 落临时文件后 MediaPlayer 播放，
 *   声音复刻音色按角色音色映射选择，支持中日双语。
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

    /** 系统引擎（懒初始化，见 SystemTtsEngine）。 */
    private val systemTts = SystemTtsEngine(context)

    /** 串行化 speak，避免并发调用互相打断导致状态错乱/资源泄漏 */
    private val mutex = Mutex()

    val playing: Boolean get() = isPlaying || systemTts.isPlaying

    /** 检查云端 TTS 凭据是否已配置（仅云端引擎需要）。 */
    suspend fun hasCredentials(): Boolean {
        val config = settings.getTtsConfigNow()
        return client.hasCredentials(config)
    }

    /**
     * 系统引擎试听：用未保存的模板立即朗读一段示例（设置页「试听」用）。
     * 语言跟随已保存设置；不触碰云端凭据。
     */
    suspend fun previewSystem(text: String, template: SystemVoiceTemplate) = mutex.withLock {
        if (text.isBlank()) throw Exception("没有可朗读的文本")
        if (playing) stopAll()
        val language = settings.getTtsLanguageNow()
        systemTts.speak(cleanTtsText(text), language, template)
    }

    /**
     * 合成并播放语音
     * @param text 待朗读文本
     * @param characterId 角色 ID（云端引擎用于选择音色；系统引擎忽略）
     */
    suspend fun speak(text: String, characterId: String) = mutex.withLock {
        if (text.isBlank()) throw Exception("没有可朗读的文本")
        if (playing) stopAll()

        val language = settings.getTtsLanguageNow()
        val engine = settings.getTtsEngineNow()
        when (engine) {
            TtsEngine.SYSTEM -> {
                val template = settings.getTtsSystemTemplateNow()
                val cleanText = cleanTtsText(text)
                systemTts.speak(cleanText, language, template)
            }
            TtsEngine.CLOUD -> speakCloud(text, characterId, language)
        }
    }

    /** 云端引擎合成并播放（原 MediaPlayer 路径；仅在 [speak] 持有 mutex 时调用，自身不加锁）。 */
    private suspend fun speakCloud(text: String, characterId: String, language: TtsLanguage) {
        val ttsConfig = settings.getTtsConfigNow()
        val volume = withTimeoutOrNull(5000) { settings.ttsVolume.first() }
            ?: AppConfig.TTS_DEFAULT_VOLUME

        if (!client.hasCredentials(ttsConfig)) {
            throw Exception("请先在设置页配置火山引擎 TTS 凭据（或改用手机系统语音）")
        }

        // 当前语言的音色与 Resource ID 必须成对配置，避免服务端返回资源不匹配错误。
        val voice = selectVoiceConfig(settings.getTtsVoiceMapNow()[characterId], language)
        if (!voice.isComplete) {
            throw Exception(
                voice.validationError(if (language == TtsLanguage.ZH) "中文" else "日文")
                    ?: "请先在设置页为该角色配置${language.label}音色与 Resource ID",
            )
        }

        // 清理括号内容
        val cleanText = cleanTtsText(text)

        val audioBytes = client.synthesize(cleanText, characterId, ttsConfig, voice)

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
        systemTts.stop()
    }

    /**
     * 视频等其它音频抢占焦点时暂停当前 TTS（云端保留临时文件与 MediaPlayer，可 [resume] 续播；
     * 系统引擎不支持暂停，语义退化为停止）。无播放中实例时为空操作；暂停期间 [playing] 仍为 true
     * （云端），UI 视为「正在播放」。云端路径与 [playAudio] 一样需在主线程调用。
     */
    fun pause() {
        if (systemTts.isPlaying) {
            systemTts.stop()
            return
        }
        mediaPlayer?.let { mp ->
            runCatching { if (mp.isPlaying) mp.pause() }
        }
    }

    /**
     * 抢占方释放音频后恢复被 [pause] 暂停的云端 TTS 播放。无暂停中的 MediaPlayer 时为空操作。
     * 与 [playAudio] 一样需在主线程调用。
     */
    fun resume() {
        mediaPlayer?.let { mp ->
            runCatching { if (!mp.isPlaying) mp.start() }
        }
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

internal fun selectVoiceConfig(pair: VoicePair?, language: TtsLanguage): VoiceConfig = when (language) {
    TtsLanguage.ZH -> pair?.zh ?: VoiceConfig()
    TtsLanguage.JA -> pair?.ja ?: VoiceConfig()
}
