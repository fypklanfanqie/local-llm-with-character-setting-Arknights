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
 *
 * [TRIVIAL] 为平凡门：寒暄/致谢/纯标点表情等零推理需求输入，与 [SIMPLE] 一同在 AUTO 档下
 * 整轮跳过思考——对应高效推理领域的 when-to-think 路由思想：思考是稀缺资源，
 * 平凡/简单问题不分配（省下的就是纯收益）。
 */
enum class QuestionComplexity {
    TRIVIAL,
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
 * - [effectiveLevel]：实际执行的强度。AUTO 会按 [complexity] 解析为受限子集（整体较旧版降一档：
 *   STANDARD→SHORT、COMPLEX→MEDIUM），否则与请求档位一致。[skipThinking]=true 时名义归入
 *   SHORT（枚举无 OFF 档），实际本轮完全不思考。
 * - [skipThinking]：本轮是否整体跳过思考（仅 AUTO→TRIVIAL/SIMPLE 为 true）。true 时调用方应把
 *   enable_thinking 传 false、[systemInstruction] 为空串、[thinkingBudgetBytes] 为 0（无思考段
 *   即无预算）。手动档（短/中/长）是用户显式选择的强度，永不跳过。
 * - [complexity]：仅 AUTO 时非空；手动档为 null（不经过复杂度分类）。
 * - [targetMinMs]/[targetMaxMs]：思考软目标时长范围。是产品调优目标而非硬 SLA：小模型可能忽略
 *   软提示而超出，也可能更快结束。skipThinking=true 时为 0。
 * - [checkpointBudget]：建议的思考要点上限（提示与诊断共用）。
 * - [thinkingBudgetBytes]：思考段 UTF-8 字节**硬预算**（Task 17）。推理模型对软提示服从度低，
 *   故按档位给出可执行上限：思考段（`<think>` 起至 `</think>` 止）字节数超过预算时由调用方
 *   （LocalChatProvider）截断思考并发起「直接作答」收束轮，保证「思考长度设置」真正生效。
 *   预算按 UTF-8 字节计（与 ThinkingOutputClassifier.rawBytes 口径一致）；skipThinking 时恒 0。
 * - [systemInstruction]：追加到本地 system message 的软收束提示（「思考模板」结构化微指令；
 *   小模型对长篇散文提示服从度差，指令本身也必须省 token）。
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
    val skipThinking: Boolean = false,
)

/**
 * 本地思考策略解析器（纯 Kotlin、确定性、无 Android/MNN 依赖，可 JVM 单测）。
 *
 * 职责：把「全局深度思考开关 + 本地档位」解析为本轮不可变的 [LocalThinkingPlan]。
 * - 开关关闭 -> 返回 null（不追加任何思考提示，本地继续传 enableThinking=false）。
 * - AUTO（when-to-think 路由，高效推理核心思想）：
 *   TRIVIAL/SIMPLE -> **整轮跳过思考**（enable_thinking=false、无提示、无预算）；
 *   STANDARD -> SHORT；COMPLEX -> MEDIUM。整体比按档直译低一档，压制「很多都没有意义」的长思考。
 * - 短/中/长：直接采用对应强度，不经过 AUTO 分类与跳过判定。
 * - 一律不修改最终答案的格式、篇幅或总 token 预算；只通过「跳过 + 提示 + 思考段预算」控制开销。
 */
class LocalThinkingPolicyResolver {

    /**
     * 解析本轮思考计划。
     * @param enabled 全局深度思考开关（deepThinking）。
     * @param requestedLevel 设置页档位。
     * @param latestUserContent 最后一条模型可见 user 消息文本（用于 AUTO 分类）。
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
        // when-to-think 路由：仅 AUTO 且命中 TRIVIAL/SIMPLE 时整轮跳过；手动档尊重用户显式选择。
        val skipThinking = requestedLevel == LocalThinkingLevel.AUTO &&
            (complexity == QuestionComplexity.TRIVIAL || complexity == QuestionComplexity.SIMPLE)
        val effectiveLevel = if (requestedLevel == LocalThinkingLevel.AUTO) {
            when (complexity) {
                // 跳过轮名义归 SHORT（枚举无 OFF 档，仅供遥测展示）。
                QuestionComplexity.TRIVIAL, QuestionComplexity.SIMPLE, QuestionComplexity.STANDARD ->
                    LocalThinkingLevel.SHORT
                // 复杂任务仍给足思考空间，但由 LONG 降为 MEDIUM（长档留给手动选择）。
                QuestionComplexity.COMPLEX -> LocalThinkingLevel.MEDIUM
                null -> LocalThinkingLevel.MEDIUM
            }
        } else requestedLevel
        val profile = if (skipThinking) {
            Profile(0L, 0L, 0)
        } else {
            targetProfile(effectiveLevel, complexity.takeUnless { it == QuestionComplexity.TRIVIAL })
        }

        return LocalThinkingPlan(
            requestedLevel = requestedLevel,
            effectiveLevel = effectiveLevel,
            complexity = complexity,
            controlMode = if (nativeBudgetAvailable) ThinkingControlMode.NATIVE_BUDGET
            else ThinkingControlMode.PROMPT_FALLBACK,
            targetMinMs = profile.targetMinMs,
            targetMaxMs = profile.targetMaxMs,
            checkpointBudget = profile.checkpointBudget,
            thinkingBudgetBytes = if (skipThinking) 0L else thinkingBudgetBytes(effectiveLevel),
            systemInstruction = if (skipThinking) "" else buildInstruction(effectiveLevel, profile.checkpointBudget),
            skipThinking = skipThinking,
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
     * 平凡门（when-to-think 第一级路由）：命中即整轮跳过思考。
     *
     * 三条独立判据（满足任一即为平凡）：
     * 1. 寒暄全匹配：整条消息仅由常见问候/致谢/附和/告别/亲昵/感叹 token 与尾随标点组成；
     * 2. 纯口算：仅数字与四则运算符号组成的极短算式；
     * 3. 无文字内容：纯标点/空白/符号（含表情）。
     *
     * 刻意用**整条全匹配**而非子串包含——「你好，帮我写个排序」这类带任务内容的消息不会因
     * 开头寒暄而被误跳过；问句（天气怎么样/为什么…）不含在词表里，自然落回常规分类。
     */
    fun isTrivial(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.length > TRIVIAL_MAX_LENGTH) return false
        if (TRIVIAL_CASUAL.matchEntire(t) != null) return true
        if (ARITHMETIC_ONLY.matchEntire(t) != null) return true
        return t.none { it.isLetterOrDigit() }
    }

    /**
     * 结构启发式复杂度分类：平凡门优先；0 分且短 -> SIMPLE；1–2 分 -> STANDARD；
     * >=3 分且至少一项结构证据 -> COMPLEX；其余保守 STANDARD。
     *
     * 语义关键词（「复杂」「证明」等）最多加 1 分，且不能单独把问题判为 COMPLEX——
     * 避免「这是一道复杂问题」这类字面关键词被误判为深任务。
     */
    fun classify(latestUserContent: String): QuestionComplexity {
        val text = latestUserContent.trim()
        if (isTrivial(text)) return QuestionComplexity.TRIVIAL

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

    /**
     * 「思考模板」结构化微指令（succinct-CoT / Chain-of-Draft 风格）：
     * - 固定三段骨架「目标 / 要点 / 结论」，每段一行、直给内容——把开放式「想一想」收敛为
     *   填空式草稿，从源头压缩无效 token；
     * - 负向禁令点名两类最无意义的思考（复述问题原文、重复验证已确认步骤）；
     * - 「满足即立即停止」给出明确收束条件，配 Task 17 字节硬预算形成软硬两级控制；
     * - 保留两条语义保证：只约束思考不改回答、答案完整性不受思考缩短影响。
     * 手动档通过要点上限（N）与深浅提示区分强度；skipThinking 时本函数不会被调用。
     */
    private fun buildInstruction(effectiveLevel: LocalThinkingLevel, checkpointBudget: Int): String {
        val depthHint = when (effectiveLevel) {
            LocalThinkingLevel.SHORT -> "多数问题凭直觉直接给结论"
            LocalThinkingLevel.MEDIUM -> "检查关键假设与明显反例后再下结论"
            LocalThinkingLevel.LONG -> "复杂处可多列几条要点，覆盖边界与自检"
            LocalThinkingLevel.AUTO -> "按问题需要取舍深浅"
        }
        return "\n\n【思考要求（仅本轮生效）】\n" +
            "本提示只约束你的内部思考过程，不改变最终回答的格式、篇幅与内容要求。\n" +
            "用以下思考模板组织思考，每行直给内容：\n" +
            "目标：<一句话写清本次要解决什么>\n" +
            "要点：<最多 $checkpointBudget 条，每条一行；禁止：复述问题原文、重复验证已确认的步骤>" +
            "（$depthHint）\n" +
            "结论：<据此作答的最短路径>\n" +
            "满足上述模板即可立即停止思考并写出完整最终答案；不得因思考简短而省略重要结论。"
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

        /** 平凡门最大长度：超过必不是寒暄（寒暄极少超过一句话）。 */
        const val TRIVIAL_MAX_LENGTH = 24

        /** 语义关键词最多贡献 1 分（弱证据），不能单独判 COMPLEX。 */
        val KEYWORDS = listOf("证明", "调试", "架构", "比较", "推导", "修复")

        val FENCED_BLOCK = Regex("(?:```|~~~).*?(?:```|~~~)", RegexOption.DOT_MATCHES_ALL)
        val BULLET_LINE = Regex("""^\s*(?:[-*•]|\d+[.、）)])\s+""")

        /**
         * 寒暄词表（整条全匹配，允许多 token 连用 + 尾随标点/语气符）：
         * 结构 = (?:(?:词表)(尾随符号)*)+ —— 外层组必须闭合且带 + 量词，
         * 否则 Regex 构造抛 PatternSyntaxException → ExceptionInInitializerError
         * （LocalChatProvider 首次实例化即崩，见 20260825 崩溃日志）。
         * 问句词（吗/呢/怎么/为什么…）刻意不入表——「吃了吗/在吗/忙吗」作为固定短语单独收录，
         * 其余带疑问词的消息一律走常规分类。
         */
        val TRIVIAL_CASUAL = Regex(
            "(?:(?:你好|您好|哈喽|嗨+|嗨呀|hi+|hello+|hey+|yo+|" +
                "早安|早上好|中午好|下午好|晚上好|晚安|安|" +
                "在吗|在么|在不在|吃了吗|睡了吗|醒了吗|忙吗|忙不忙|" +
                "谢谢(?:你|啦|了)?|感谢(?:你)?|多谢|麻烦(?:你)?了|辛苦(?:你)?[了啦]|有劳了|" +
                "好的|好吧|好呀|好嘞|好哒|好耶|好滴|可以(?:的)?|行的|行吧|中|成(?:吧)?|没问题|" +
                "嗯+|哦+|噢+|喔+|诶+|欸+|呃+|唔+|" +
                "ok(?:ay)?|收到|了解|明白|知道(?:了)?|懂(?:了)?|" +
                "拜拜|再见|回见|明天见|下次见|回头见|" +
                "抱抱|亲亲|摸摸头|揉揉|拍拍|贴贴|比心|爱你|想你|想你了|喜欢你们?|" +
                "哈哈+|呵呵+|嘿嘿+|嘻嘻+|嘶+|呼+|呜呜+|哇+|哇哦|哦哦+|" +
                "厉害[了呀啊]?|牛[逼b][呀啊]?|太棒了|真棒|赞(?:一个)?|强[呀啊]?|" +
                "666+|233+|hhh+|lol+|lmao|xd+)" +
                "[\\s~～!！?？。，,.…·—、♡]*)+",
            RegexOption.IGNORE_CASE,
        )

        /** 极短纯口算：仅数字与四则运算/括号（如 1+1、23×7=?）。 */
        val ARITHMETIC_ONLY = Regex("""[\d\s+\-*/×÷=().，,？?]+""")
    }
}
