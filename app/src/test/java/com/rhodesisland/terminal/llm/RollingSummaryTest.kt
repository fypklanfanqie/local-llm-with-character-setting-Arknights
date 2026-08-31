package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动摘要上下文压缩（单聊云端）纯逻辑测试。
 *
 * 设计（用户方案）：常驻稳定前缀=[人设+世界书静态头 system]+[【前情提要】system]，
 * 追加区=未摘要轮次+本轮新消息。折叠批量 N 为**用户可调**（默认 50 条，即每攒 50 条压一次）；
 * 触发阈值由批量派生：min(2N, 供给上限-5)。两次折叠之间消息列表纯追加 →
 * 前缀逐字节一致、缓存全程命中；折叠那一刻断一次前缀，摊薄后命中率仍≈97%。
 */
class RollingSummaryTest {

    // ===== 折叠时机 =====

    @Test
    fun shouldFold_onlyAboveDerivedTrigger() {
        val batch = 50
        assertFalse(
            "未过派生阈值(2N)不折叠",
            RollingSummary.shouldFold(RollingSummary.triggerFor(batch), batch),
        )
        assertTrue(
            "超过派生阈值才折叠",
            RollingSummary.shouldFold(RollingSummary.triggerFor(batch) + 1, batch),
        )
        assertFalse(RollingSummary.shouldFold(0, batch))
        assertFalse(RollingSummary.shouldFold(-1, batch))
    }

    @Test
    fun triggerFor_saturatesBelowPromptSupplyCeiling() {
        assertEquals("默认 50 → 峰值恰为请求 cap(2N=100)", 100, RollingSummary.triggerFor(50))
        // 批量过大时阈值必须封顶在供给上限之下，否则 countUnfolded 永远到不了、折叠饿死
        for (b in listOf(60, 80, 500)) {
            assertTrue(
                "batch=$b 的触发阈值 ${RollingSummary.triggerFor(b)} 必须 < MAX_PROMPT_SUPPLY(${com.rhodesisland.terminal.config.AppConfig.MAX_PROMPT_SUPPLY})",
                RollingSummary.triggerFor(b) < com.rhodesisland.terminal.config.AppConfig.MAX_PROMPT_SUPPLY,
            )
        }
    }

    @Test
    fun coerceBatch_clampsToConfiguredRange() {
        assertEquals(50, RollingSummary.coerceBatch(50)) // 默认值原样
        assertEquals(AppConfig.RollingSummary.MIN_FOLD_BATCH, RollingSummary.coerceBatch(-1))
        assertEquals(AppConfig.RollingSummary.MAX_FOLD_BATCH, RollingSummary.coerceBatch(9999))
    }

    @Test
    fun defaultConstants_matchDesign() {
        assertEquals("用户约定的默认压缩节奏：每 50 条压一次", 50, AppConfig.RollingSummary.DEFAULT_FOLD_BATCH)
        assertTrue(AppConfig.RollingSummary.MIN_FOLD_BATCH <= AppConfig.RollingSummary.DEFAULT_FOLD_BATCH)
        assertTrue(AppConfig.RollingSummary.DEFAULT_FOLD_BATCH <= AppConfig.RollingSummary.MAX_FOLD_BATCH)
        assertEquals(300, AppConfig.RollingSummary.SUMMARY_MAX_CHARS)
    }

    // ===== 摘要提示词 =====

    @Test
    fun foldPrompt_includesOldSummaryAndBatchTexts_andCompressionDirectives() {
        val prompt = RollingSummary.buildFoldPrompt(
            oldSummary = "博士与凯尔希已约定周六检修设备。",
            batchLines = listOf("博士：修理厂还开着吗", "凯尔希：开到晚上八点"),
        )
        assertTrue(prompt.contains("博士与凯尔希已约定周六检修设备"))
        assertTrue(prompt.contains("修理厂还开着吗"))
        assertTrue(prompt.contains("开到晚上八点"))
        // 四要素压缩指令与长度上限（设计表：关系/承诺/伏笔/情绪基调，中文 ≤300 字）
        listOf("人物关系", "承诺", "伏笔", "情绪基调").forEach { keyword ->
            assertTrue("提示词须要求保留「$keyword」", prompt.contains(keyword))
        }
        assertTrue(prompt.contains("300"))
        // 空旧摘要以占位符表达，不留空洞标记
        val firstPrompt = RollingSummary.buildFoldPrompt("", batchLines = listOf("第一句"))
        assertFalse(firstPrompt.contains("【已有前情提要】\n\n"))
        assertTrue(firstPrompt.contains("无"))
    }

    // ===== 模型输出清洗 =====

    @Test
    fun sanitizeSummary_stripsThinkAndWhitespace() {
        val raw = "<think>让我想想怎么概括…</think>博士在修理厂与凯尔希碰面。"
        assertEquals("博士在修理厂与凯尔希碰面。", RollingSummary.sanitizeSummary(raw))
    }

    @Test
    fun sanitizeSummary_hardCapsAtMaxLength() {
        val long = "长".repeat(500)
        val out = RollingSummary.sanitizeSummary(long)
        assertEquals(AppConfig.RollingSummary.SUMMARY_MAX_CHARS.toLong(), out.length.toLong())
    }

    @Test
    fun sanitizeSummary_blankStaysBlank() {
        assertEquals("", RollingSummary.sanitizeSummary("   \n<think>x</think> "))
    }
}
