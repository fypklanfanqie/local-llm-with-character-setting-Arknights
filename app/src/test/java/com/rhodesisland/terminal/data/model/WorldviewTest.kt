package com.rhodesisland.terminal.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义世界观指令块测试：空白正文跳过注入、正文裁剪、注入格式含遵循约束。
 */
class WorldviewTest {

    @Test
    fun blankContent_returnsEmptyDirective() {
        assertEquals("", Worldview("id", "n", "", WorldviewTargetType.CHARACTER, "amiya").directiveText())
        assertEquals("", Worldview("id", "n", "   \n ", WorldviewTargetType.GROUP, "1").directiveText())
    }

    @Test
    fun content_trimmedAndWrappedWithFollowConstraint() {
        val text = Worldview("id", "末日", " 故事发生在末日废土。 ", WorldviewTargetType.CHARACTER, "amiya")
            .directiveText()
        assertTrue(text.contains("[世界观设定]"))
        assertTrue(text.contains("故事发生在末日废土。"))
        assertTrue(text.contains("请严格遵循以上世界观的设定进行对话"))
        // 前导/尾随空白被裁剪（换行拼接后不应出现「设定]\n \n」式空洞）
        assertFalse(text.contains("\n \n"))
    }

    @Test
    fun directive_startsAndEndsWithSingleNewline() {
        val text = Worldview("id", "n", "C", WorldviewTargetType.GROUP, "7").directiveText()
        assertTrue(text.startsWith("\n[世界观设定]\nC\n请严格遵循"))
        assertTrue(text.endsWith("对话。"))
    }
}
