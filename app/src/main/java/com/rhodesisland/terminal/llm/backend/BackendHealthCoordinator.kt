package com.rhodesisland.terminal.llm.backend

import android.os.Build
import android.os.SystemClock
import com.rhodesisland.terminal.llm.profile.DeviceRuntimeFingerprint
import com.rhodesisland.terminal.llm.profile.OpenClHealthState
import com.rhodesisland.terminal.llm.profile.RuntimeVariant
import java.io.File
import java.security.MessageDigest
import com.chatbyyourside.llm.backend.MnnBridge

/**
 * OpenCL 健康决策（Task 3）。
 *
 * @param state 供 [com.rhodesisland.terminal.llm.profile.InferenceProfileResolver] 决定 OpenCL 是否入链：
 *        PROBE_OK/MODEL_OK 可入链；UNKNOWN 不入链（需探测）；COOLDOWN/CRASH_BLACKLISTED 不入链且降级。
 * @param probeRequired 是否需要先执行一次隔离探测再决策（无记录 / 冷却期已过）。
 * @param reason 决策理由（诊断/日志；无则 null）。
 */
data class BackendHealthDecision(
    val state: OpenClHealthState,
    val probeRequired: Boolean,
    val reason: String? = null,
)

/**
 * 后端健康协调器（Task 3）：OpenCL 准入的单一决策点。
 *
 * 职责：给定设备/模型指纹与后端/变体，从 [BackendHealthRecordStore] 解析健康状态；需要时由调用方
 * （LocalChatProvider）经 [resolveForGpu] 同步跑一次隔离探测（[OpenClProbeRunner]，5s 超时）并回写
 * store，再用新记录重新解析。BackendManager 在生成失败/成功路径回调本协调器记录 LOAD/GENERATION
 * 失败与 MODEL_OK 成功，与探测记录共用同一存储——一套状态，两个消费者。
 *
 * 决策表（[resolve]）：
 * - 无记录                        -> UNKNOWN + probeRequired=true（首次探测）
 * - PROBE_OK / MODEL_OK           -> 原样返回（不重复探测）
 * - COOLDOWN 未过期               -> COOLDOWN（不探测）
 * - COOLDOWN 已过期               -> UNKNOWN + probeRequired=true（重新验证）
 * - CRASH_BLACKLISTED             -> 恒 CRASH_BLACKLISTED（直到指纹变化或显式 reset；键含指纹，天然失效）
 *
 * 记录规则：probe 失败 -> PROBE 类别；load 失败 -> LOAD 类别；生成异常 -> GENERATION 类别
 * （同类别重复达阈值升 7d 冷却）；probe 成功 -> PROBE_OK；非错误生成完成 -> MODEL_OK（覆盖 PROBE_OK）。
 * 用户取消 / 超时 / 热停 / 准入拒绝 / 模板不支持 / 空输出**不记录**（这些不是 backend failure）。
 *
 * @param store 健康记录存储（JVM 单测注入内存替身）。
 * @param deviceFingerprint 设备/运行时指纹（[DeviceRuntimeFingerprint]；键的一部分，变化即新键）。
 * @param modelFingerprint 模型指纹默认值（config.json SHA-256 前 16 hex，见 [modelConfigFingerprint]）。
 *        真实调用方应按当前模型显式传入——模型切换即新键，旧记录自然失效。
 * @param probeRunner 隔离探测执行器；null = 纯查询/测试场景（探测跳过，保持 UNKNOWN 走 CPU 链）。
 * @param clock 单调时钟（cooldown 过期判定；默认 elapsedRealtime）。
 */
class BackendHealthCoordinator(
    private val store: BackendHealthRecordStore,
    private val deviceFingerprint: String,
    private val modelFingerprint: String = "",
    private val probeRunner: OpenClProbeRunner? = null,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    /**
     * 解析 OpenCL 健康决策（不触发探测）。
     *
     * @param modelFingerprint 当前模型指纹（默认取构造值；实际调用方按当前模型传入）。
     */
    suspend fun resolve(modelFingerprint: String = this.modelFingerprint): BackendHealthDecision {
        val key = gpuKey(modelFingerprint)
        val record = store.get(key)
        if (record == null) {
            return BackendHealthDecision(OpenClHealthState.UNKNOWN, probeRequired = true)
        }
        return when (record.state) {
            HealthState.PROBE_OK ->
                BackendHealthDecision(OpenClHealthState.PROBE_OK, probeRequired = false)
            HealthState.MODEL_OK ->
                BackendHealthDecision(OpenClHealthState.MODEL_OK, probeRequired = false)
            HealthState.CRASH_BLACKLISTED ->
                BackendHealthDecision(
                    OpenClHealthState.CRASH_BLACKLISTED,
                    probeRequired = false,
                    reason = "崩溃黑名单（直到指纹变化或显式重置）",
                )
            HealthState.COOLDOWN ->
                if (BackendHealthPolicy.shouldAttempt(record, clock())) {
                    // 冷却期已过：回 UNKNOWN 重新验证（探测成功后再入链）。
                    BackendHealthDecision(
                        OpenClHealthState.UNKNOWN,
                        probeRequired = true,
                        reason = "冷却期已过，重新探测验证",
                    )
                } else {
                    BackendHealthDecision(
                        OpenClHealthState.COOLDOWN,
                        probeRequired = false,
                        reason = "冷却中（跳过 OpenCL 尝试）",
                    )
                }
            HealthState.UNKNOWN -> BackendHealthDecision(OpenClHealthState.UNKNOWN, probeRequired = true)
        }
    }

    /**
     * 探测入口（LocalChatProvider 每轮调用）：先按持久记录解析；若 [BackendHealthDecision.probeRequired]
     * 且 probeRunner 可用，则同步跑一次隔离探测（5s 超时）并写 store，再按新记录重新解析返回。
     */
    suspend fun resolveForGpu(modelFingerprint: String = this.modelFingerprint): BackendHealthDecision {
        runProbeIfNeeded(modelFingerprint)
        return resolve(modelFingerprint)
    }

    /**
     * 仅当决策要求探测且 probeRunner 可用时执行一次探测并记录；返回探测后的健康状态
     * （无需探测 / 无 probeRunner 时返回当前决策状态，不启动探测进程）。
     */
    suspend fun runProbeIfNeeded(modelFingerprint: String = this.modelFingerprint): OpenClHealthState {
        val decision = resolve(modelFingerprint)
        val runner = probeRunner
        if (!decision.probeRequired || runner == null) return decision.state
        val result = runner.runProbe()
        return if (result.success) {
            afterProbeSuccess(modelFingerprint)
            OpenClHealthState.PROBE_OK
        } else {
            afterProbeFailure(modelFingerprint)
            OpenClHealthState.COOLDOWN
        }
    }

    /** 探测成功：写 PROBE_OK（覆盖旧记录；旧 cooldown/黑名单不再生效）。 */
    suspend fun afterProbeSuccess(modelFingerprint: String = this.modelFingerprint) {
        store.update(gpuKey(modelFingerprint)) {
            BackendHealthPolicy.recordOk(HealthState.PROBE_OK)
        }
    }

    /** 探测失败：记 PROBE 类别失败（24h 冷却）。 */
    suspend fun afterProbeFailure(modelFingerprint: String = this.modelFingerprint) {
        store.update(gpuKey(modelFingerprint)) {
            BackendHealthPolicy.afterFailure(it, HealthFailureClass.PROBE, clock())
        }
    }

    /** 模型加载失败：记 LOAD 类别失败（24h 冷却）。 */
    suspend fun afterLoadFailure(
        backend: BackendType,
        variant: RuntimeVariant,
        modelFingerprint: String = this.modelFingerprint,
    ) {
        store.update(key(backend, variant, modelFingerprint)) {
            BackendHealthPolicy.afterFailure(it, HealthFailureClass.LOAD, clock())
        }
    }

    /** 生成异常：记 GENERATION 类别失败（首次 24h，同类别重复达阈值 7d）。 */
    suspend fun afterGenerationFailure(
        backend: BackendType,
        variant: RuntimeVariant,
        modelFingerprint: String = this.modelFingerprint,
    ) {
        store.update(key(backend, variant, modelFingerprint)) {
            BackendHealthPolicy.afterFailure(it, HealthFailureClass.GENERATION, clock())
        }
    }

    /** 非错误生成完成：升 MODEL_OK（覆盖 PROBE_OK 及更低状态）。 */
    suspend fun markModelOk(
        backend: BackendType,
        variant: RuntimeVariant,
        modelFingerprint: String = this.modelFingerprint,
    ) {
        store.update(key(backend, variant, modelFingerprint)) {
            BackendHealthPolicy.recordOk(HealthState.MODEL_OK)
        }
    }

    private fun gpuKey(modelFingerprint: String): BackendHealthKey =
        key(BackendType.MNN_GPU, RuntimeVariant.OPENCL, modelFingerprint)

    private fun key(backend: BackendType, variant: RuntimeVariant, modelFingerprint: String): BackendHealthKey =
        BackendHealthStore.keyFor(deviceFingerprint, modelFingerprint, backend, variant)

    companion object {
        /** 健康记录策略版本（指纹组成部分：策略变更 -> 新指纹 -> 旧记录失效）。 */
        private const val POLICY_SCHEMA = "1"

        /**
         * 健康键设备指纹部件（final review I2）：Build 身份（厂商/型号/系统指纹/SoC/ABI）+ 策略版本，
         * **不含 native 身份**（mnnCommit/nativeBuildId）。缺失字段留空不参与哈希
         * （[DeviceRuntimeFingerprint] 会过滤 blank 值）。系统 OTA / 驱动 / 应用更新 -> 指纹变化 ->
         * 健康键变化，旧黑名单与基准自然失效；native 重建（mnnCommit/nativeBuildId 变化）**不**改变
         * 健康键——与 [BackendHealthStore] KDoc 的键粒度语义一致（旧构建的失败教训仍适用于新构建）。
         * internal 供 JVM 单测断言 native 身份不进健康指纹。
         */
        internal fun healthFingerprintParts(): Map<String, String> = buildMap {
            // JVM 单测里 android.jar 桩的 Build 字段为 null——按 KDoc「缺失字段留空不参与哈希」兜底。
            put("manufacturer", Build.MANUFACTURER ?: "")
            put("model", Build.MODEL ?: "")
            put("osFingerprint", Build.FINGERPRINT ?: "")
            put(
                "soc",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
                } else {
                    ""
                },
            )
            put("abi", Build.SUPPORTED_ABIS?.firstOrNull() ?: "")
            put("policySchema", POLICY_SCHEMA)
        }

        /** 健康键设备指纹（final review I2）：仅 [healthFingerprintParts]，不含 native 身份——
         * 健康记录跨 native 重建存活。AppContainer 构造 [BackendHealthCoordinator] 时使用。 */
        fun healthDeviceFingerprintOf(): String =
            DeviceRuntimeFingerprint.compute(healthFingerprintParts())

        /**
         * 设备/运行时指纹：Build 身份 + native 栈身份（MNN commit/buildId）+ 策略版本。
         * **供认证键使用**（[com.rhodesisland.terminal.llm.benchmark.InferenceCertificationStore.certKey]
         * 的 device 分量，Task 6 M-3 与落盘侧同源）：native 身份另经 certKey 的显式
         * nativeBuildId/mnnCommit 分量绑定，native 重建即认证失效——与本函数含 native 身份一致。
         * 健康键请用 [healthDeviceFingerprintOf]（不含 native 身份，语义分歧见 [BackendHealthStore] KDoc）。
         */
        fun deviceFingerprintOf(): String = DeviceRuntimeFingerprint.compute(
            buildMap {
                putAll(healthFingerprintParts())
                put("mnnCommit", MnnBridge.runtimeInfo?.mnnCommit ?: "")
                put("nativeBuildId", MnnBridge.runtimeInfo?.nativeBuildId ?: "")
            },
        )
    }
}

/**
 * 模型指纹：config.json 内容 SHA-256 前 16 位 hex（Task 3）。模型替换 / 模板或采样配置变更 ->
 * 新指纹 -> 健康记录键变化 -> 旧记录自然失效。InferenceProfileResolver 的 sha256 为 private
 * 不可复用，故此处提供独立小工具（LocalChatProvider 与 BackendManager 共用）。
 */
fun modelConfigFingerprint(modelPath: String): String {
    val bytes = runCatching { File(modelPath).readBytes() }.getOrNull() ?: return ""
    return sha256Hex(bytes).take(16)
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
