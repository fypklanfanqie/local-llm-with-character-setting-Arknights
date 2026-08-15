package com.rhodesisland.terminal.llm.benchmark

import com.rhodesisland.terminal.llm.backend.BackendType

/**
 * 实验促进门禁（Task 15 Step 1）。
 *
 * 候选配置（如 lookahead / 线程数 / OpenCL 变体）相对基线能否 promotion：
 * - 正确性优先（UTF-8/EOS/重复/KV 前缀无失配）；
 * - 至少 ≥10% median decode 提升（或定义 TTFT 收益）；
 * - TTFT 与峰值 PSS 的劣化有界（≤30%）；
 * - 需冷启样本（拒绝热/噪声/单样本）。
 *
 * Task 6 认证衔接：Promote 决策经 [InferenceCertificationStore.toCertifiedOptions] 生成该
 * device+model+variant+native 组合的认证记录并落盘（基准触发与 UI 入口见 Task 7）；
 * [com.rhodesisland.terminal.llm.profile.InferenceProfileResolver] 启用 lookahead / 多 token 步进前
 * 按组合查证该认证——没有基准证据，配置不全局启用。
 *
 * Task 4（MNN runtime 晋级门禁）：[evaluateRuntime] 专门用于候选 MNN runtime / GPU 优化路径
 * 相对 pinned baseline 的晋级判定，在 [evaluate] 的性能门禁之上追加：
 * - 候选必须是**不同且非空白**的 native 身份（mnnCommit/nativeBuildId）；
 * - `SECOND_TURN_KV_REUSE` 无回归；
 * - 空响应率必须为 0；
 * - GPU 候选的全部样本必须实际跑在 [BackendType.MNN_GPU]，任何 CPU fallback 样本
 *   不得计入「GPU 更快」的证据。
 */
data class BenchmarkSample(
    val decodeTpsMedian: Float,
    val ttftMsMedian: Float? = null,
    /** 完整 prefill 吞吐（tokens/s）中位数（Task 15：仅纳入无 KV 复用污染的完整 prefill 样本）。 */
    val prefillTpsMedian: Float? = null,
    val peakPssMb: Float? = null,
    val sampleCount: Int,
    val hotStart: Boolean = false,
    /** UTF-8 完整 / EOS 正常 / 无复读 / KV 前缀无失配 的总体正确性。 */
    val correctnessOk: Boolean = true,
)

/**
 * Runtime/GPU 晋级候选（Task 4 Step 5）。
 *
 * 在 [BenchmarkSample] 之上携带 native 身份与可靠性/证据维度：
 * - [mnnCommit] / [nativeBuildId]：候选构建身份（必须与基线不同且非空白）。
 * - [actualBackendCounts]：样本级实际后端分布（backend name -> 样本数）；GPU 候选要求
 *   `[BackendType.MNN_GPU].name` 计数 == [sample.sampleCount]，混入 CPU fallback 即证据无效。
 * - [kvReuseRate]：`SECOND_TURN_KV_REUSE` 场景的 KV 复用率（可为 null=未测）。
 * - [emptyResponseRate]：`EMPTY_RESPONSE_CHECK` 的空响应率（晋级要求为 0）。
 * - [isGpuCandidate]：是否按 GPU 候选判定（true 时要求全部 decoded 样本实际跑在 [BackendType.MNN_GPU]；
 *   全回退 / 混入 CPU fallback 的样本不得作为「GPU 更快」的证据；默认 false 保持既有 CPU 候选不受影响）。
 */
data class RuntimeBenchmarkCandidate(
    val sample: BenchmarkSample,
    val mnnCommit: String,
    val nativeBuildId: String,
    val actualBackendCounts: Map<String, Int>,
    val kvReuseRate: Float?,
    val emptyResponseRate: Float,
    val isGpuCandidate: Boolean = false,
)

sealed interface PromotionDecision {
    data object Promote : PromotionDecision
    data class Reject(val reasons: List<String>) : PromotionDecision
}

object ExperimentalPromotionPolicy {

    const val MIN_DECODE_IMPROVEMENT = 1.10f   // ≥10% decode 提升
    const val MAX_TTFT_REGRESSION = 1.30f      // TTFT 劣化 ≤30%
    const val MAX_PSS_REGRESSION = 1.30f       // 峰值 PSS 劣化 ≤30%
    const val MIN_SAMPLES = 3
    /** Runtime 晋级要求空响应率必须为 0（可靠性不容许空输出）。 */
    const val MAX_EMPTY_RESPONSE_RATE = 0.0f

    fun evaluate(baseline: BenchmarkSample, candidate: BenchmarkSample): PromotionDecision {
        val reasons = mutableListOf<String>()

        if (!candidate.correctnessOk) reasons += "候选正确性校验未通过（UTF-8/EOS/复读/KV 失配）"
        if (candidate.hotStart || baseline.hotStart) reasons += "热启动样本无效，需冷启重测"
        if (candidate.sampleCount < MIN_SAMPLES || baseline.sampleCount < MIN_SAMPLES) {
            reasons += "样本数不足（需 ≥$MIN_SAMPLES，候选=${candidate.sampleCount}，基线=${baseline.sampleCount}）"
        }
        if (candidate.decodeTpsMedian < baseline.decodeTpsMedian * MIN_DECODE_IMPROVEMENT) {
            reasons += "decode 提升不足 10%（候选=${candidate.decodeTpsMedian} vs 基线=${baseline.decodeTpsMedian}）"
        }
        if (candidate.ttftMsMedian != null && baseline.ttftMsMedian != null &&
            candidate.ttftMsMedian > baseline.ttftMsMedian * MAX_TTFT_REGRESSION
        ) {
            reasons += "TTFT 劣化超 30%（候选=${candidate.ttftMsMedian} vs 基线=${baseline.ttftMsMedian}）"
        }
        if (candidate.peakPssMb != null && baseline.peakPssMb != null &&
            candidate.peakPssMb > baseline.peakPssMb * MAX_PSS_REGRESSION
        ) {
            reasons += "峰值 PSS 劣化超 30%（候选=${candidate.peakPssMb} vs 基线=${baseline.peakPssMb}）"
        }

        return if (reasons.isEmpty()) PromotionDecision.Promote else PromotionDecision.Reject(reasons)
    }

    /**
     * Runtime/GPU 候选晋级门禁（Task 4 Step 5/8）。
     *
     * @param baseline 基线样本（pinned runtime 同象限）。
     * @param baselineMnnCommit / [baselineNativeBuildId] 基线 native 身份（握手；空白=无证据）。
     * @param baselineKvReuseRate `SECOND_TURN_KV_REUSE` 基线复用率（null=未测/不比较）。
     * @param candidate 候选样本 + 身份 + 证据维度。
     */
    fun evaluateRuntime(
        baseline: BenchmarkSample,
        baselineMnnCommit: String,
        baselineNativeBuildId: String,
        baselineKvReuseRate: Float? = null,
        candidate: RuntimeBenchmarkCandidate,
    ): PromotionDecision {
        val reasons = mutableListOf<String>()

        // 1. 候选必须为「不同且非空白」的 native 身份。
        if (candidate.mnnCommit.isBlank() || candidate.nativeBuildId.isBlank()) {
            reasons += "候选 native 身份缺失（mnnCommit/nativeBuildId 空白）"
        }
        if (baselineMnnCommit.isBlank() || baselineNativeBuildId.isBlank()) {
            reasons += "基线 native 身份缺失"
        }
        if (candidate.mnnCommit.isNotBlank() && candidate.nativeBuildId.isNotBlank() &&
            candidate.mnnCommit == baselineMnnCommit && candidate.nativeBuildId == baselineNativeBuildId
        ) {
            reasons += "候选身份与基线相同（mnnCommit/nativeBuildId 未变化，无升级价值）"
        }

        // 2. 正确性 / KV 复用 / 空响应（可靠性，Step 8 晋级要求）。
        if (!candidate.sample.correctnessOk) reasons += "候选正确性校验未通过（UTF-8/EOS/复读/KV 失配）"
        if (baselineKvReuseRate != null && candidate.kvReuseRate != null &&
            candidate.kvReuseRate < baselineKvReuseRate
        ) {
            reasons += "KV 复用率回归（候选=${candidate.kvReuseRate} vs 基线=${baselineKvReuseRate}）"
        }
        if (candidate.emptyResponseRate > MAX_EMPTY_RESPONSE_RATE) {
            reasons += "候选空响应率过高（${candidate.emptyResponseRate} > $MAX_EMPTY_RESPONSE_RATE）"
        }

        // 3. GPU 证据：声称走 GPU 的候选，全部样本必须实际跑在 MNN_GPU；任一 CPU fallback
        //    样本不得计入「GPU 更快」的证据。
        val gpuSamples = candidate.actualBackendCounts[BackendType.MNN_GPU.name] ?: 0
        if (gpuSamples > 0 && gpuSamples != candidate.sample.sampleCount) {
            reasons += "GPU 候选混入非 GPU 样本（MNN_GPU=$gpuSamples / 总样本=${candidate.sample.sampleCount}，实际后端=${candidate.actualBackendCounts}）"
        }
        // 全回退洞：GPU 意图候选若 0 个 GPU 样本（全 CPU fallback），上面「>0」条件不拦，会用 CPU decode
        // 数据通过 ≥1.10× 门禁被当作 GPU 收益晋级。isGpuCandidate=true 时要求 MNN_GPU 计数 == 总样本数，
        // 全回退不可作为 GPU 收益证据。
        if (candidate.isGpuCandidate && candidate.actualBackendCounts[BackendType.MNN_GPU.name] != candidate.sample.sampleCount) {
            reasons += "GPU 候选实际 GPU 样本数不足（全回退不可作为 GPU 收益证据）"
        }

        // 4. 性能门禁（与 evaluate 同阈值）。
        if (candidate.sample.hotStart || baseline.hotStart) reasons += "热启动样本无效，需冷启重测"
        if (candidate.sample.sampleCount < MIN_SAMPLES || baseline.sampleCount < MIN_SAMPLES) {
            reasons += "样本数不足（需 ≥$MIN_SAMPLES，候选=${candidate.sample.sampleCount}，基线=${baseline.sampleCount}）"
        }
        if (candidate.sample.decodeTpsMedian < baseline.decodeTpsMedian * MIN_DECODE_IMPROVEMENT) {
            reasons += "decode 提升不足 10%（候选=${candidate.sample.decodeTpsMedian} vs 基线=${baseline.decodeTpsMedian}）"
        }
        candidate.sample.ttftMsMedian?.let { ct ->
            baseline.ttftMsMedian?.let { bt ->
                if (ct > bt * MAX_TTFT_REGRESSION) {
                    reasons += "TTFT 劣化超 30%（候选=$ct vs 基线=$bt）"
                }
            }
        }
        candidate.sample.peakPssMb?.let { cp ->
            baseline.peakPssMb?.let { bp ->
                if (cp > bp * MAX_PSS_REGRESSION) {
                    reasons += "峰值 PSS 劣化超 30%（候选=$cp vs 基线=$bp）"
                }
            }
        }

        return if (reasons.isEmpty()) PromotionDecision.Promote else PromotionDecision.Reject(reasons)
    }

    // ===== Task 15：prefill/TTFT 目标晋级（GPU prefill 优化实验用）=====

    /** PREFILL_TTFT 目标的必要收益：完整 prefill 吞吐或 TTFT 提升 ≥ 该比例。 */
    const val MIN_PREFILL_IMPROVEMENT = 1.10f

    /**
     * 以「完整 prefill / 首字（TTFT）」为目标的晋级判定（GPU prefill 优化、OpenCL 配置/候选
     * runtime 实验；**只产出证据，不自动升级 native bundle**）。
     *
     * 门禁：
     * - 两侧必须都有完整 prefill 证据（[BenchmarkSample.prefillTpsMedian] 与 TTFT 均非 null；
     *   缺失 = 证据不足，拒绝）；
     * - prefill 吞吐或 TTFT 至少提升 [MIN_PREFILL_IMPROVEMENT]（二者其一达标即可，目标明确）；
     * - decode 不得劣化超过 [MAX_TTFT_REGRESSION] 容差（不牺牲解码换首字）；
     * - 正确性 / 冷启样本数 / 峰值 PSS 有界与 [evaluate] 一致。
     */
    fun evaluatePrefill(baseline: BenchmarkSample, candidate: BenchmarkSample): PromotionDecision {
        val reasons = mutableListOf<String>()

        if (!candidate.correctnessOk || !baseline.correctnessOk) reasons += "正确性校验未通过（UTF-8/EOS/复读/KV 失配）"
        if (candidate.hotStart || baseline.hotStart) reasons += "热启动样本无效，需冷启重测"
        if (candidate.sampleCount < MIN_SAMPLES || baseline.sampleCount < MIN_SAMPLES) {
            reasons += "样本数不足（需 ≥$MIN_SAMPLES，候选=${candidate.sampleCount}，基线=${baseline.sampleCount}）"
        }
        val bp = baseline.prefillTpsMedian
        val cp = candidate.prefillTpsMedian
        val bt = baseline.ttftMsMedian
        val ct = candidate.ttftMsMedian
        if (bp == null || cp == null || bt == null || ct == null) {
            reasons += "prefill 证据缺失（需完整 prefill 样本的 prefillTps 与 TTFT；KV 复用污染样本不计）"
        } else {
            val prefillGain = cp / bp
            val ttftGain = bt / ct  // TTFT 变小 = 收益
            if (prefillGain < MIN_PREFILL_IMPROVEMENT && ttftGain < MIN_PREFILL_IMPROVEMENT) {
                reasons += "prefill 提升不足（prefill ${cp} vs ${bp} tps；TTFT ${ct} vs ${bt} ms）"
            }
            if (ct > bt * MAX_TTFT_REGRESSION) {
                reasons += "TTFT 劣化超 30%（候选=$ct vs 基线=$bt）"
            }
        }
        // 不牺牲解码换首字：decode 可略降，但不超过 30% 容差。
        if (candidate.decodeTpsMedian < baseline.decodeTpsMedian / MAX_TTFT_REGRESSION) {
            reasons += "decode 劣化超 30%（候选=${candidate.decodeTpsMedian} vs 基线=${baseline.decodeTpsMedian}）"
        }
        candidate.peakPssMb?.let { cpss ->
            baseline.peakPssMb?.let { bpss ->
                if (cpss > bpss * MAX_PSS_REGRESSION) {
                    reasons += "峰值 PSS 劣化超 30%（候选=$cpss vs 基线=$bpss）"
                }
            }
        }

        return if (reasons.isEmpty()) PromotionDecision.Promote else PromotionDecision.Reject(reasons)
    }
}
