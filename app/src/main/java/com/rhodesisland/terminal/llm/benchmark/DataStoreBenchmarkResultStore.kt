package com.rhodesisland.terminal.llm.benchmark

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 基准结果专用 DataStore（文件 benchmark_store.preferences_pb）。corruptionHandler：损坏重置为空（见 DataStoreCorruption.kt）。 */
private val Context.benchmarkResultStore by preferencesDataStore(
    name = "benchmark_store",
    corruptionHandler = com.rhodesisland.terminal.data.local.tolerantCorruptionHandler,
)

/**
 * DataStore + JSON 基准结果持久化（Task 5 Step 4）。
 *
 * 键 = `scenario.storageKey|quadrant|deviceFingerprint|configFingerprint`（四象限同场景分键归档，
 * 保证 CPU/GPU、思考开/关的结果互不覆盖）；值 = kotlinx.serialization JSON
 * （ignoreUnknownKeys + encodeDefaults，与遥测持久化风格一致）。
 *
 * 契约约束：仅持久化 [BenchmarkScenarioResult.coolRun]=true 的结果；热态/噪声结果不落盘。
 * 覆盖式更新：同键再 save 直接覆盖旧值。
 *
 * 契约 [BenchmarkResultStore.load] 的 quadrant 为可空（Task 5 review M-1）：非 null 时直取
 * 该象限键；null 时依次尝试四个象限键返回首个命中（同一场景+指纹下一般仅一份冷态结果，
 * 保留既有首命中兼容）。
 */
class DataStoreBenchmarkResultStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : BenchmarkResultStore {

    private val context = context.applicationContext

    override suspend fun save(result: BenchmarkScenarioResult) {
        // 契约：仅存冷态结果。
        if (!result.coolRun) {
            Log.w(TAG, "热态/非冷态结果不落盘: scenario=${result.scenario.storageKey} coolRun=false")
            return
        }
        val key = keyOf(result.scenario, result.deviceFingerprint, result.configFingerprint, result.quadrant)
        context.benchmarkResultStore.edit { prefs ->
            prefs[stringPreferencesKey(key)] = json.encodeToString(result)
        }
        Log.i(TAG, "基准结果已持久化: $key")
    }

    override suspend fun load(
        scenario: InferenceBenchmarkScenario,
        deviceFingerprint: String,
        configFingerprint: String,
        quadrant: InferenceBackendQuadrant?,
    ): BenchmarkScenarioResult? {
        val prefs = context.benchmarkResultStore.data.first()
        // M-1：非 null 时直取该象限键，不受「枚举序首命中」遮蔽（GPU/CPU 同存时按象限精确读回）。
        if (quadrant != null) {
            return decodeOrNull(prefs[stringPreferencesKey(keyOf(scenario, deviceFingerprint, configFingerprint, quadrant))])
        }
        for (candidate in InferenceBackendQuadrant.entries) {
            val key = keyOf(scenario, deviceFingerprint, configFingerprint, candidate)
            val raw = prefs[stringPreferencesKey(key)] ?: continue
            decodeOrNull(raw)?.let { return it }
        }
        return null
    }

    /** 键值 JSON 解码（解析失败仅记日志返回 null，容忍脏数据）。 */
    private fun decodeOrNull(raw: String?): BenchmarkScenarioResult? {
        if (raw == null) return null
        return runCatching { json.decodeFromString<BenchmarkScenarioResult>(raw) }
            .getOrElse {
                Log.w(TAG, "基准结果 JSON 解析失败（忽略）: ${it.message}")
                null
            }
    }

    /**
     * 组装归档键：`scenario.storageKey|quadrant|deviceFingerprint|configFingerprint`。
     *
     * Task 5 review M-7：键以 `|` 分隔——storageKey/deviceFingerprint/configFingerprint 均
     * **不得含 `|` 字符**（否则键维度串位、四象限分键失效）。指纹由调用方构造、可控，本实现
     * 不做转义；若未来指纹来源不可控，需改为转义或 length-prefix 编码。
     */
    private fun keyOf(
        scenario: InferenceBenchmarkScenario,
        deviceFingerprint: String,
        configFingerprint: String,
        quadrant: InferenceBackendQuadrant?,
    ): String {
        val quadrantPart = quadrant?.storageKey ?: "UNKNOWN"
        return "${scenario.storageKey}|$quadrantPart|$deviceFingerprint|$configFingerprint"
    }

    companion object {
        private const val TAG = "DataStoreBenchmarkResultStore"
    }
}
