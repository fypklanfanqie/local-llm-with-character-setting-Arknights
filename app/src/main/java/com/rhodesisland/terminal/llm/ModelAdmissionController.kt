package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.llm.profile.DowngradeReason
import kotlin.math.max

/**
 * 模型/资源准入控制器（Task 13 + Task 15 口径修正）。
 *
 * - 下载存储准入：required = bundle + 合并 headroom + runtime-cache reserve + max(512MiB, 10% bundle)，
 *   与磁盘实际可用量比对。
 * - 模型加载 RAM 准入：把「进程总足迹」作为需求侧统一计量，避免双重计数——
 *   `estimatedTotal = 当前进程基线 PSS + 权重工作集 + KV(候选 context) + 激活预留 + 后端预留`，
 *   再与「同一模型历史实测峰值 PSS」（若存在，已含权重/KV 全部）取较大值；
 *   与系统可用预算 `availMem - threshold - (lowMemory 余量)` 比对。解析顺序：降 context 档 ->
 *   拒绝。**不改用户配置**（只降本次实际 context）。
 *
 * 纯逻辑（注入量），可 JVM 测试；不覆盖用户配置的 context（只降 actualContext，不改配置）。
 */
object ModelAdmissionController {

    const val MIN_CONTEXT_STEP = 512
    const val STORAGE_HEADROOM_FIXED_BYTES = 512L * 1024 * 1024   // 512 MiB
    const val STORAGE_HEADROOM_FRACTION = 0.10                    // 10% bundle

    sealed interface AdmissionDecision {
        data class Allowed(val contextTokens: Int) : AdmissionDecision
        data class Downgraded(val actualContext: Int, val reasons: List<DowngradeReason>) : AdmissionDecision
        data class Rejected(val userMessage: String, val details: Map<String, Long>) : AdmissionDecision
    }

    // ===== 下载存储准入 =====

    /** 所需存储 = bundle + 合并 headroom + runtime-cache reserve + max(512MiB, 10% bundle)。 */
    fun storageRequiredBytes(
        bundleBytes: Long,
        mergeHeadroomBytes: Long = 0L,
        runtimeCacheReserveBytes: Long = 0L,
    ): Long = bundleBytes + mergeHeadroomBytes + runtimeCacheReserveBytes +
        max(STORAGE_HEADROOM_FIXED_BYTES, (bundleBytes * STORAGE_HEADROOM_FRACTION).toLong())

    /** 返回空 = 空间足够；否则 Rejected（含 required/available 供 UI 展示）。 */
    fun assessStorage(
        bundleBytes: Long,
        availableBytes: Long,
        mergeHeadroomBytes: Long = 0L,
        runtimeCacheReserveBytes: Long = 0L,
    ): AdmissionDecision {
        val required = storageRequiredBytes(bundleBytes, mergeHeadroomBytes, runtimeCacheReserveBytes)
        return if (availableBytes >= required) {
            AdmissionDecision.Allowed(contextTokens = 0)
        } else {
            AdmissionDecision.Rejected(
                userMessage = "存储空间不足，无法下载模型",
                details = mapOf("requiredBytes" to required, "availableBytes" to availableBytes),
            )
        }
    }

    // ===== 模型加载 RAM 准入 =====

    data class MemoryInputs(
        /** 权重工作集估算（无实测记录时用模型包字节数作保守上界；mmap 后常驻可能更小）。 */
        val weightWorkingSetBytes: Long,
        /** 用户配置的上下文长度（只降本次 actual，不改配置）。 */
        val configuredContext: Int,
        /** 给定候选 context 的 KV 缓存估算（GQA-aware；维度未知时返回 0）。 */
        val kvBytesForContext: (Int) -> Long,
        /** 推理激活等瞬态内存预留。 */
        val activationReserveBytes: Long,
        /** 后端/驱动预留（OpenCL 驱动缓冲与图编译远大于 CPU）。 */
        val backendReserveBytes: Long,
        /** 当前进程基线 PSS（模型加载前实测；未知为 0——大模型下权重/KV 占主导）。 */
        val currentProcessPssBytes: Long,
        /** 同一模型的历史实测峰值总 PSS（已含权重/KV 等全部；null = 无实测记录）。
         *  与估算取较大值（保守），绝不在估算之外再叠加，避免双重计数。 */
        val priorMeasuredTotalPssBytes: Long?,
        val availMemBytes: Long,
        val thresholdBytes: Long,
        val lowMemory: Boolean,
        /** 是否同模型已驻留（权重已在 [currentProcessPssBytes] 内，不应重复计入；热复用双重计数修复）。 */
        val modelAlreadyResident: Boolean = false,
    )

    /** context 降档序列（从配置值起逐级减半，最低 [MIN_CONTEXT_STEP]）。 */
    fun contextSteps(configured: Int): List<Int> {
        val steps = mutableListOf<Int>()
        var c = configured
        while (c >= MIN_CONTEXT_STEP) {
            steps += c
            c /= 2
        }
        return steps
    }

    fun decideMemory(inputs: MemoryInputs): AdmissionDecision {
        // 系统可用预算：当前可用内存 - 低内存阈值 -（lowMemory 时再留 1/4 余量）。
        val guard = if (inputs.lowMemory) inputs.availMemBytes / 4 else 0L
        val budget = saturatingSub(inputs.availMemBytes - inputs.thresholdBytes, guard)
        if (budget <= 0L) {
            return AdmissionDecision.Rejected(
                userMessage = "可用内存不足，无法加载模型",
                details = mapOf("availableBytes" to budget),
            )
        }

        // 降 context 档，直到「进程总足迹估算」放得进预算。
        for (ctx in contextSteps(inputs.configuredContext)) {
            val incremental = incrementalBytes(inputs, ctx)
            val estimatedTotal = saturatingAdd(inputs.currentProcessPssBytes, incremental)
            // 与历史实测峰值取较大值（保守校准），不叠加——避免把「含模型的峰值 PSS」再减一次。
            val footprint = inputs.priorMeasuredTotalPssBytes?.let { maxOf(estimatedTotal, it) } ?: estimatedTotal
            if (footprint <= budget) {
                return if (ctx == inputs.configuredContext) {
                    AdmissionDecision.Allowed(contextTokens = ctx)
                } else {
                    AdmissionDecision.Downgraded(
                        actualContext = ctx,
                        reasons = listOf(DowngradeReason.MEMORY),
                    )
                }
            }
        }

        // 最小 context 仍放不下：拒绝（含 min-context 下的足迹与预算，供调用方拼友好文案）。
        val minCtx = contextSteps(inputs.configuredContext).minOrNull() ?: MIN_CONTEXT_STEP
        val minIncremental = incrementalBytes(inputs, minCtx)
        val minFootprint = saturatingAdd(inputs.currentProcessPssBytes, minIncremental)
        return AdmissionDecision.Rejected(
            userMessage = "模型过大或上下文过长，无法在可用内存下运行",
            details = mapOf(
                "requiredBytes" to (inputs.priorMeasuredTotalPssBytes?.let { maxOf(minFootprint, it) } ?: minFootprint),
                "availableBytes" to budget,
                "minContext" to minCtx.toLong(),
            ),
        )
    }

    /**
     * 给定候选 context 的增量需求（权重/KV/激活/后端预留）。
     * 同模型已驻留时，权重已在 [MemoryInputs.currentProcessPssBytes] 内，不再重复计入——
     * 修复「热复用把已驻留权重再算一遍」导致的误降级/误拒；KV 仍按候选 context 计入
     * （context 未变时轻微高估，稳定优先可接受）。
     */
    private fun incrementalBytes(inputs: MemoryInputs, context: Int): Long {
        val kv = inputs.kvBytesForContext(context)
        return if (inputs.modelAlreadyResident) {
            saturatingAdd(kv, inputs.activationReserveBytes, inputs.backendReserveBytes)
        } else {
            saturatingAdd(inputs.weightWorkingSetBytes, kv, inputs.activationReserveBytes, inputs.backendReserveBytes)
        }
    }

    /** 饱和加法：溢出收敛到 Long.MAX_VALUE（防止估算越界变负导致误判可运行）。 */
    fun saturatingAdd(vararg values: Long): Long {
        var sum = 0L
        for (v in values) {
            if (v <= 0L) continue
            sum = if (sum > Long.MAX_VALUE - v) Long.MAX_VALUE else sum + v
        }
        return sum
    }

    /** 饱和减法：下溢收敛到 0。 */
    fun saturatingSub(a: Long, b: Long): Long = when {
        a <= b -> 0L
        b <= 0L -> a
        else -> a - b
    }
}
