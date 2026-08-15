package com.rhodesisland.terminal.llm.benchmark

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
import java.security.MessageDigest
import com.chatbyyourside.llm.backend.MnnBridge

private val Context.certificationDataStore by preferencesDataStore(name = "certification_store")

/**
 * 已认证推理选项（Task 6）。
 *
 * 记录「该 device+model+variant+native 组合上 lookahead / decodeStepTokens 有基准证据的收益」：
 * 由 [ExperimentalPromotionPolicy] 判定 Promote 的候选配置经 [InferenceCertificationStore.toCertifiedOptions]
 * 生成并落盘。[InferenceProfileResolver] 在启用 lookahead / 多 token 步进前按组合查证本记录。
 *
 * 字段全默认值（@Serializable 兼容）：未来追加字段不破坏旧记录解析（ignoreUnknownKeys + 默认值）。
 * [variant] 是运行时变体枚举名（[RuntimeVariant]，如 CPU_OPTIMIZED）——认证按「组合」而非配置字节
 * 生效（[certifiedConfigHash] 仅诊断用途，不做启用前匹配校验：候选配置哈希本身含 lookahead，
 * 启用前无法预计算匹配）。
 *
 * @param lookahead 该组合是否认证了 lookahead 投机解码收益（仅 CPU_OPTIMIZED 组合有意义）。
 * @param decodeStepTokens 认证的多 token 步进（1=仅认证了逐 token，无步进证据）。
 * @param certifiedConfigHash 认证时的候选配置哈希（诊断用途，不做门禁匹配）。
 * @param certifiedAtElapsedMs 认证时刻（SystemClock.elapsedRealtime 语义；诊断/过期策略用）。
 */
@Serializable
data class CertifiedInferenceOptions(
    val deviceFingerprint: String = "",
    val modelFingerprint: String = "",
    val variant: String = "",
    val nativeBuildId: String = "",
    val mnnCommit: String = "",
    val lookahead: Boolean = false,
    val decodeStepTokens: Int = 1,
    val certifiedConfigHash: String? = null,
    val certifiedAtElapsedMs: Long = 0L,
)

/**
 * 推理选项认证存储（DataStore `certification_store`，Task 6）。
 *
 * 键 = [certKey]（device+model+variant+native 组合的 SHA-256 前 16 hex）；值 = JSON 序列化的
 * [CertifiedInferenceOptions]。指纹变化（OTA/驱动/模型替换/native 重建）-> 键变化 -> 旧认证自然失效，
 * 与 [com.rhodesisland.terminal.llm.backend.BackendHealthStore] 的 map 持久化模式一致。
 * 键粒度与健康存储的分歧（含原因）见 [certKey] KDoc——有意为之，勿盲目统一（Task 6 review M-1）。
 *
 * **线程数认证不在本任务范围**：CPU 线程数保持热准入机制（min(用户, 大核, 温控)）；四象限基准数据
 * 由 [LocalInferenceBenchmarkRunner] 产出（Task 5），Task 7 接 UI 后用户/未来流程可按相同组合键
 * 认证线程配置——本存储的组合键（device/model/variant/native）天然可承载，无需改存储结构。
 *
 * 认证记录来源（Task 6 范围）：本类只实现存储与纯映射（[toCertifiedOptions]）；基准触发与 UI 入口
 * 由 Task 7 接入。存储类绑定 Android Context，无法纯 JVM 实例化——map 编解码纯逻辑抽到 companion
 * （[decodeRecords]/[encodeRecords]），JVM 单测覆盖（仿 BackendHealthStoreTest 的纯函数模式）。
 */
class InferenceCertificationStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val recordsKey = stringPreferencesKey("records")

    /** 全部认证记录快照（certKey -> 选项）。 */
    val records: Flow<Map<String, CertifiedInferenceOptions>> =
        context.certificationDataStore.data.map { prefs ->
            decodeRecords(prefs[recordsKey] ?: "")
        }

    /** 读单条认证（未命中/损坏记录返回 null = 未认证）。 */
    suspend fun get(key: String): CertifiedInferenceOptions? =
        records.map { it[key] }.first()

    /** 保存/覆盖一条认证。 */
    suspend fun save(options: CertifiedInferenceOptions) {
        context.certificationDataStore.edit { prefs ->
            val current = decodeRecords(prefs[recordsKey] ?: "").toMutableMap()
            current[certKey(options)] = options
            prefs[recordsKey] = encodeRecords(current)
        }
    }

    /** 重置全部认证（用户显式重置 / 诊断页）。 */
    suspend fun resetAll() {
        context.certificationDataStore.edit { it.remove(recordsKey) }
    }

    companion object {
        private const val HASH_HEX_LENGTH = 16

        /** 由选项自身推导认证键（identity 字段与 [certKey] 同源）。 */
        fun certKey(options: CertifiedInferenceOptions): String = certKey(
            deviceFingerprint = options.deviceFingerprint,
            modelFingerprint = options.modelFingerprint,
            variant = options.variant,
            nativeBuildId = options.nativeBuildId,
            mnnCommit = options.mnnCommit,
        )

        /**
         * 认证键：device+model+variant+native 组合的 SHA-256 前 16 hex。
         *
         * 任一分量变化（换设备/换模型/变体变化/native 重建或 MNN commit 升级）即新键，
         * 旧认证自然失效——认证的正是「该组合上有基准证据的收益」。
         *
         * **粒度分歧（与 [com.rhodesisland.terminal.llm.backend.BackendHealthStore.keyFor] 有意不同，
         * 勿盲目统一，Task 6 review M-1）**：健康键为 device+model+backend+variant、**不含 native
         * 身份**——健康记录跨 native 重建存活（旧构建的失败教训仍适用于新构建的同一后端，「缓用」
         * 语义保守）；本认证键含 nativeBuildId/mnnCommit——认证是「该二进制上实测的收益」，native
         * 重建即证据失效。两方向都保守：健康记录尽量复用、认证尽量失效。
         */
        fun certKey(
            deviceFingerprint: String,
            modelFingerprint: String,
            variant: String,
            nativeBuildId: String,
            mnnCommit: String,
        ): String = sha256(
            listOf(deviceFingerprint, modelFingerprint, variant, nativeBuildId, mnnCommit)
                .joinToString("\n"),
        ).take(HASH_HEX_LENGTH)

        /**
         * 认证记录映射（纯函数，JVM 可测）：[PromotionDecision.Promote] -> 生成 [CertifiedInferenceOptions]
         * 记录；[PromotionDecision.Reject]（或任何非 Promote 决策）-> null（不记录）。
         *
         * [case] 提供 device/model/象限指纹；native 构建身份（[nativeBuildId]/[mnnCommit]）**由调用方
         * 传入**（Task 7 基准触发从 [BenchmarkScenarioResult] 同源取 [MnnBridge.runtimeInfo]；本映射
         * 不读 [MnnBridge]，保持纯映射 JVM 可测）。**任一分量为空/空白时返回 null（不生成认证记录）**——
         * 认证键含 native 身份（[certKey] 五分量），握手缺席（runtimeInfo==null）时若仍记录，键退化为
         * device+model+variant 三分量：应用更新 native 后同键不变，旧二进制证据会在新构建上继续启用
         * 步进/lookahead（证据错配）。无 native 身份证明不认证。
         * 变体由象限推导：GPU 象限 -> [RuntimeVariant.OPENCL]，CPU 象限 -> [RuntimeVariant.CPU_OPTIMIZED]
         * （lookahead / 多 token 步进只对 CPU 组合有意义，resolver 门禁只认 CPU_OPTIMIZED 认证）。
         *
         * @param nativeBuildId native 构建身份（[MnnBridge.runtimeInfo] 同源）；空/空白 -> 返回 null。
         * @param mnnCommit MNN commit（同上）；空/空白 -> 返回 null。
         * @param decodeStepTokens 被认证候选实测的 decode 步长（1=仅 lookahead 认证；2..4=步进认证）。
         * @param lookaheadEvidence 本次基准是否产生 lookahead 证据（lookahead 开 vs 关对比基准 -> true；
         *        纯步进基准（step=2 vs 1）-> false）。认证记录按此字段落 lookahead——纯步进认证不得
         *        留下 lookahead=true 的记录，否则用户请求 lookahead 时 resolver 无 lookahead 基准证据
         *        仍启用 speculative_type（证据错配启用）。
         * @param configHash 认证时的候选配置哈希（诊断用途）。
         * @param nowElapsedMs 认证时刻（SystemClock.elapsedRealtime 语义）。
         */
        fun toCertifiedOptions(
            case: InferenceBenchmarkCase,
            decision: PromotionDecision,
            nativeBuildId: String,
            mnnCommit: String,
            decodeStepTokens: Int,
            lookaheadEvidence: Boolean,
            configHash: String?,
            nowElapsedMs: Long,
        ): CertifiedInferenceOptions? {
            if (decision !is PromotionDecision.Promote) return null
            // 无 native 身份证明不认证（见 KDoc）：握手缺席的基准结果不产生认证记录。
            if (nativeBuildId.isBlank() || mnnCommit.isBlank()) return null
            val variant = if (case.quadrant.usesGpu) {
                RuntimeVariant.OPENCL.name
            } else {
                RuntimeVariant.CPU_OPTIMIZED.name
            }
            return CertifiedInferenceOptions(
                deviceFingerprint = case.deviceFingerprint,
                modelFingerprint = case.modelFingerprint,
                variant = variant,
                nativeBuildId = nativeBuildId,
                mnnCommit = mnnCommit,
                // lookahead 证据由基准类型决定（lookaheadEvidence），不做硬编码：纯步进基准
                // （step=2 vs 1）不产生 lookahead 证据，record.lookahead=false 防止门禁误启用。
                lookahead = lookaheadEvidence,
                decodeStepTokens = decodeStepTokens,
                certifiedConfigHash = configHash,
                certifiedAtElapsedMs = nowElapsedMs,
            )
        }

        /** map 反序列化（纯逻辑，JVM 可测）：空白/损坏记录 -> 空 map（与 BackendHealthStore 一致）。 */
        fun decodeRecords(raw: String): Map<String, CertifiedInferenceOptions> {
            if (raw.isBlank()) return emptyMap()
            return runCatching {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString<Map<String, CertifiedInferenceOptions>>(raw)
            }.getOrDefault(emptyMap())
        }

        /** map 序列化（纯逻辑，JVM 可测）。 */
        fun encodeRecords(records: Map<String, CertifiedInferenceOptions>): String =
            Json { ignoreUnknownKeys = true }.encodeToString(records)

        private fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
