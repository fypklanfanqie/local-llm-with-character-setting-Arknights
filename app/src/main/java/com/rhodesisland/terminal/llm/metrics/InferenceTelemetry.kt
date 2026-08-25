package com.rhodesisland.terminal.llm.metrics

import com.rhodesisland.terminal.llm.backend.BackendType
import com.rhodesisland.terminal.llm.profile.InferencePerformanceMode
import com.rhodesisland.terminal.llm.thinking.ThinkingPolicyTelemetry
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * 推理遥测基线（Task 2）。
 *
 * 本文件定义一次性、跨任务复用的遥测数据模型与统计工具：
 * - [InferenceStage] / [CompletionReason]：生命周期阶段与终止原因（[CompletionReason] 供 Task 4 流式批处理使用，提前定义）。
 * - [InferenceSnapshot]：每次生成的**原子**实时快照，供 500ms 浮窗读取，避免在 overlay 线程并发调用 nativeGetMetrics。
 * - [InferenceTurnRecord]：每次生成的**最终**记录（冷/热加载、TTFT、prefill/decode、token 数、KV 复用、PSS、热状态、尝试轨迹等）。
 * - [BenchmarkSummary]：多轮基准的中位数/离散度汇总。
 * - [InferenceTelemetry]：原子存储，由 [com.rhodesisland.terminal.llm.backend.MnnBackend] 持有，在受控回调点写入。
 *
 * 设计约定：
 * - 用显式可空字段（`?`）而非哨兵常量表示「不可用」，杜绝 0/-1 歧义。
 * - 实时快照只承载 onToken 回调已算出的量（tokenCount/currentTps 等）；精确 native 指标仅在受控点（生成结束 finally）写入最终记录。
 * - overlay 永不调用 nativeGetMetrics；仅读 [InferenceTelemetry.snapshot]。
 */

/** 推理生命周期阶段。 */
enum class InferenceStage {
    VALIDATE,   // 模型/输入校验
    ADMIT,      // 准入决策（Task 13）
    PROBE,      // 后端探测（Task 10 OpenCL probe）
    LOAD,       // 模型加载（冷/热）
    PREFILL,    // prompt 前缀处理
    DECODE,     // 自回归解码
    FINALIZE,   // 收尾、释放
}

/** 生成终止原因。[POLICY_TRUNCATION]/[THERMAL_STOP] 供 Task 4/8 使用，提前定义以保证前向兼容。 */
enum class CompletionReason {
    EOS,                // 正常结束符
    MAX_TOKENS,         // 达到 maxTokens
    USER_CANCEL,        // 用户中断
    POLICY_TRUNCATION,  // 策略截断（窗口/预算）
    THERMAL_STOP,       // 过热降级停止
    TIMEOUT,            // 超时
    BACKEND_FAILURE,    // 后端错误
}

/**
 * 每次生成的实时快照（原子读取）。
 *
 * 字段全部为回调期已知量；不包含需并发读 native 的精确指标。
 *
 * @param generationId 本次生成唯一标识（与 [InferenceTurnRecord.generationId] 对应）。
 * @param stage 当前生命周期阶段。
 * @param requestedMode 用户/设置请求的性能模式（Task 6 解析前可能为 null）。
 * @param effectiveMode 实际生效模式（经热/电降级后，可能 != requestedMode）。
 * @param backend 实际后端类型。
 * @param tokenCount 已生成 token 数。
 * @param callbackCount onToken 回调次数（与 tokenCount 在 Task 2 下 1:1；Task 4 批处理后可能不等）。
 * @param callbackBytes 回调累计 UTF-8 字节数（用于核对流式拼接完整性）。
 * @param currentTps 当前解码速度（tokens/s，回调线程算出）；首 token 前为 null。
 * @param startedElapsedMs 生成起始 SystemClock.elapsedRealtime（ms）。
 * @param lastProgressElapsedMs 最近一次真实 token 进度的 elapsedRealtime；prefill 前等于 started。
 */
data class InferenceSnapshot(
    val generationId: String,
    val stage: InferenceStage,
    val requestedMode: InferencePerformanceMode?,
    val effectiveMode: InferencePerformanceMode?,
    val backend: BackendType?,
    val tokenCount: Int,
    val callbackCount: Int,
    val callbackBytes: Long,
    val currentTps: Float?,
    val startedElapsedMs: Long,
    val lastProgressElapsedMs: Long,
)

/**
 * 每次生成的最终记录（受控点写入）。
 *
 * 在生成结束的 finally 块中，由 nativeGetMetrics 的精确值 + 回调期累计量组装。
 * 用 kotlinx.serialization 持久化（JSON），便于基准库按指纹归档与跨重启对比。
 */
@Serializable
data class InferenceTurnRecord(
    val generationId: String,
    val requestedMode: InferencePerformanceMode?,
    val effectiveMode: InferencePerformanceMode?,
    val backend: BackendType?,
    val startedElapsedMs: Long,
    val endedElapsedMs: Long,
    /** 冷启动加载耗时（首次 mmap+load，ms）；热加载（mmap 缓存命中）为 null。 */
    val coldLoadMs: Long? = null,
    /** 热加载耗时（ms）；冷启动时为 null。 */
    val warmLoadMs: Long? = null,
    /** 首字延迟 TTFT（prompt 提交 -> 首 token，ms）。 */
    val ttftMs: Long? = null,
    /** prefill 阶段耗时（ms）。 */
    val prefillMs: Long? = null,
    /** decode 阶段耗时（ms）。 */
    val decodeMs: Long? = null,
    /** prompt token 数（MNN promptLen）。 */
    val promptTokens: Int = 0,
    /** 生成 token 数（MNN genLen）。 */
    val generatedTokens: Int = 0,
    /** prefill 吞吐（tokens/s）。 */
    val prefillTps: Float? = null,
    /** decode 吞吐（tokens/s，native 实测）。 */
    val decodeTps: Float? = null,
    /** 是否复用 KV cache（MNN reuseKv）。 */
    val kvReuse: Boolean? = null,
    /** 峰值 PSS（MB）。 */
    val peakPssMb: Long? = null,
    /** 热状态起始档位（0..N，设备口径）。 */
    val thermalStart: Int? = null,
    /** 热状态峰值档位。 */
    val thermalMax: Int? = null,
    /** 热状态结束档位。 */
    val thermalEnd: Int? = null,
    val completionReason: CompletionReason? = null,
    /** 后端切换/重试轨迹（如 NPU->GPU->CPU 回退链）。 */
    val attemptTrace: List<String> = emptyList(),
    /** 生效配置指纹（模型路径+线程数+上下文长度+模式等的哈希），供基准按配置归档。 */
    val configHash: String? = null,
    /** 降级原因列表（如 thermal、battery、admission 拒绝）。 */
    val downgradeReasons: List<String> = emptyList(),
    // ---- Task 15：内存准入的上下文降级（本次生成）----
    /** 用户配置的上下文长度；null = 与 [actualContextTokens] 相同（未降级）。 */
    val configuredContextTokens: Int? = null,
    /** 本次生成实际使用的上下文长度（内存准入降级后）。 */
    val actualContextTokens: Int? = null,
    // ---- Task 1 v2 观测（来自 native v2 摘要；旧 native/摘要缺失为 null）----
    /** `set_config(enable_thinking)` 是否被 native 接受（true=生效，false=回退模型默认）。 */
    val thinkingConfigAccepted: Boolean? = null,
    /** 检测到 `</think>` 的时刻（us，相对生成起点）；无思考段为 null。 */
    val reasoningEndUs: Long? = null,
    /** 首个非思考正文回调的时刻（us，相对生成起点）；无正文为 null。 */
    val firstBodyDeltaUs: Long? = null,
    /** native 实际生效的 decode 步长（clamp 后 1..4）；摘要缺失为 null。 */
    val decodeStepTokens: Int? = null,
    // ---- Task 2：思考请求 / 模板能力 / 分类结果（生成信封；分类结果由 MnnBackend 在生成
    //      finally 内收口并入本记录——取代原 provider 侧补记路径，见 MnnBackend.generateStreamMessages）----
    /** 本轮是否请求了深度思考（deepThinking 设置值）。 */
    val thinkingRequested: Boolean? = null,
    /** 模板能力枚举名（[com.rhodesisland.terminal.llm.template.ThinkingTemplateCapability]）。 */
    val templateCapability: String? = null,
    /** 思考效果枚举名（[com.rhodesisland.terminal.llm.template.ThinkingEffect]）。 */
    val thinkingEffective: String? = null,
    /** 空响应分类枚举名（[com.rhodesisland.terminal.llm.template.EmptyResponseClass]）。 */
    val emptyResponseClass: String? = null,
    /** 输出健全性分类枚举名（[com.rhodesisland.terminal.llm.template.OutputSanityDetector.SanityClass]）；
     *  null=未检测/旧记录。认证门禁据此拒绝乱码/复读样本（correctnessOk 的真实证据源）。 */
    val sanityClass: String? = null,
    // ---- Task 5：本地思考档位策略快照（由 provider 解析计划后一次构造透传；思考关闭/云端为 null）----
    /** 本地思考档位/复杂度/软目标/控制方式快照；不包含用户正文。 */
    val thinkingPolicy: ThinkingPolicyTelemetry? = null,
)

/**
 * 多轮基准汇总（中位数 + P95 + 离散度），只纳入冷态/合格样本。
 */
@Serializable
data class BenchmarkSummary(
    val medianTtftMs: Float? = null,
    val medianPrefillTps: Float? = null,
    val medianDecodeTps: Float? = null,
    val decodeStdDev: Float? = null,
    val peakPssMb: Long? = null,
    val maxThermalStatus: Int? = null,
    /** KV 复用率：kvReuse==true 的样本占比（0..1）。 */
    val kvReuseRate: Float? = null,
    /** 健全样本率：sanityClass==SANE 的占比（仅统计有检测值的样本；null=无任何检测证据）。 */
    val saneSampleRate: Float? = null,
    // ---- Task 5：P95 尾延迟/尾吞吐（>0 过滤口径与 median 一致）----
    /** P95 首字延迟（ms）。 */
    val p95TtftMs: Float? = null,
    /** P95 解码吞吐（tokens/s）。 */
    val p95DecodeTps: Float? = null,
)

// ----------------------------------------------------------------------------------
// 统计工具（纯函数，无 Android 依赖，便于 JVM 单测）
// ----------------------------------------------------------------------------------

/** [OutputSanityDetector.SanityClass.SANE] 的枚举名字符串（summarize 用；避免反向依赖 template 包）。 */
private const val SANE_CLASS_NAME = "SANE"

/** 算术平均；空列表返回 null。 */
fun mean(values: List<Float>): Float? {
    if (values.isEmpty()) return null
    var sum = 0.0
    for (v in values) sum += v
    return (sum / values.size).toFloat()
}

/** 中位数；偶数个取中间两数平均，空列表返回 null。 */
fun median(values: List<Float>): Float? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val n = sorted.size
    val mid = n / 2
    return if (n % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}

/**
 * 样本标准差（n-1 分母）。样本数 < 2 返回 null（无意义）。
 */
fun sampleStandardDeviation(values: List<Float>): Float? {
    if (values.size < 2) return null
    val avg = mean(values) ?: return null
    var sq = 0.0
    for (v in values) {
        val d = v - avg
        sq += d * d
    }
    return sqrt(sq / (values.size - 1)).toFloat()
}

/**
 * 第 95 百分位（升序 + 线性插值，Task 5 Step 6）。
 *
 * 口径：`pos = 0.95 * (n - 1)`，取 `sorted[floor(pos)]` 与 `sorted[ceil(pos)]` 按小数部分插值；
 * 单样本返回其本身，空列表返回 null——与 [median] 的「可空、不抛异常」风格一致。
 */
fun p95(values: List<Float>): Float? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val pos = 0.95 * (sorted.size - 1)
    val lo = pos.toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    val frac = (pos - lo).toFloat()
    return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
}

/** 由记录列表构建基准汇总。空列表返回全 null 字段。 */
fun summarize(records: List<InferenceTurnRecord>): BenchmarkSummary {
    if (records.isEmpty()) return BenchmarkSummary()
    val ttfts = records.mapNotNull { it.ttftMs }.filter { it > 0 }.map { it.toFloat() }
    val prefillTps = records.mapNotNull { it.prefillTps }.filter { it > 0 }
    val decodeTps = records.mapNotNull { it.decodeTps }.filter { it > 0 }
    val pss = records.mapNotNull { it.peakPssMb }
    val thermal = records.mapNotNull { it.thermalMax }
    val reuse = records.mapNotNull { it.kvReuse }
    val reuseRate = if (reuse.isEmpty()) null else reuse.count { it }.toFloat() / reuse.size
    val sanity = records.mapNotNull { it.sanityClass }
    val saneRate = if (sanity.isEmpty()) null else sanity.count { it == SANE_CLASS_NAME }.toFloat() / sanity.size
    return BenchmarkSummary(
        medianTtftMs = median(ttfts),
        medianPrefillTps = median(prefillTps),
        medianDecodeTps = median(decodeTps),
        decodeStdDev = sampleStandardDeviation(decodeTps),
        p95TtftMs = p95(ttfts),
        p95DecodeTps = p95(decodeTps),
        peakPssMb = pss.maxOrNull(),
        maxThermalStatus = thermal.maxOrNull(),
        kvReuseRate = reuseRate,
        saneSampleRate = saneRate,
    )
}

// ----------------------------------------------------------------------------------
// 原子遥测存储
// ----------------------------------------------------------------------------------

/**
 * 单次生成的可变累加器。
 *
 * 仅在推理线程（[com.rhodesisland.terminal.llm.backend.MnnBackend] 的 mnnMutex 串行段内）读写，
 * 不需额外同步；对外的可见性通过 [InferenceTelemetry.snapshotRef] 原子发布。
 */
private class ActiveGeneration(
    val generationId: String,
    val requestedMode: InferencePerformanceMode?,
    val effectiveMode: InferencePerformanceMode?,
    val backend: BackendType?,
    val startedElapsedMs: Long,
) {
    var firstTokenElapsedMs: Long? = null
    var tokenCount: Int = 0
    var callbackCount: Int = 0
    var callbackBytes: Long = 0L
    var lastTps: Float? = null
    var lastProgressElapsedMs: Long = startedElapsedMs

    fun snapshot(stage: InferenceStage): InferenceSnapshot = InferenceSnapshot(
        generationId = generationId,
        stage = stage,
        requestedMode = requestedMode,
        effectiveMode = effectiveMode,
        backend = backend,
        tokenCount = tokenCount,
        callbackCount = callbackCount,
        callbackBytes = callbackBytes,
        currentTps = lastTps,
        startedElapsedMs = startedElapsedMs,
        lastProgressElapsedMs = lastProgressElapsedMs,
    )
}

/**
 * 原子遥测存储。由 [com.rhodesisland.terminal.llm.backend.MnnBackend] 持有。
 *
 * 调用时序（单推理线程）：
 * 1. [beginGeneration]：建立本次生成上下文，发布 PREFILL 快照。
 * 2. [onDecodeToken]（onToken 回调内）：累加计数、记录首字时间、发布 DECODE 快照。
 * 3. [snapshot]（overlay 500ms 读线程）：原子读取，零 native 调用。
 * 4. [finalize]（finally 受控点）：用 nativeGetMetrics 精确值组装最终记录，清空快照。
 */
class InferenceTelemetry {
    private val snapshotRef = AtomicReference<InferenceSnapshot?>(null)
    private var active: ActiveGeneration? = null

    /** 是否正处于一次生成中（已 begin 未 finalize）。 */
    val isActive: Boolean get() = active != null

    /** 开始一次生成。 */
    fun beginGeneration(
        generationId: String,
        requestedMode: InferencePerformanceMode?,
        effectiveMode: InferencePerformanceMode?,
        backend: BackendType?,
        startedElapsedMs: Long,
    ) {
        active = ActiveGeneration(generationId, requestedMode, effectiveMode, backend, startedElapsedMs)
        snapshotRef.set(active!!.snapshot(InferenceStage.PREFILL))
    }

    /**
     * onToken 回调内更新解码进度并发布 DECODE 快照。
     *
     * @param tokenCount 当前累计 token 数（Task 4 批处理后取 native 实时 gen_len，与 [callbackCount] 解耦）。
     * @param callbackCount 当前累计回调次数（批次数）。
     * @param callbackBytes 当前累计回调 UTF-8 字节数。
     * @param currentTps 当前解码速度；首 token 前可传 null。
     * @param nowElapsedMs SystemClock.elapsedRealtime（ms），用于记录首字时间。
     */
    fun onDecodeToken(
        tokenCount: Int,
        callbackCount: Int,
        callbackBytes: Long,
        currentTps: Float?,
        nowElapsedMs: Long,
    ) {
        val g = active ?: return
        if (g.firstTokenElapsedMs == null) g.firstTokenElapsedMs = nowElapsedMs
        g.tokenCount = tokenCount
        g.callbackCount = callbackCount
        g.callbackBytes = callbackBytes
        g.lastTps = currentTps
        g.lastProgressElapsedMs = nowElapsedMs
        snapshotRef.set(g.snapshot(InferenceStage.DECODE))
    }

    /** overlay 读取入口：原子返回当前快照，不调用任何 native。 */
    fun snapshot(): InferenceSnapshot? = snapshotRef.get()

    /**
     * 受控点（生成结束 finally）组装最终记录并清空快照。
     *
     * @param nowElapsedMs 结束时刻 SystemClock.elapsedRealtime（ms）。
     * @param completionReason 终止原因。
     * @param nativeMetrics nativeGetMetrics 原始数组 [tps, prefillUs, decodeUs, promptLen, genLen, reuseKv]；可空。
     * @param peakPssMb / thermalStart / thermalMax / thermalEnd 可选的进程/热状态采样。
     * @param configHash / attemptTrace / downgradeReasons 配置与轨迹。
     * @return 本次生成记录；若无活跃生成返回 null。
     */
    fun finalize(
        nowElapsedMs: Long,
        completionReason: CompletionReason,
        nativeMetrics: FloatArray? = null,
        peakPssMb: Long? = null,
        thermalStart: Int? = null,
        thermalMax: Int? = null,
        thermalEnd: Int? = null,
        configHash: String? = null,
        attemptTrace: List<String> = emptyList(),
        downgradeReasons: List<String> = emptyList(),
        coldLoadMs: Long? = null,
        warmLoadMs: Long? = null,
        /** Task 1：native firstDeltaUs 换算的 TTFT（ms）；为空时回退 Kotlin 侧首回调时间。 */
        ttftMsOverride: Long? = null,
        // Task 1 v2：思考配置接受 / 思考边界 / 首正文时刻 / 生效 decode 步长（native v2 摘要；缺失为 null）。
        thinkingConfigAccepted: Boolean? = null,
        reasoningEndUs: Long? = null,
        firstBodyDeltaUs: Long? = null,
        decodeStepTokens: Int? = null,
        // Task 2：思考请求 / 模板能力 / 思考效果 / 空响应分类（生成信封透传；分类结果由 MnnBackend
        // 在 generateStreamMessages 的 finally 内收口并随本 finalize 并入记录——不再有 provider
        // 侧补记路径，避免加载期/首 attempt 前取消时分类写进上一轮记录）。
        thinkingRequested: Boolean? = null,
        templateCapability: String? = null,
        thinkingEffective: String? = null,
        emptyResponseClass: String? = null,
        // Wave 2：输出健全性分类（基准/生成旁路检测器产出；null=未检测）。
        sanityClass: String? = null,
        // Task 5：本地思考档位策略快照（单次透传；思考关闭/云端为 null）。
        thinkingPolicy: ThinkingPolicyTelemetry? = null,
        // Task 15：内存准入的上下文降级（配置值 -> 实际值；未降级传 null/等值）。
        configuredContextTokens: Int? = null,
        actualContextTokens: Int? = null,
    ): InferenceTurnRecord? {
        val g = active ?: run { snapshotRef.set(null); return null }
        val m = nativeMetrics
        val promptTokens = if (m != null && m.size > 3) m[3].toInt() else g.tokenCount
        val generatedTokens = if (m != null && m.size > 4) m[4].toInt() else g.tokenCount
        val decodeTps = if (m != null && m.isNotEmpty() && m[0] > 0f) m[0] else g.lastTps
        val prefillMs = if (m != null && m.size > 1 && m[1] > 0f) (m[1] / 1000f).toLong() else null
        val decodeMs = if (m != null && m.size > 2 && m[2] > 0f) (m[2] / 1000f).toLong() else null
        val kvReuse = if (m != null && m.size > 5) m[5].toInt() != 0 else null
        val prefillTps = if (prefillMs != null && promptTokens > 0 && prefillMs > 0L)
            promptTokens.toFloat() / (prefillMs / 1000f) else null

        val record = InferenceTurnRecord(
            generationId = g.generationId,
            requestedMode = g.requestedMode,
            effectiveMode = g.effectiveMode,
            backend = g.backend,
            startedElapsedMs = g.startedElapsedMs,
            endedElapsedMs = nowElapsedMs,
            coldLoadMs = coldLoadMs,
            warmLoadMs = warmLoadMs,
            ttftMs = ttftMsOverride ?: g.firstTokenElapsedMs?.let { it - g.startedElapsedMs },
            prefillMs = prefillMs,
            decodeMs = decodeMs,
            promptTokens = promptTokens,
            generatedTokens = generatedTokens,
            prefillTps = prefillTps,
            decodeTps = decodeTps,
            kvReuse = kvReuse,
            peakPssMb = peakPssMb,
            thermalStart = thermalStart,
            thermalMax = thermalMax,
            thermalEnd = thermalEnd,
            completionReason = completionReason,
            attemptTrace = attemptTrace,
            configHash = configHash,
            downgradeReasons = downgradeReasons,
            configuredContextTokens = configuredContextTokens,
            actualContextTokens = actualContextTokens,
            thinkingConfigAccepted = thinkingConfigAccepted,
            reasoningEndUs = reasoningEndUs,
            firstBodyDeltaUs = firstBodyDeltaUs,
            decodeStepTokens = decodeStepTokens,
            thinkingRequested = thinkingRequested,
            templateCapability = templateCapability,
            thinkingEffective = thinkingEffective,
            emptyResponseClass = emptyResponseClass,
            sanityClass = sanityClass,
            thinkingPolicy = thinkingPolicy,
        )
        active = null
        snapshotRef.set(null)
        return record
    }

    /** 清空（不产出记录），用于异常路径或 release。 */
    fun reset() {
        active = null
        snapshotRef.set(null)
    }
}
