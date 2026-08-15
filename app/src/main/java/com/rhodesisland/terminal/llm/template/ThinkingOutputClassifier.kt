package com.rhodesisland.terminal.llm.template

import com.rhodesisland.terminal.llm.metrics.CompletionReason
import com.rhodesisland.terminal.llm.metrics.InferenceStage

/**
 * 思考开关的实际效果（Task 2）。
 *
 * 与 [EmptyResponseClass] 一起回答「思考关闭是否生效 / 开关是否被模板忽略 / 是否真实后端失败」：
 * - [ENABLED]：请求了思考且观察到完整思考段（`</think>` 命中）。
 * - [DISABLED]：未请求思考且无思考段、有正常正文——关闭生效。
 * - [IGNORED_BY_TEMPLATE]：请求了思考但模板能力为 [ThinkingTemplateCapability.UNSUPPORTED]
 *   （模板无 `enable_thinking` 分支）且输出正常——开关被模板忽略。
 * - [THINKING_DISABLE_NOT_EFFECTIVE]：未请求思考但出现完整思考段——开关未生效（Task 2 Step 4 硬性要求）。
 * - [UNKNOWN]：信息不足（思考中截断、后端失败、空响应等）。
 */
enum class ThinkingEffect {
    ENABLED,
    DISABLED,
    IGNORED_BY_TEMPLATE,
    THINKING_DISABLE_NOT_EFFECTIVE,
    UNKNOWN,
}

/**
 * 空响应（无正文）分类（Task 2）。
 *
 * 推导：有正文 → [NONE]；有思考标签（完整或半截）无正文 → [THINK_ONLY]；仅有空白 → [WHITESPACE_ONLY]；
 * 零输出时按 [CompletionReason] 归类（[EOS_EMPTY] / [MAX_TOKENS_EMPTY] / [CANCELLED] / [TIMEOUT] /
 * [THERMAL_STOP] / [PREFILL_FAILURE] / [DECODE_FAILURE]）。[TEMPLATE_SUPPRESSED_OUTPUT] 为启发式：
 * 请求思考 + 模板含分支 + EOS 零输出，疑似模板渲染吞掉输出。
 */
enum class EmptyResponseClass {
    NONE,
    EOS_EMPTY,
    MAX_TOKENS_EMPTY,
    THINK_ONLY,
    WHITESPACE_ONLY,
    TEMPLATE_SUPPRESSED_OUTPUT,
    PREFILL_FAILURE,
    DECODE_FAILURE,
    CANCELLED,
    TIMEOUT,
    THERMAL_STOP,
}

/** 分类结果：空响应分类 + 思考效果。 */
data class ClassificationResult(
    val emptyResponseClass: EmptyResponseClass,
    val thinkingEffect: ThinkingEffect,
)

/**
 * 思考输出增量分类器（Task 2）：模仿 [com.rhodesisland.terminal.llm.IncrementalScriptDetector] 的增量旁路风格。
 *
 * 输入：流式 delta（**未装饰的原始模型文本**，未经 `renderLocalThink` 包装）；输出观察：
 * 是否见完整 `<think>` 开标签、是否见完整 `</think>` 闭标签、首个非空白正文绝对偏移、
 * raw 字节数、body 字节数；[finish] 产出 [EmptyResponseClass] 与 [ThinkingEffect]。
 *
 * 设计约束：
 * - 不吞改输出、不持有全文：只维护开/闭标签标志、首正文标志、字节计数与有界尾缓冲区。
 * - 增量 O(1) 摊还：标签检测只在「尾缓冲 + 本 delta」窗口内做（尾缓冲长 = 最长标签 8 - 1，
 *   任意跨 delta 分片的完整标签必被完整捕获）；首正文扫描每字符至多被扫一次，不重扫旧文本。
 * - `</think>` 是 reasoning 结束的**唯一可靠锚点**：模板前缀注入的起始 `<think>` 可能不在流中，
 *   模型也可能自输出 `<think>`；正文起点只认完整闭标签，开标签仅作存在性观察。
 * - 字节计数为 UTF-8 编码字节（与 native callbackBytes 口径一致）。
 *
 * 线程模型：与上层累加器同线程（MNN 解码回调）串行调用，无需同步。
 */
class ThinkingOutputClassifier(
    /** 本轮是否请求了深度思考（deepThinking 设置值）。 */
    private val thinkingRequested: Boolean,
    /** 本轮模板能力（[ThinkingTemplateCapabilityResolver] 解析结果）。 */
    private val templateCapability: ThinkingTemplateCapability,
) {

    /** Task 4：最近一次 [finish] 产出的空响应分类（供 [com.rhodesisland.terminal.llm.backend.BackendManager]
     *  在 GPU 空输出回退判定时**消费**，不重复调用 finish——finish 已被 MnnBackend 在 finally 内收口
     *  调用过一次（Task 2 fix），二次 finish 会污染旁路观察状态。未 finish 过为 null。
     *  线程模型：与上层累加器同线程串行调用（generationMutex 内），无需同步。 */
    var lastEmptyResponseClass: EmptyResponseClass? = null
        private set

    /** 已见完整 `<think>` 开标签。 */
    var sawThinkOpen: Boolean = false
        private set

    /** 已见完整 `</think>` 闭标签（reasoning 结束的唯一可靠锚点）。 */
    var sawThinkClose: Boolean = false
        private set

    /** 已喂入增量文本的 UTF-8 字节总数（原始输出量）。 */
    var rawBytes: Long = 0L
        private set

    /** 正文 UTF-8 字节数：`</think>` 之后全部文本（含分隔空白）；未见思考标签时 = [rawBytes]；思考中截断 = 0。 */
    val bodyBytes: Long
        get() = when {
            sawThinkClose -> bytesAfterClose
            !sawThinkOpen -> rawBytes
            else -> 0L
        }

    /**
     * 首个非空白正文字符的绝对偏移（UTF-16 字符下标，与上层累加器对齐）：
     * 已见 `</think>` → 闭标签之后首个非空白；未见任何思考标签 → 全流首个非空白；思考中截断 → null。
     */
    val firstBodyOffset: Int?
        get() = when {
            sawThinkClose -> firstBodyCharAfterClose
            !sawThinkOpen -> firstNonWsChar
            else -> null
        }

    /** `</think>` 之后的累计 UTF-8 字节数（增量累计）。 */
    private var bytesAfterClose: Long = 0L

    /** 首个非空白字符绝对偏移：闭标签之后口径 / 全流口径分别跟踪。 */
    private var firstBodyCharAfterClose: Int? = null
    private var firstNonWsChar: Int? = null

    /** 闭标签已闭合但首个非空白正文未出现时的扫描位置（绝对偏移，只进不退）。 */
    private var pendingScanOffset: Int? = null

    /** 有界尾缓冲（最近已消费文本，用于跨 delta 标签检测）。 */
    private var tail: String = ""

    /** 已喂入字符总数（绝对偏移基准）。 */
    private var charsFed: Int = 0

    /**
     * 追加一段增量文本（须与上层累加器按相同顺序喂入相同字符，使偏移与累加器对齐）。
     * 旁路观察：不修改输出、不持有全文。
     */
    fun append(delta: String) {
        if (delta.isEmpty()) return
        val deltaBytes = delta.toByteArray(Charsets.UTF_8).size
        rawBytes += deltaBytes
        val closeAlreadySeen = sawThinkClose
        // 闭标签在此之前已见：整个 delta 均为正文。
        if (closeAlreadySeen) bytesAfterClose += deltaBytes

        val combined = tail + delta

        // 开标签（跨 delta 分片经尾缓冲捕获；仅记录存在性，不作正文起点依据）
        if (!sawThinkOpen && combined.indexOf(OPEN_TAG) >= 0) sawThinkOpen = true

        // 闭标签：命中即正文起点——本 delta 内闭标签之后的字符为正文。
        // 不变式：闭标签的末字符必在本 delta（其到达时才置位 sawThinkClose），
        // 故 bodyStartInDelta ∈ [1, delta.length]，substring 安全。
        if (!sawThinkClose) {
            val closeIdx = combined.indexOf(CLOSE_TAG)
            if (closeIdx >= 0) {
                sawThinkClose = true
                val bodyStartInDelta = closeIdx + CLOSE_TAG.length - tail.length
                val bodyDelta = delta.substring(bodyStartInDelta)
                bytesAfterClose += bodyDelta.toByteArray(Charsets.UTF_8).size
                val trimmedBody = bodyDelta.trimStart()
                if (trimmedBody.isNotEmpty()) {
                    firstBodyCharAfterClose =
                        charsFed + bodyStartInDelta + (bodyDelta.length - trimmedBody.length)
                } else {
                    // 正文段全为空白：记下扫描起点，跨后续 delta 继续找首个非空白。
                    pendingScanOffset = charsFed + bodyStartInDelta + bodyDelta.length
                }
            }
        }

        // 闭标签在先前 delta 已见且首个非空白正文未出现：跨 delta 继续扫描（每字符至多扫一次）。
        // 本 delta 整体位于闭标签之后，其首字符即上次扫描位置的延续。
        if (closeAlreadySeen && firstBodyCharAfterClose == null) {
            val pending = pendingScanOffset
            if (pending != null) {
                val trimmed = delta.trimStart()
                if (trimmed.isNotEmpty()) {
                    firstBodyCharAfterClose = pending + (delta.length - trimmed.length)
                    pendingScanOffset = null
                } else {
                    pendingScanOffset = pending + delta.length
                }
            }
        }

        // 全流首个非空白（无思考标签时的正文起点回退口径）
        if (firstNonWsChar == null) {
            val trimmed = delta.trimStart()
            if (trimmed.isNotEmpty()) firstNonWsChar = charsFed + (delta.length - trimmed.length)
        }

        // 有界尾缓冲：最长标签 `</think>` 长 8，保留 7 个字符即保证任意完整标签必被捕获。
        tail = if (combined.length > TAIL_KEEP) combined.takeLast(TAIL_KEEP) else combined
        charsFed += delta.length
    }

    /**
     * 生成结束后收口分类（旁路观察完结）。
     *
     * @param completionReason 本轮终止原因（后端推导后的最终值）。
     * @param generatedTokens 本轮生成 token 数（用于区分 prefill/decode 阶段失败）。
     * @param errorStage native 出错阶段（[InferenceStage] 枚举名字符串；无错误/未知为 null）。
     *        PREFILL/DECODE_FAILURE 判定优先用本值，缺失时才回退 [generatedTokens] 近似。
     */
    fun finish(
        completionReason: CompletionReason,
        generatedTokens: Int,
        errorStage: String? = null,
    ): ClassificationResult {
        val hasBodyText = firstBodyOffset != null
        val thinkTagSeen = sawThinkOpen || sawThinkClose
        val emptyResponseClass = when {
            hasBodyText -> EmptyResponseClass.NONE
            thinkTagSeen -> EmptyResponseClass.THINK_ONLY
            rawBytes > 0L -> EmptyResponseClass.WHITESPACE_ONLY
            else -> classifyEmptyByReason(completionReason, generatedTokens, errorStage)
        }
        val thinkingEffect = when {
            // 硬性要求（Task 2 Step 4）：请求关闭仍出现完整思考段。
            !thinkingRequested && sawThinkClose -> ThinkingEffect.THINKING_DISABLE_NOT_EFFECTIVE
            thinkingRequested && sawThinkClose -> ThinkingEffect.ENABLED
            // 模板无 enable_thinking 分支：开关必然被忽略（无论输出形态）。
            thinkingRequested && templateCapability == ThinkingTemplateCapability.UNSUPPORTED ->
                ThinkingEffect.IGNORED_BY_TEMPLATE
            !thinkingRequested && hasBodyText -> ThinkingEffect.DISABLED
            else -> ThinkingEffect.UNKNOWN
        }
        // Task 4：收口结果落盘到只读属性（BackendManager 回退判定消费；单线程串行无并发问题）。
        lastEmptyResponseClass = emptyResponseClass
        return ClassificationResult(emptyResponseClass, thinkingEffect)
    }

    /** 无思考标签且零输出时，按终止原因归类空响应。 */
    private fun classifyEmptyByReason(
        reason: CompletionReason,
        generatedTokens: Int,
        errorStage: String?,
    ): EmptyResponseClass = when (reason) {
        CompletionReason.EOS ->
            // 启发式：请求思考 + 模板含分支 + EOS 零输出——疑似模板渲染吞掉了响应。
            if (thinkingRequested && templateCapability == ThinkingTemplateCapability.SUPPORTED)
                EmptyResponseClass.TEMPLATE_SUPPRESSED_OUTPUT
            else EmptyResponseClass.EOS_EMPTY
        CompletionReason.MAX_TOKENS -> EmptyResponseClass.MAX_TOKENS_EMPTY
        // POLICY_TRUNCATION 与用户取消同属「请求级提前终止」，归入 CANCELLED。
        CompletionReason.USER_CANCEL, CompletionReason.POLICY_TRUNCATION -> EmptyResponseClass.CANCELLED
        CompletionReason.TIMEOUT -> EmptyResponseClass.TIMEOUT
        CompletionReason.THERMAL_STOP -> EmptyResponseClass.THERMAL_STOP
        // PREFILL/DECODE_FAILURE 优先用 native errorStage（PREFILL/DECODE 明确区分）；
        // 缺失时回退近似：零 token 多为 prefill/加载阶段失败，已有 token 则为 decode 阶段失败。
        CompletionReason.BACKEND_FAILURE ->
            when (errorStage) {
                InferenceStage.PREFILL.name -> EmptyResponseClass.PREFILL_FAILURE
                InferenceStage.DECODE.name -> EmptyResponseClass.DECODE_FAILURE
                else -> if (generatedTokens <= 0) EmptyResponseClass.PREFILL_FAILURE
                    else EmptyResponseClass.DECODE_FAILURE
            }
    }

    companion object {
        private const val OPEN_TAG = "<think>"
        private const val CLOSE_TAG = "</think>"

        /** 尾缓冲长度 = 最长标签（`</think>`，8 字符）- 1：保证跨 delta 分片的完整标签必被捕获。 */
        private const val TAIL_KEEP = CLOSE_TAG.length - 1
    }
}
