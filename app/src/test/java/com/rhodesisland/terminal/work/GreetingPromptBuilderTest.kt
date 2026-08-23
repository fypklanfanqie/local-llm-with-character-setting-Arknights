package com.rhodesisland.terminal.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动问候提示词构建纯函数测试。
 *
 * 背景：旧实现把「当前时间 HH:mm」写进 system 指令，分钟级变化导致云端 prompt 前缀
 * 缓存几乎无法复用。降级为只含低基数时段词后，同一时段内前缀字节稳定，可跨请求命中缓存。
 *
 * 覆盖：
 * - 时段词映射与旧实现一致（清晨/上午/中午/下午/傍晚/晚上）；
 * - 指令文本不含任何数字时间（HH:mm），保证同小时内前缀稳定；
 * - 指令保留「主动发消息」的完整要求（人设/简短/只输出消息本身）。
 */
class GreetingPromptBuilderTest {

    @Test
    fun timeDirective_usesPeriodWord_notClockTime() {
        val directive = GreetingPromptBuilder.buildTimeDirective(hour = 9)
        assertTrue("应包含时段词", directive.contains("上午"))
        // 不得出现 HH:mm 形式的数字时间（如 "09:00"）；冒号加两位数字的组合一律拒绝
        assertFalse("不得包含数字时钟时间", Regex("""\d{1,2}:\d{2}""").containsMatchIn(directive))
    }

    @Test
    fun periodMapping_matchesLegacyBoundaries() {
        // 与 GreetingWorker 旧 when 划分一致
        assertEquals("清晨", GreetingPromptBuilder.periodName(5))
        assertEquals("清晨", GreetingPromptBuilder.periodName(7))
        assertEquals("上午", GreetingPromptBuilder.periodName(8))
        assertEquals("上午", GreetingPromptBuilder.periodName(10))
        assertEquals("中午", GreetingPromptBuilder.periodName(11))
        assertEquals("中午", GreetingPromptBuilder.periodName(13))
        assertEquals("下午", GreetingPromptBuilder.periodName(14))
        assertEquals("下午", GreetingPromptBuilder.periodName(17))
        assertEquals("傍晚", GreetingPromptBuilder.periodName(18))
        assertEquals("傍晚", GreetingPromptBuilder.periodName(21))
        assertEquals("晚上", GreetingPromptBuilder.periodName(22))
        assertEquals("晚上", GreetingPromptBuilder.periodName(3))
        assertEquals("晚上", GreetingPromptBuilder.periodName(4))
    }

    @Test
    fun timeDirective_keepsCoreInstructions() {
        val directive = GreetingPromptBuilder.buildTimeDirective(hour = 20)
        assertTrue("应要求主动发消息", directive.contains("主动给用户发一条消息"))
        assertTrue("应要求符合人设", directive.contains("人设"))
        assertTrue("应要求简短", directive.contains("1-3 句"))
        assertTrue("应要求只输出消息本身", directive.contains("只输出消息内容本身"))
        // 同一小时内任意两次调用结果逐字节一致（缓存友好的核心保证）
        assertEquals(directive, GreetingPromptBuilder.buildTimeDirective(hour = 20))
    }
}
