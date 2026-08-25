package com.rhodesisland.terminal.llm.template

/**
 * 增量输出健全性检测器（Wave 2 / 认证前置）。
 *
 * 动机：基准认证循环此前没有真实正确性证据——`benchmarkSampleFrom` 恒留
 * correctnessOk=true，FFFF/替换符洪水、复读环、退化单字输出都畅通无阻。KV 量化档位
 * （attention_mode=9/14）历史上出过乱码事故，认证采纳前必须有机器可判的输出质量证据。
 *
 * 与 [ThinkingOutputClassifier] 同风格：增量旁路（O(1) 空间），只观察原始模型文本，不持有全文。
 *
 * 判定维度（任一命中即非 SANE，按严重度排序）：
 * - [SanityClass.REPLACEMENT_CHARS]：U+FFFD 替换符占比超阈值 —— UTF-8 流被截断/解码错乱的标志
 *   （历史 FFFF 乱码事故的直接信号）；
 * - [SanityClass.REPETITION_LOOP]：短单元（1-8 字符）复读环占满尾部窗口 —— 小模型无惩罚时的
 *   结构性复读（"FFFFF…"、"哈哈哈哈哈…"、"。。。。。"）；
 * - [SanityClass.DEGENERATE]：有效字符集过小（去重后 ≤2 且长度 ≥20）—— 除复读环外另一种
 *   退化输出；
 * - [SanityClass.SANE]：以上皆未命中。
 */
class OutputSanityDetector {

    enum class SanityClass {
        SANE,
        REPLACEMENT_CHARS,
        REPETITION_LOOP,
        DEGENERATE,
    }

    // ---- 增量状态 ----
    private var totalChars = 0
    private var replacementChars = 0
    private var nonBlankChars = 0
    private val distinct = HashSet<Char>(64)
    private val tail = ArrayDeque<Char>()

    /** 追加一段流式文本（任意分片边界；内部按 code point 消化避免代理对劈半）。 */
    fun append(text: String) {
        if (text.isEmpty()) return
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                // 代理对算一个字符位；用首代理入集合/窗口即可（emoji 不参与单字符判定）
                consume(c)
                i += 2
            } else {
                consume(c)
                i++
            }
        }
    }

    private fun consume(c: Char) {
        totalChars++
        if (c == REPLACEMENT) replacementChars++
        if (!c.isWhitespace()) {
            nonBlankChars++
            distinct.add(c)
            tail.addLast(c)
            if (tail.size > TAIL_WINDOW) tail.removeFirst()
        }
    }

    /**
     * 当前判定（可随时调用；流结束后调用即最终分类）。
     * 文本过短（<20 个非空白字符）时恒 SANE——样本不足不做指控，交给 emptyResponseClass 处理空响应。
     */
    fun classify(): SanityClass {
        if (nonBlankChars < MIN_LENGTH_FOR_JUDGMENT) return SanityClass.SANE

        // 1) 替换符占比（阈值取宽松侧：>10% 即几乎必然是解码错乱而非偶发）
        val replacementRatio = replacementChars.toFloat() / totalChars
        if (replacementRatio > REPLACEMENT_RATIO_THRESHOLD) return SanityClass.REPLACEMENT_CHARS

        // 2) 尾部复读环：单元长度 1..8，重复 ≥6 次且覆盖尾部窗口 ≥80%
        val window = CharArray(tail.size)
        tail.forEachIndexed { i, c -> window[i] = c }
        for (unitLen in 1..MAX_UNIT_LEN) {
            if (tail.size < unitLen * MIN_REPEATS) continue
            // 候选单元 = 窗口末尾 unitLen 个字符；从尾部向前按单元对齐数重复次数。
            // 覆盖率按「窗口实际填充数」（tail.size）而非固定容量——短回复（如 15 字哈哈循环）
            // 在 128 容量里只填 15 格，按容量算覆盖率会漏检。
            var repeats = 0
            var pos = window.size - unitLen
            while (pos >= 0) {
                var match = true
                for (k in 0 until unitLen) {
                    if (window[pos + k] != window[window.size - unitLen + k]) {
                        match = false
                        break
                    }
                }
                if (!match) break
                repeats++
                pos -= unitLen
            }
            if (repeats >= MIN_REPEATS && repeats * unitLen >= tail.size * LOOP_COVERAGE_RATIO) {
                return SanityClass.REPETITION_LOOP
            }
        }

        // 3) 字符集退化：非空白去重 ≤2 且足够长。复读环判定在退化之前——"abab…" 形态同时满足
        //    两类，按更具体的复读环归类；纯交替但单元过长（>8）才落到这里判 DEGENERATE。
        if (distinct.size <= DEGENERATE_DISTINCT_MAX && nonBlankChars >= DEGENERATE_MIN_LENGTH) {
            return SanityClass.DEGENERATE
        }

        return SanityClass.SANE
    }

    companion object {
        private const val REPLACEMENT = '�'

        /** 判定所需最小非空白字符数（低于此恒 SANE）。 */
        const val MIN_LENGTH_FOR_JUDGMENT = 20

        /** 替换符占比阈值（0.10 = 宽松侧，>10% 几乎必然是流错乱）。 */
        const val REPLACEMENT_RATIO_THRESHOLD = 0.10f

        /** 尾部滑窗大小（字符）。 */
        const val TAIL_WINDOW = 128

        /** 复读环最大单元长度。 */
        const val MAX_UNIT_LEN = 8

        /** 复读环最少重复次数。 */
        const val MIN_REPEATS = 6

        /** 复读环覆盖尾部窗口比例（0.75：短回复前缀占比高时仍可检出）。 */
        const val LOOP_COVERAGE_RATIO = 0.75

        /** DEGENERATE 判定的最大去重字符数。 */
        const val DEGENERATE_DISTINCT_MAX = 2

        /** DEGENERATE 判定所需最小长度（比通用下限更严，避免误伤短回复）。 */
        const val DEGENERATE_MIN_LENGTH = 40
    }
}
