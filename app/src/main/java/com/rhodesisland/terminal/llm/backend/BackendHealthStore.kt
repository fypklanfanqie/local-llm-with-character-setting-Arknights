package com.rhodesisland.terminal.llm.backend

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rhodesisland.terminal.llm.profile.RuntimeVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// corruptionHandler：损坏重置为空（见 DataStoreCorruption.kt），健康记录丢失可由探测重建，绝不崩。
private val Context.healthDataStore by preferencesDataStore(
    name = "health_store",
    corruptionHandler = com.rhodesisland.terminal.data.local.tolerantCorruptionHandler,
)

/** 后端健康状态（Task 9）。 */
enum class HealthState { UNKNOWN, PROBE_OK, MODEL_OK, COOLDOWN, CRASH_BLACKLISTED }

/** 失败类别（决定 skip 时长；cancel/thermal/validation/admission 不记录，故无类别）。 */
enum class HealthFailureClass { PROBE, LOAD, GENERATION }

/** 健康记录键：指纹 + 后端 + 配置族 + 阶段。指纹变化即新键，旧黑名单/基准自然失效。 */
@Serializable
data class BackendHealthKey(
    val deviceFingerprint: String,
    val modelFingerprint: String,
    val backend: String,
    val variant: String,
)

/** 单个健康记录（@Serializable 供 DataStore JSON 持久化）。 */
@Serializable
data class HealthRecord(
    val state: HealthState,
    val failureClass: HealthFailureClass? = null,
    val failureCount: Int = 0,
    val lastFailureElapsedMs: Long? = null,
    val cooldownUntilElapsedMs: Long? = null,
    val pendingMarker: Boolean = false,
)

/**
 * 健康策略决策（纯函数，注入 clock 可测）。
 *
 * - 普通 probe/load 失败：COOLDOWN 24h；
 * - 重复生成失败：COOLDOWN 7d；
 * - 陈旧 crash marker：CRASH_BLACKLISTED（直到指纹变化或显式 reset）；
 * - 取消/热停止/校验/准入拒绝：**无惩罚**（调用方不调 [afterFailure]）。
 */
object BackendHealthPolicy {
    const val PROBE_FAILURE_SKIP_MS = 24L * 60 * 60 * 1000
    const val GENERATION_FAILURE_SKIP_MS = 7L * 24 * 60 * 60 * 1000
    const val REPEATED_GENERATION_FAILURE_THRESHOLD = 2

    fun shouldAttempt(record: HealthRecord?, nowElapsedMs: Long): Boolean {
        if (record == null) return true
        return when (record.state) {
            HealthState.CRASH_BLACKLISTED -> false
            HealthState.COOLDOWN -> {
                val until = record.cooldownUntilElapsedMs
                until == null || nowElapsedMs >= until
            }
            else -> true
        }
    }

    fun afterFailure(
        record: HealthRecord?,
        failureClass: HealthFailureClass,
        nowElapsedMs: Long,
    ): HealthRecord {
        val previousCount = if (record?.failureClass == failureClass) record.failureCount else 0
        val count = previousCount + 1
        val skipMs = when (failureClass) {
            HealthFailureClass.PROBE, HealthFailureClass.LOAD -> PROBE_FAILURE_SKIP_MS
            HealthFailureClass.GENERATION ->
                if (count >= REPEATED_GENERATION_FAILURE_THRESHOLD) GENERATION_FAILURE_SKIP_MS
                else PROBE_FAILURE_SKIP_MS
        }
        return HealthRecord(
            state = HealthState.COOLDOWN,
            failureClass = failureClass,
            failureCount = count,
            lastFailureElapsedMs = nowElapsedMs,
            cooldownUntilElapsedMs = nowElapsedMs + skipMs,
            pendingMarker = false,
        )
    }

    fun afterCrashMarker(nowElapsedMs: Long): HealthRecord = HealthRecord(
        state = HealthState.CRASH_BLACKLISTED,
        failureClass = HealthFailureClass.LOAD,
        failureCount = 1,
        lastFailureElapsedMs = nowElapsedMs,
        cooldownUntilElapsedMs = null,
        pendingMarker = false,
    )

    fun recordOk(state: HealthState): HealthRecord = HealthRecord(state = state)
}

/**
 * 健康记录存储最小操作面（Task 3）：[BackendHealthCoordinator] 依赖此接口而非具体 DataStore 实现，
 * JVM 单测可注入内存替身（BackendHealthStore 绑定 Android Context，无法纯 JVM 实例化）。
 */
interface BackendHealthRecordStore {
    suspend fun get(key: BackendHealthKey): HealthRecord?
    suspend fun update(key: BackendHealthKey, transform: (HealthRecord?) -> HealthRecord?)
}

/**
 * 后端健康记录持久化存储（DataStore `health_store`）。
 *
 * 键 = [BackendHealthKey]；值 = JSON 序列化的 [HealthRecord]。指纹变化（OTA/驱动/模型替换/策略
 * 版本）导致键变化 -> 旧黑名单/基准自然失效。普通失败跳过、黑名单语义与重置策略见 [BackendHealthPolicy]。
 *
 * **键粒度分歧（与 [com.rhodesisland.terminal.llm.benchmark.InferenceCertificationStore.certKey] 有意不同，
 * 勿盲目统一，Task 6 review M-1）**：健康键为 device+model+backend+variant、**不含 native 身份**——
 * 健康记录跨 native 重建存活（旧构建的失败教训仍适用于新构建的同一后端，「缓用」语义保守）；
 * 认证键含 nativeBuildId/mnnCommit——认证证据绑定具体二进制，native 重建即失效。两方向都保守：
 * 健康记录尽量复用、认证尽量失效。
 */
class BackendHealthStore(private val context: Context) : BackendHealthRecordStore {

    /** 与 [STORE_JSON] 同配置；实例别名便于既有代码引用。 */
    private val json = STORE_JSON

    private val healthKey = stringPreferencesKey("records")

    /** 全部健康记录快照。 */
    val records: Flow<Map<BackendHealthKey, HealthRecord>> =
        context.healthDataStore.data.map { prefs ->
            val raw = prefs[healthKey] ?: ""
            if (raw.isBlank()) emptyMap()
            else runCatching {
                json.decodeFromString<Map<BackendHealthKey, HealthRecord>>(raw)
            }.getOrDefault(emptyMap())
        }

    /** 读单条记录（缺失返回 null = 未知健康）。 */
    override suspend fun get(key: BackendHealthKey): HealthRecord? =
        records.map { it[key] }.first()

    /** 原子更新一条记录。 */
    override suspend fun update(key: BackendHealthKey, transform: (HealthRecord?) -> HealthRecord?) {
        context.healthDataStore.edit { prefs ->
            val raw = prefs[healthKey] ?: ""
            val current: MutableMap<BackendHealthKey, HealthRecord> = if (raw.isBlank())
                mutableMapOf()
            else runCatching {
                json.decodeFromString<Map<BackendHealthKey, HealthRecord>>(raw).toMutableMap()
            }.getOrDefault(mutableMapOf())
            val next = transform(current[key])
            if (next != null) current[key] = next else current.remove(key)
            prefs[healthKey] = json.encodeToString(current)
        }
    }

    /** 重置全部健康记录（用户显式 reset / 设置页）。 */
    suspend fun resetAll() {
        context.healthDataStore.edit { it.remove(healthKey) }
    }

    companion object {
        /**
         * 本 store 的 Json 编解码配置：`ignoreUnknownKeys` 容忍旧字段残留；
         * `allowStructuredMapKeys` 允许 `Map<BackendHealthKey, HealthRecord>` 以
         * `[key1, value1, key2, value2, ...]` 数组形式编解码（kotlinx.serialization 默认禁止
         * CLASS 类型作 map 键，BackendHealthKey 是 data class——缺此开关会在首次读写时抛
         * "can't be used in JSON as a key"）。internal 供 JVM 单测复用同一配置做 round-trip。
         */
        internal val STORE_JSON = Json {
            ignoreUnknownKeys = true
            allowStructuredMapKeys = true
        }

        fun keyFor(
            deviceFingerprint: String,
            modelFingerprint: String,
            backend: BackendType,
            variant: RuntimeVariant,
        ) = BackendHealthKey(
            deviceFingerprint = deviceFingerprint,
            modelFingerprint = modelFingerprint,
            backend = backend.name,
            variant = variant.name,
        )
    }
}
