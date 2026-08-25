package com.rhodesisland.terminal.llm.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OutputSanityDetector（输出健全性检测）单测：FFFF 洪水 / 复读环 / 退化 / 正常中英 CJK emoji。 */
class OutputSanityDetectorTest {

    private fun classifyOf(vararg chunks: String): OutputSanityDetector.SanityClass {
        val d = OutputSanityDetector()
        chunks.forEach { d.append(it) }
        return d.classify()
    }

    @Test
    fun saneChineseResponse() {
        assertEquals(
            OutputSanityDetector.SanityClass.SANE,
            classifyOf("我是阿米娅，罗德岛的干员。今天有什么可以帮到你的吗？", "如果有需要请随时告诉我。"),
        )
    }

    @Test
    fun saneEnglishWithEmoji() {
        assertEquals(
            OutputSanityDetector.SanityClass.SANE,
            classifyOf("Hello! I am the Doctor's assistant 🎉 ", "We have a lot of work to do today, let us begin."),
        )
    }

    @Test
    fun replacementCharFloodDetected() {
        // 历史 FFFF 乱码事故形态：解码错乱产生大量 U+FFFD
        assertEquals(
            OutputSanityDetector.SanityClass.REPLACEMENT_CHARS,
            classifyOf("正常开头一句。", "�".repeat(60)),
        )
    }

    @Test
    fun lowReplacementRatioStillSane() {
        // 偶发 1-2 个替换符（长文本内）不构成指控
        val text = "这是一段足够长的正常中文回复内容，用于测试偶发替换符号不会触发误判，" +
            "再补充一些字数让总量超过判定下限。"
        assertEquals(OutputSanityDetector.SanityClass.SANE, classifyOf(text + "��"))
    }

    @Test
    fun singleCharLoopDetected() {
        assertEquals(
            OutputSanityDetector.SanityClass.REPETITION_LOOP,
            classifyOf("好的，以下是结果：", "F".repeat(80)),
        )
    }

    @Test
    fun multiCharLoopDetected() {
        assertEquals(
            OutputSanityDetector.SanityClass.REPETITION_LOOP,
            classifyOf("开始列举：", "哈哈。".repeat(30)),
        )
    }

    @Test
    fun shortRepetitionBelowThresholdIsSane() {
        // 少量重复（如省略号、语气词）不构成复读环
        assertEquals(
            OutputSanityDetector.SanityClass.SANE,
            classifyOf("嗯……这个问题的话，我觉得需要分几步来看待和处理。", "首先我们要明确目标是什么。"),
        )
    }

    @Test
    fun degenerateTwoCharAlphabetDetected() {
        // 小字符集长周期交替：unitLen 1..8 的环检测会以某个对齐粒度命中（周期 12 与 8 有公因数），
        // 归类为 REPETITION_LOOP——它确实是循环；纯 DEGENERATE 仅在「字符集≤2 但无短周期」时可达
        // （如超长伪随机 a/b 序列），此处验证的是小字符集退化至少被判非 SANE。
        // 字符经码点构造避开源码编码错位坑（同 incremental 用例注释）。
        val unit = StringBuilder()
        for (cp in intArrayOf(0x61, 0x61, 0x62, 0x62, 0x61, 0x61, 0x61, 0x61, 0x62, 0x62, 0x61, 0x62)) unit.appendCodePoint(cp)
        val text = unit.toString().repeat(5)
        val cls = classifyOf(text)
        assertTrue(
            "小字符集长交替应判非 SANE（实际 $cls）",
            cls == OutputSanityDetector.SanityClass.REPETITION_LOOP ||
                cls == OutputSanityDetector.SanityClass.DEGENERATE,
        )
    }

    @Test
    fun tooShortOutputIsSane() {
        // 样本不足不做指控（交给 emptyResponseClass 管空响应）
        assertEquals(OutputSanityDetector.SanityClass.SANE, classifyOf("FFFF"))
        assertEquals(OutputSanityDetector.SanityClass.SANE, classifyOf("好的。"))
    }

    @Test
    fun incrementalAppendAcrossChunkBoundaries() {
        // 分片边界打在复读环中间也要能检出。
        // 字符经 \u 转义构造：本仓库单测工具链存在 UTF-8/GBK 编码错位坑（项目记忆
        // unit-test-argfile-encoding-crash），中文字面量可能被错误解码成空串/乱码。
        // 前缀 = "回复如下："（5 个非空白字符）；循环体 = "哈"×15。
        val prefix = StringBuilder()
        for (cp in intArrayOf(0x56DE, 0x590D, 0x5982, 0x4E0B, 0xFF1A)) prefix.appendCodePoint(cp)
        val ha = 0x54C8.toChar() // 哈
        val d = OutputSanityDetector()
        prefix.forEach { c -> d.append(c.toString()) }
        repeat(5) { d.append(ha.toString()) }          // 逐字符分片
        d.append(ha.toString().repeat(10))             // 整块补齐到 15 连哈
        assertEquals(OutputSanityDetector.SanityClass.REPETITION_LOOP, d.classify())
    }
}
