package com.rhodesisland.terminal.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.llm.backend.BackendPreference
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.thinking.LocalThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalInferenceSettings] 默认值与快照聚合测试（Task 6 Step 1）。
 *
 * 纯 JVM：不依赖 Android 运行时（`preferencesOf` 与 `fromPreferences` 均为纯 Kotlin）。
 */
class LocalInferenceSettingsTest {

    private val modeKey = stringPreferencesKey("llm_performance_mode")
    private val cpuBoostKey = booleanPreferencesKey("llm_cpu_boost")
    private val lookaheadKey = booleanPreferencesKey("llm_lookahead")
    private val contextKey = intPreferencesKey("llm_context_len")
    private val threadsKey = intPreferencesKey("llm_threads")
    private val tempKey = floatPreferencesKey("llm_temperature")
    private val maxTokensKey = intPreferencesKey("llm_max_tokens")
    private val backendKey = stringPreferencesKey("llm_backend")
    private val thinkKey = booleanPreferencesKey("deep_thinking")
    private val thinkingLevelKey = stringPreferencesKey("llm_thinking_level")

    @Test
    fun missingOrInvalidModeFallsBackToBalanced() {
        val empty = LocalInferenceSettings.fromPreferences(preferencesOf())
        assertEquals(InferencePerformanceMode.BALANCED, empty.performanceMode)

        val bogus = LocalInferenceSettings.fromPreferences(preferencesOf(modeKey to "BOGUS_MODE"))
        assertEquals(InferencePerformanceMode.BALANCED, bogus.performanceMode)
    }

    @Test
    fun maximumSpeedModeRoundTrips() {
        assertEquals("MAXIMUM_SPEED", InferencePerformanceMode.MAXIMUM_SPEED.storageKey)
        assertEquals(
            InferencePerformanceMode.MAXIMUM_SPEED,
            InferencePerformanceMode.fromStorageKey("MAXIMUM_SPEED"),
        )
        val snap = LocalInferenceSettings.fromPreferences(preferencesOf(modeKey to "MAXIMUM_SPEED"))
        assertEquals(InferencePerformanceMode.MAXIMUM_SPEED, snap.performanceMode)
    }

    @Test
    fun legacyCpuBoostAndLookaheadRemainReadable() {
        val snap = LocalInferenceSettings.fromPreferences(
            preferencesOf(cpuBoostKey to true, lookaheadKey to true),
        )
        // 旧键仍可读；默认分别是开/关。
        assertTrue(snap.cpuBoost)
        assertTrue(snap.lookahead)
        val defaults = LocalInferenceSettings()
        assertTrue(defaults.cpuBoost)
        assertFalse(defaults.lookahead)
    }

    @Test
    fun emptySnapshotUsesConfiguredDefaults() {
        val snap = LocalInferenceSettings.fromPreferences(preferencesOf())
        assertEquals(AppConfig.LLM.DEFAULT_CONTEXT_LEN, snap.contextLen)
        assertEquals(AppConfig.LLM.DEFAULT_THREADS, snap.threads)
        assertEquals(AppConfig.LLM.DEFAULT_TEMPERATURE, snap.temperature, 0.0001f)
        assertEquals(AppConfig.LLM.DEFAULT_MAX_TOKENS, snap.maxTokens)
        assertEquals(BackendPreference.AUTO, snap.backend)
        assertFalse(snap.deepThinking)
        assertEquals(LocalThinkingLevel.DEFAULT, snap.thinkingLevel)
        assertEquals(InferencePerformanceMode.DEFAULT, snap.performanceMode)
    }

    @Test
    fun snapshotUsesStoredValuesWhenPresent() {
        val snap = LocalInferenceSettings.fromPreferences(
            preferencesOf(
                contextKey to 8192,
                threadsKey to 6,
                tempKey to 0.9f,
                maxTokensKey to 4096,
                backendKey to "MNN_CPU",
                thinkKey to true,
            ),
        )
        assertEquals(8192, snap.contextLen)
        assertEquals(6, snap.threads)
        assertEquals(0.9f, snap.temperature, 0.0001f)
        assertEquals(4096, snap.maxTokens)
        assertEquals(BackendPreference.MNN_CPU, snap.backend)
        assertTrue(snap.deepThinking)
    }

    @Test
    fun missingOrUnknownThinkingLevelFallsBackToAuto() {
        val empty = LocalInferenceSettings.fromPreferences(preferencesOf())
        assertEquals(LocalThinkingLevel.AUTO, empty.thinkingLevel)

        val bogus = LocalInferenceSettings.fromPreferences(preferencesOf(thinkingLevelKey to "BOGUS_LEVEL"))
        assertEquals(LocalThinkingLevel.AUTO, bogus.thinkingLevel)
    }

    @Test
    fun thinkingLevelRoundTripsAcrossAllLevels() {
        LocalThinkingLevel.entries.forEach { level ->
            assertEquals(
                level,
                LocalThinkingLevel.fromStorageKey(level.storageKey),
            )
            val snap = LocalInferenceSettings.fromPreferences(preferencesOf(thinkingLevelKey to level.storageKey))
            assertEquals(level, snap.thinkingLevel)
        }
        assertEquals("auto", LocalThinkingLevel.AUTO.storageKey)
    }

    @Test
    fun thinkingLevelIsIndependentFromDeepThinkingSwitch() {
        val off = LocalInferenceSettings.fromPreferences(
            preferencesOf(thinkingLevelKey to "long", thinkKey to false),
        )
        assertEquals(LocalThinkingLevel.LONG, off.thinkingLevel)
        assertFalse(off.deepThinking)

        val on = LocalInferenceSettings.fromPreferences(
            preferencesOf(thinkingLevelKey to "short", thinkKey to true),
        )
        assertEquals(LocalThinkingLevel.SHORT, on.thinkingLevel)
        assertTrue(on.deepThinking)
    }

    /** 超时回退逻辑的纯数据面：不可用时应落到不可变默认快照。 */
    @Test
    fun fallbackDefaultIsFullyPopulatedImmutableSnapshot() {
        val fallback = LocalInferenceSettings()
        assertTrue(fallback.cpuBoost)
        assertEquals(InferencePerformanceMode.BALANCED, fallback.performanceMode)
        // 证明它是一个自洽的"什么都不缺"快照，可供 getLocalInferenceSettingsNow 超时回退。
        assertEquals(
            LocalInferenceSettings.fromPreferences(preferencesOf()),
            fallback,
        )
    }
}
