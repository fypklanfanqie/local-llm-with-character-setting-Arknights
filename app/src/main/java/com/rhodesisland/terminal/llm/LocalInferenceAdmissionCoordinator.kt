package com.rhodesisland.terminal.llm

import android.app.ActivityManager
import android.content.Context
import com.rhodesisland.terminal.llm.ModelAdmissionController.AdmissionDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 每轮推理前的内存准入协调器（Task 15）。
 *
 * Android 适配层：读取 [ActivityManager.MemoryInfo]（availMem/threshold/lowMemory）与本进程 PSS，
 * 调用纯逻辑 [ModelAdmissionController.decideMemory]；维护「同模型最近实测峰值 PSS」用于后续
 * 校准（[recordPeakPss] 由调用方在生成结束后回填）。
 *
 * 决策语义（稳定优先）：
 * - 足够：保持用户配置的 context；
 * - 不足：仅本次把 context 逐级减半（最低 512），**不写用户设置**；
 * - 512 仍不足：返回 [Rejected]（调用方抛 [MemoryAdmissionException] 展示友好错误）。
 *
 * @param memoryInfoProvider 可注入的 MemoryInfo 读取（JVM 测试注入 fake；生产用系统真实值）。
 * @param pssProvider 可注入的进程 PSS 读取（字节；null=未知）。
 */
class LocalInferenceAdmissionCoordinator(
    private val memoryInfoProvider: () -> ActivityManager.MemoryInfo,
    private val pssProvider: () -> Long?,
) {
    constructor(context: Context) : this(
        memoryInfoProvider = {
            ActivityManager.MemoryInfo().also { mi ->
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.getMemoryInfo(mi)
            }
        },
        pssProvider = { ProcessMemorySampler.currentProcessPssBytes(context) },
    )

    private val peakPssByModel = ConcurrentHashMap<String, Long>()

    /** 记录一次生成后的实测峰值 PSS（同模型后续准入作为足迹下限；<=0 忽略）。 */
    fun recordPeakPss(modelId: String, peakPssBytes: Long?) {
        if (peakPssBytes == null || peakPssBytes <= 0L) return
        peakPssByModel.merge(modelId, peakPssBytes, ::maxOf)
    }

    /**
     * 执行一次准入。返回 [AdmissionDecision.Allowed]/[Downgraded] 表示可运行（含本次实际 context）；
     * [Rejected] 表示最小 context 也无法容纳（调用方应抛 [MemoryAdmissionException]）。
     *
     * @param weightWorkingSetBytes 权重工作集估算（内置模型用 [com.rhodesisland.terminal.data.model.ModelInfo.size]；
     *        未知模型传 0——准入退化为只查 KV/预留）。
     * @param kvBytesForContext 候选 context 的 KV 估算（维度未知返回 0）。
     * @param backendReserveBytes 后端/驱动预留：OpenCL 明显大于 CPU（调用方按是否可能走 GPU 选择）。
     */
    suspend fun admit(
        modelId: String,
        configuredContext: Int,
        weightWorkingSetBytes: Long,
        kvBytesForContext: (Int) -> Long,
        activationReserveBytes: Long = DEFAULT_ACTIVATION_RESERVE_BYTES,
        backendReserveBytes: Long = CPU_BACKEND_RESERVE_BYTES,
        /** 同模型已驻留（权重已在当前 PSS 内），不再重复计入权重。 */
        modelAlreadyResident: Boolean = false,
    ): AdmissionDecision = withContext(Dispatchers.IO) {
        val mi = memoryInfoProvider()
        ModelAdmissionController.decideMemory(
            ModelAdmissionController.MemoryInputs(
                weightWorkingSetBytes = weightWorkingSetBytes,
                configuredContext = configuredContext,
                kvBytesForContext = kvBytesForContext,
                activationReserveBytes = activationReserveBytes,
                backendReserveBytes = backendReserveBytes,
                currentProcessPssBytes = pssProvider() ?: 0L,
                priorMeasuredTotalPssBytes = peakPssByModel[modelId],
                availMemBytes = mi.availMem,
                thresholdBytes = mi.threshold,
                lowMemory = mi.lowMemory,
                modelAlreadyResident = modelAlreadyResident,
            ),
        )
    }

    companion object {
        /** 激活/瞬态内存预留（估算值，实测后校准；9B 级模型 prefill 激活量级约数百 MB）。 */
        const val DEFAULT_ACTIVATION_RESERVE_BYTES = 256L * 1024 * 1024

        /** CPU 后端预留（图/内存分配器开销，估算值）。 */
        const val CPU_BACKEND_RESERVE_BYTES = 256L * 1024 * 1024

        /** OpenCL 后端预留（驱动缓冲、图编译缓存等明显大于 CPU；估算值）。 */
        const val OPENCL_BACKEND_RESERVE_BYTES = 768L * 1024 * 1024
    }
}

/**
 * 内存准入拒绝（最小 512 context 仍无法容纳时抛出）。
 *
 * 消息面向用户（经 [ChatViewModel] 的 errorMessage -> Snackbar 展示）；由
 * [LocalInferenceAdmissionCoordinator] 的调用方（LocalChatProvider）在 Rejected 时构造。
 */
class MemoryAdmissionException(message: String) : Exception(message)
