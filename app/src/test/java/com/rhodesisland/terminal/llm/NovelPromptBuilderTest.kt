package com.rhodesisland.terminal.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NovelPromptBuilder] 契约测试：续写提示词的关键约束。
 */
class NovelPromptBuilderTest {

    @Test
    fun system_containsStyleAndFormatContract() {
        val system = NovelPromptBuilder.buildSystem(
            background = "荒岛求生",
            characterSheets = listOf("阿米娅：罗德岛领袖"),
            protagonistName = "博士",
            protagonistPersona = "冷静",
        )
        assertTrue(system.contains("荒岛求生"))
        assertTrue(system.contains("阿米娅：罗德岛领袖"))
        assertTrue(system.contains("博士"))
        // 输出格式约束：名字：对白 / 旁白
        assertTrue(system.contains("旁白"))
        // 语言约束
        assertTrue(system.contains("简体中文"))
    }

    @Test
    fun user_containsChapterContext_andContinueInstruction() {
        val script = listOf(
            NovelScriptParser.ScriptLine(NovelScriptParser.TYPE_NARRATION, "旁白", null, "开场"),
            NovelScriptParser.ScriptLine(NovelScriptParser.TYPE_CHARACTER, "阿米娅", "amiya", "你好"),
        )
        val user = NovelPromptBuilder.buildUser(
            chapterTitle = "第 1 话",
            summary = "坠机后的第一晚",
            opening = "旁白：夜幕降临",
            requirements = "以阿米娅发现物资为发展",
            script = script,
        )
        assertTrue(user.contains("第 1 话"))
        assertTrue(user.contains("坠机后的第一晚"))
        assertTrue(user.contains("夜幕降临"))
        assertTrue(user.contains("以阿米娅发现物资为发展"))
        assertTrue(user.contains("旁白：开场"))
        assertTrue(user.contains("阿米娅：你好"))
        // 续写指令
        assertTrue(user.contains("续写"))
    }

    @Test
    fun user_trimsScriptWindow() {
        val many = (1..100).map { i ->
            NovelScriptParser.ScriptLine(NovelScriptParser.TYPE_NARRATION, "旁白", null, "行$i")
        }
        val user = NovelPromptBuilder.buildUser("t", "", "", "", many)
        // 最近 80 行窗口：首行（行1）被裁掉，行21..100 保留
        assertFalse(user.contains("行1\n"))
        assertFalse(user.contains("行20"))
        assertTrue(user.contains("行100"))
    }
}
