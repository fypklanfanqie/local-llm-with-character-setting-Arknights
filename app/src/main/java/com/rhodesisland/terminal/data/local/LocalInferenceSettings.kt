package com.rhodesisland.terminal.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.thinking.LocalThinkingLevel

/**
 * 本地推理参数的不可变快照（Task 6）。
 *
 * 一次聚合所有本地 LLM 设置 + [performanceMode]，替换 [SettingsStore] 中 provider 侧
 * 逐字段 `.first()` 的多次读取；一次 `data.map` 构建、整体读取，减少国产 ROM 上
 * DataStore I/O 被拦截时的挂起点数量。
 *
 * [cpuBoost] / [lookahead] 为 **legacy** 键：仍可读、UI 高级诊断视图仍可改，但自
 * [performanceMode] 引入起不再是权威设置——具体模式行为由后续任务（resolved plans）
 * 解析覆盖。字段默认值与现有独立 flow 保持一致（缺省只读、不写回 DataStore）。
 */
data class LocalInferenceSettings(
    val performanceMode: InferencePerformanceMode = InferencePerformanceMode.DEFAULT,
    val contextLen: Int = AppConfig.LLM.DEFAULT_CONTEXT_LEN,
    val threads: Int = AppConfig.LLM.DEFAULT_THREADS,
    val temperature: Float = AppConfig.LLM.DEFAULT_TEMPERATURE,
    val maxTokens: Int = AppConfig.LLM.DEFAULT_MAX_TOKENS,
    val backend: BackendPreference = BackendPreference.AUTO,
    /** legacy：CPU 提频开关（默认开）。性能模式下由解析层决定是否仍生效。 */
    val cpuBoost: Boolean = true,
    /** legacy：lookahead 投机解码开关（默认关）。性能模式下由解析层决定。 */
    val lookahead: Boolean = false,
    /** 深度思考开关（本地 + 云端通用，默认关）。开启时展示推理过程并（对支持的供应商）请求思考。 */
    val deepThinking: Boolean = false,
    /** 本地思考档位（默认 AUTO，仅本地生效）：开启深度思考后决定思考强度；云端不读取本字段。 */
    val thinkingLevel: LocalThinkingLevel = LocalThinkingLevel.DEFAULT,
) {
    companion object {
        // DataStore 键名单点定义；SettingsStore.Keys 中的同名键与这里保持一致。
        const val PERFORMANCE_MODE_KEY = "llm_performance_mode"
        const val CPU_BOOST_KEY = "llm_cpu_boost"
        const val LOOKAHEAD_KEY = "llm_lookahead"
        const val CONTEXT_LEN_KEY = "llm_context_len"
        const val THREADS_KEY = "llm_threads"
        const val TEMPERATURE_KEY = "llm_temperature"
        const val MAX_TOKENS_KEY = "llm_max_tokens"
        const val BACKEND_KEY = "llm_backend"
        const val DEEP_THINKING_KEY = "deep_thinking"
        const val THINKING_LEVEL_KEY = "llm_thinking_level"

        private val modeKey = stringPreferencesKey(PERFORMANCE_MODE_KEY)
        private val cpuBoostKey = booleanPreferencesKey(CPU_BOOST_KEY)
        private val lookaheadKey = booleanPreferencesKey(LOOKAHEAD_KEY)
        private val contextKey = intPreferencesKey(CONTEXT_LEN_KEY)
        private val threadsKey = intPreferencesKey(THREADS_KEY)
        private val tempKey = floatPreferencesKey(TEMPERATURE_KEY)
        private val maxTokensKey = intPreferencesKey(MAX_TOKENS_KEY)
        private val backendKey = stringPreferencesKey(BACKEND_KEY)
        private val thinkKey = booleanPreferencesKey(DEEP_THINKING_KEY)
        private val thinkingLevelKey = stringPreferencesKey(THINKING_LEVEL_KEY)

        /** 从一次 DataStore Preferences 快照聚合出不可变推理设置。 */
        fun fromPreferences(prefs: Preferences): LocalInferenceSettings = LocalInferenceSettings(
            performanceMode = InferencePerformanceMode.fromStorageKey(prefs[modeKey]),
            contextLen = prefs[contextKey] ?: AppConfig.LLM.DEFAULT_CONTEXT_LEN,
            threads = prefs[threadsKey] ?: AppConfig.LLM.DEFAULT_THREADS,
            temperature = prefs[tempKey] ?: AppConfig.LLM.DEFAULT_TEMPERATURE,
            maxTokens = prefs[maxTokensKey] ?: AppConfig.LLM.DEFAULT_MAX_TOKENS,
            backend = BackendPreference.fromKey(prefs[backendKey]),
            cpuBoost = prefs[cpuBoostKey] ?: true,
            lookahead = prefs[lookaheadKey] ?: false,
            deepThinking = prefs[thinkKey] ?: false,
            thinkingLevel = LocalThinkingLevel.fromStorageKey(prefs[thinkingLevelKey]),
        )
    }
}
