package com.rhodesisland.terminal.llm.thinking

import com.rhodesisland.terminal.llm.template.ThinkingOutputClassifier

/**
 * 思考段是否超过档位预算（Task 17）：思考段已开启且未闭合，且思考段字节数 > [budgetBytes]。
 *
 * 思考段字节 = rawBytes - bodyBytes（思考未闭合时 bodyBytes=0，即当前全部输出字节）；
 * 与 [ThinkingOutputClassifier] 的 UTF-8 字节口径一致。纯函数，JVM 可测。
 */
fun shouldTruncateThinking(classifier: ThinkingOutputClassifier, budgetBytes: Long): Boolean =
    classifier.sawThinkOpen && !classifier.sawThinkClose &&
        (classifier.rawBytes - classifier.bodyBytes) > budgetBytes

/**
 * 问题复杂度分类（仅用于选择思考策略，不决定最终答案长度）。
 */
enum class QuestionComplexity {
    SIMPLE,
    STANDARD,
    COMPLEX,
}

/**
 * 本轮思考的控制方式。
 *
 * 首期统一 [PROMPT_FALLBACK]：在本地 system message 追加软收束提示，不硬截断、不降低总
 * maxTokens。[NATIVE_BUDGET] 为未来预留——只有出现「版本化适配器 + 精确 runtime capability +
 * 完整指纹认证」的证据时才使用；当前无任何本地模型具备该证据，恒不启用。
 */
enum class ThinkingControlMode {
    PROMPT_FALLBACK,
    NATIVE_BUDGET,
}

/**
 * 本轮思考策略的不可变计划。
 *
 * - [requestedLevel]：设置页选择的档位（AUTO/短/中/长）。
 * - [effectiveLevel]：实际执行的强度。AUTO 会按 [complexity] 解析为受限子集，否则与请求档位一致。
 * - [complexity]：仅 AUTO 时非空；手动档为 null（不经过复杂度分类）。
 * - [targetMinMs]/[targetMaxMs]：思考软目标时长范围。是产品调优目标而非硬 SLA：小模型可能忽略
 *   软提示而超出，也可能更快结束。
 * - [checkpointBudget]：建议的思考核验点上限（提示与诊断共用）。
 * - [thinkingBudgetBytes]：思考段 UTF-8 字节**硬预算**（Task 17）。推理模型对软提示服从度低，
 *   故按档位给出可执行上限：思考段（`<think>` 起至 `</think>` 止）字节数超过预算时由调用方
 *   （LocalChatProvider）截断思考并发起「直接作答」收束轮，保证「思考长度设置」真正生效。
 *   预算按 UTF-8 字节计（与 ThinkingOutputClassifier.rawBytes 口径一致）。
 * - [systemInstruction]：追加到本地 system message 的软收束提示。
 */
data class LocalThinkingPlan(
    val requestedLevel: LocalThinkingLevel,
    val effectiveLevel: LocalThinkingLevel,
    val complexity: QuestionComplexity?,
    val controlMode: ThinkingControlMode,
    val targetMinMs: Long,
    val targetMaxMs: Long,
    val checkpointBudget: Int,
    val thinkingBudgetBytes: Long,
    val systemInstruction: String,
)

/**
 * 本地思考策略解析器（纯 Kotlin、确定性、无 Android/MNN 依赖，可 JVM 单测）。
 *
 * 职责：把「全局深度思考开关 + 本地档位」解析为本轮不可变的 [LocalThinkingPlan]。
 * - 开关关闭 -> 返回 null（不追加任何思考提示，本地继续传 enableThinking=false）。
 * - AUTO：用结构启发式把最后一条 user 消息分类为 SIMPLE/STANDARD/COMPLEX，映射到受限子集。
 * - 短/中/长：直接采用对应强度，不经过 AUTO 分类。
 * - 一律不修改最终答案的格式、篇幅或总 token 预算；只通过提示引导思考尽早收束。
 */
class LocalThinkingPolicyResolver {

    /**
     * 解析本轮思考计划。
     * @param enabled 全局深度思考开关（deepThinking）。
     * @param requestedLevel 设置页档位。
     * @param latestUserContent 最后一条模型可见 user 消息文本（仅用于 AUTO 结构分类）。
     * @param nativeBudgetAvailable 是否存在经完整认证的原生思考预算能力（首期为 false）。
     * @return 开关关闭时 null；否则为完整计划。
     */
    fun resolve(
        enabled: Boolean,
        requestedLevel: LocalThinkingLevel,
        latestUserContent: String,
        nativeBudgetAvailable: Boolean,
    ): LocalThinkingPlan? {
        if (!enabled) return null

        val complexity: QuestionComplexity? = if (requestedLevel == LocalThinkingLevel.AUTO) {
            classify(latestUserContent)
        } else null
        val effectiveLevel = if (requestedLevel == LocalThinkingLevel.AUTO) {
            when (complexity) {
                QuestionComplexity.SIMPLE -> LocalThinkingLevel.SHORT
                QuestionComplexity.STANDARD -> LocalThinkingLevel.MEDIUM
                QuestionComplexity.COMPLEX -> LocalThinkingLevel.LONG
                null -> LocalThinkingLevel.MEDIUM
            }
        } else requestedLevel
        val profile = targetProfile(effectiveLevel, complexity)

        return LocalThinkingPlan(
            requestedLevel = requestedLevel,
            effectiveLevel = effectiveLevel,
            complexity = complexity,
            controlMode = if (nativeBudgetAvailable) ThinkingControlMode.NATIVE_BUDGET
            else ThinkingControlMode.PROMPT_FALLBACK,
            targetMinMs = profile.targetMinMs,
            targetMaxMs = profile.targetMaxMs,
            checkpointBudget = profile.checkpointBudget,
            thinkingBudgetBytes = thinkingBudgetBytes(effectiveLevel),
            systemInstruction = buildInstruction(effectiveLevel, profile.checkpointBudget),
        )
    }

    /**
     * 思考段字节硬预算（Task 17）：档位 -> 思考段 UTF-8 字节上限。
     *
     * 约 token 数：中文约 1 字/3 字节 ≈ 1 token；英文约 1 token/4 字节。按 ×4 字节/中文 token
     * 换算取中（对中文思考偏宽松、对英文偏紧），配合保守档位值：
     * SHORT ≈ 384 中文 token、MEDIUM ≈ 768、LONG ≈ 1536（R1 级长思考远超 LONG 预算即被截断）。
     * 预算必须**小于默认总 maxTokens（2048 token ≈ 8KB 中文）**——否则思考会先被 maxTokens 硬截断，
     * 档位预算失去意义，且不给正文留空间。
     */
    fun thinkingBudgetBytes(level: LocalThinkingLevel): Long = when (level) {
        LocalThinkingLevel.SHORT -> 384L * 4
        LocalThinkingLevel.MEDIUM -> 768L * 4
        LocalThinkingLevel.LONG -> 1536L * 4
        // AUTO 已在上游解析为受限子集；兜底与 MEDIUM 同档。
        LocalThinkingLevel.AUTO -> 768L * 4
    }

    /**
     * 纯结构启发式复杂度分类：0 分且短 -> SIMPLE；1–2 分 -> STANDARD；
     * >=3 分且至少一项结构证据 -> COMPLEX；其余保守 STANDARD。
     *
     * 语义关键词（「复杂」「证明」等）最多加 1 分，且不能单独把问题判为 COMPLEX——
     * 避免「这是一道复杂问题」这类字面关键词被误判为深任务。
     */
    fun classify(latestUserContent: String): QuestionComplexity {
        val text = latestUserContent.trim()
        var score = 0
        var structuralEvidence = false

        // 长度证据（<=2 分）。
        if (text.length >= LONG_TEXT_MIN) score++
        if (text.length >= VERY_LONG_TEXT_MIN) score++
        if (text.length >= LONG_TEXT_MIN || text.length >= VERY_LONG_TEXT_MIN) structuralEvidence = true

        // 大段代码/日志（fenced block，长度 >=200）-> 结构证据。
        if (fencedContentLength(text) >= LARGE_CODE_MIN) {
            score++
            structuralEvidence = true
        }
        // 多行（>=12 行）-> 结构证据。
        if (text.lines().size >= MULTILINE_MIN) {
            score++
            structuralEvidence = true
        }
        // 4 个以上编号/项目符号 -> 结构证据。
        if (bulletCount(text) >= BULLET_MIN) {
            score++
            structuralEvidence = true
        }
        // 语义关键词（弱证据，最多 +1）。
        if (KEYWORDS.any { text.contains(it) }) score++

        return when {
            score == 0 && text.length < SIMPLE_MAX_LENGTH -> QuestionComplexity.SIMPLE
            score >= COMPLEX_MIN_SCORE && structuralEvidence -> QuestionComplexity.COMPLEX
            else -> QuestionComplexity.STANDARD
        }
    }

    private fun targetProfile(
        effectiveLevel: LocalThinkingLevel,
        autoComplexity: QuestionComplexity?,
    ): Profile = when (effectiveLevel) {
        // AUTO 解析出的短思考：普通简单问题约 5–8 秒；手动「短」约 3–8 秒。
        LocalThinkingLevel.SHORT -> if (autoComplexity == QuestionComplexity.SIMPLE) {
            Profile(5_000L, 8_000L, 2)
        } else {
            Profile(3_000L, 8_000L, 2)
        }
        // AUTO 解析出的标准思考：约 8–15 秒；手动「中」约 8–20 秒。
        LocalThinkingLevel.MEDIUM -> if (autoComplexity == QuestionComplexity.STANDARD) {
            Profile(8_000L, 15_000L, 4)
        } else {
            Profile(8_000L, 20_000L, 4)
        }
        // AUTO 解析出的长思考：约 15–30 秒；手动「长」约 20–45 秒。
        LocalThinkingLevel.LONG -> if (autoComplexity == QuestionComplexity.COMPLEX) {
            Profile(15_000L, 30_000L, 6)
        } else {
            Profile(20_000L, 45_000L, 8)
        }
        // AUTO 不应到达（AUTO 已在上游解析），兜底一个普通范围。
        LocalThinkingLevel.AUTO -> Profile(5_000L, 15_000L, 4)
    }

    private fun buildInstruction(effectiveLevel: LocalThinkingLevel, checkpointBudget: Int): String {
        // 深度短语按生效档位选择（描述「本次有效」的思考节奏，与目标时长/核验点预算同源）。
        val depth = when (effectiveLevel) {
            LocalThinkingLevel.SHORT -> "只做最必要的核验，避免展开无关分支"
            LocalThinkingLevel.MEDIUM -> "做必要的核验，检查主要假设与反例"
            LocalThinkingLevel.LONG -> "深入分析，覆盖关键方案、边界与自检"
            LocalThinkingLevel.AUTO -> "按问题复杂度做必要核验"
        }
        return "\n\n【思考节奏（本次有效）】\n" +
            "本提示只约束你的内部思考过程，不改变最终回答的格式、篇幅或内容要求。\n" +
            "- 先明确问题的关键事实与必要推导，$depth（建议控制在 $checkpointBudget 个核验点以内）。\n" +
            "- 当思考已足以给出可靠回答时，立即停止扩展更多旁支，收束并给出最终答案；" +
            "问题较复杂时可适度加深，但以尽快给出结论为目标。\n" +
            "- 最终答案必须完整满足用户要求的格式、篇幅与内容，不得因思考被缩短而省略或简化重要结论。\n" +
            "- 优先产出完整、正确的最终答案，不要在思考段留下未完成的半截推理。"
    }

    /** 计算文本中被 ``` 或 ~~~ 包裹的代码/日志块的总长度（启发式）。 */
    private fun fencedContentLength(text: String): Int =
        FENCED_BLOCK.findAll(text).sumOf { it.value.length }

    /** 计算编号/项目符号条目数：行首为 `-`/`*`/`•` 或 `1.`/`1、` 等。 */
    private fun bulletCount(text: String): Int =
        text.lines().count { BULLET_LINE.containsMatchIn(it) }

    private data class Profile(
        val targetMinMs: Long,
        val targetMaxMs: Long,
        val checkpointBudget: Int,
    )

    private companion object {
        const val LONG_TEXT_MIN = 400
        const val VERY_LONG_TEXT_MIN = 1200
        const val SIMPLE_MAX_LENGTH = 120
        const val LARGE_CODE_MIN = 200
        const val MULTILINE_MIN = 12
        const val BULLET_MIN = 4
        const val COMPLEX_MIN_SCORE = 3

        /** 语义关键词最多贡献 1 分（弱证据），不能单独判 COMPLEX。 */
        val KEYWORDS = listOf("证明", "调试", "架构", "比较", "推导", "修复")

        val FENCED_BLOCK = Regex("(?:```|~~~).*?(?:```|~~~)", RegexOption.DOT_MATCHES_ALL)
        val BULLET_LINE = Regex("""^\s*(?:[-*•]|\d+[.、）)])\s+""")
    }
}
