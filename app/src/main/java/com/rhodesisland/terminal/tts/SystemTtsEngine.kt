package com.rhodesisland.terminal.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.rhodesisland.terminal.data.model.SystemVoiceTemplate
import com.rhodesisland.terminal.data.model.TtsLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** 设备系统语音的轻量描述（纯数据，便于 JVM 单测）。 */
data class SystemVoiceInfo(val name: String, val locale: String)

/**
 * 按模板为指定语言挑选设备语音（纯函数，JVM 可测）：
 * 1. 目标语言（zh / ja 前缀）语音中，按模板关键词（小写包含）挑第一个命中；
 * 2. 无关键词命中时回落目标语言第一个语音（模板参数 pitch/rate 仍生效）；
 * 3. 目标语言完全没有语音时返回 null（调用方回落到引擎默认语音或抛明确错误）。
 */
fun matchSystemVoiceForTemplate(
    voices: List<SystemVoiceInfo>,
    template: SystemVoiceTemplate,
    language: TtsLanguage,
): SystemVoiceInfo? {
    val pool = voices.filter { normalizeVoiceLocale(it.locale).startsWith(language.code) }
    if (pool.isEmpty()) return null
    if (template == SystemVoiceTemplate.DEFAULT || template.voiceMatchers.isEmpty()) return pool.first()
    val lowered = pool.map { it.name.lowercase() }
    for (keyword in template.voiceMatchers) {
        val index = lowered.indexOfFirst { it.contains(keyword) }
        if (index >= 0) return pool[index]
    }
    return pool.first()
}

/** 归一化语音 locale（"zh_CN" / "zh-CN" 等 -> 小写 "zh_cn"）。 */
internal fun normalizeVoiceLocale(locale: String): String =
    locale.trim().lowercase().replace('-', '_')

/**
 * 手机自带 TTS 引擎封装（android.speech.tts.TextToSpeech）。
 *
 * - 懒初始化：首次 [speak] 时才创建引擎并等待 onInit（4s 超时），初始化失败抛清晰中文错误；
 * - 语音选择：[matchSystemVoiceForTemplate] 按语言+模板挑 voice，无目标语言语音时：
 *   ja -> 抛错误（提示切云端）；zh -> 使用引擎默认语音；
 * - [playing] 由 UtteranceProgressListener 驱动（onDone/onError 复位）；
 * - 系统引擎不支持暂停：pause 语义退化为停止；
 * - 全部调用切到主线程（TextToSpeech 绑定回调依赖主线程 Looper）。
 */
class SystemTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "SystemTtsEngine"
        private const val INIT_TIMEOUT_MS = 4_000L
        private const val UTTERANCE_ID = "tts_system_utterance"
    }

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var playing = false

    val isPlaying: Boolean get() = playing

    /** 合成并朗读（初始化懒执行）。 */
    suspend fun speak(
        text: String,
        language: TtsLanguage,
        template: SystemVoiceTemplate,
    ) = withContext(Dispatchers.Main) {
        val engine = ensureEngine()
            ?: throw IllegalStateException(
                "系统语音引擎启动失败：请到系统设置 → 更多设置 → 无障碍 → 文字转语音（TTS）输出中，" +
                    "确认已安装并选中一个语音引擎（如小爱同学/讯飞），必要时先下载语音数据"
            )
        // 语音选择：优先模板匹配；目标语言无语音时日语报错、中文回落默认。
        val rawVoices = engine.voices.orEmpty()
        val voices = rawVoices.map { SystemVoiceInfo(it.name, it.locale.toLanguageTag()) }
        val matched = matchSystemVoiceForTemplate(voices, template, language)
        var setVoiceResult = -1
        if (matched != null) {
            val real = rawVoices.firstOrNull { it.name == matched.name }
            real?.let { setVoiceResult = runCatching { engine.setVoice(it) }.getOrDefault(-1) }
        } else if (language == TtsLanguage.JA) {
            throw IllegalStateException("手机系统语音不支持日语，请切换中文，或在设置中改用云端引擎")
        }
        if (matched == null) {
            val result = engine.setLanguage(
                if (language == TtsLanguage.JA) Locale.JAPANESE else Locale.CHINESE
            )
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (language == TtsLanguage.JA) {
                    throw IllegalStateException("手机系统语音不支持日语，请切换中文，或在设置中改用云端引擎")
                }
            }
        }
        engine.setPitch(template.pitch)
        engine.setSpeechRate(template.rate)
        Log.w(
            TAG,
            "speak template=${template.storageKey} voiceCount=${rawVoices.size} " +
                "voices=[${rawVoices.joinToString(",") { it.name }}] matched=${matched?.name} " +
                "setVoiceResult=$setVoiceResult pitch=${template.pitch} rate=${template.rate}"
        )
        playing = true
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** 停止朗读（系统引擎无暂停语义，pause 亦走此路径）。 */
    fun stop() {
        runCatching {
            tts?.let { engine ->
                engine.stop()
                engine.setOnUtteranceProgressListener(null)
            }
        }
        playing = false
    }

    /** 释放引擎（AppContainer 生命周期销毁时调用，本应用内常驻可仅靠 stop）。 */
    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }

    private suspend fun ensureEngine(): TextToSpeech? = withContext(Dispatchers.Main) {
        tts?.let { return@withContext it }
        val deferred = CompletableDeferred<Int>()
        val engine = TextToSpeech(context.applicationContext) { status -> deferred.complete(status) }
        val status = withTimeoutOrNull(INIT_TIMEOUT_MS) { deferred.await() }
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "system tts init failed status=${status ?: "timeout"}")
            runCatching { engine.shutdown() }
            return@withContext null
        }
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                playing = false
            }

            override fun onError(utteranceId: String?) {
                Log.w(TAG, "system tts utterance error id=$utteranceId")
                playing = false
            }
        })
        tts = engine
        engine
    }
}
