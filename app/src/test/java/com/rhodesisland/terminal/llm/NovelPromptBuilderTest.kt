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

    // ===== 用户亲自发言后的「按这个角色推进剧情」约束 =====

    @Test
    fun user_withCharacterDirectedLine_requiresPersonaFidelity() {
        val script = listOf(
            NovelScriptParser.ScriptLine(NovelScriptParser.TYPE_NARRATION, "旁白", null, "夜色沉了下来"),
            NovelScriptParser.ScriptLine(NovelScriptParser.TYPE_CHARACTER, "阿米娅", "amiya", "我们该出发了"),
        )
        val user = NovelPromptBuilder.buildUser(
            chapterTitle = "第 1 话",
            summary = "",
            opening = "",
            requirements = "",
            script = script,
            directed = NovelPromptBuilder.DirectedLine(
                speakerType = NovelScriptParser.TYPE_CHARACTER,
                speakerName = "阿米娅",
                content = "我们该出发了",
                speakerRole = "protagonist",
            ),
        )
        // 用户这一行要单独成段，而不是只混在正文里
        assertTrue(user.contains("[用户刚写下的一行"))
        assertTrue(user.contains("阿米娅：我们该出发了"))
        // 核心要求：贴人设、不 OOC、不马上改口
        assertTrue(user.contains("人设"))
        assertTrue(user.contains("OOC"))
        assertTrue(user.contains("不要复述"))
        // 主角定位：主轴围绕他展开
        assertTrue(user.contains("主角"))
        // 明确「从哪一行之后接着写」，避免把用户这行当上下文复述
        assertTrue(user.contains("之后接着写"))
    }

    @Test
    fun user_withSupportingDirectedLine_warnsAgainstHijackingTheLead() {
        val user = NovelPromptBuilder.buildUser(
            chapterTitle = "第 1 话", summary = "", opening = "", requirements = "", script = emptyList(),
            directed = NovelPromptBuilder.DirectedLine(
                speakerType = NovelScriptParser.TYPE_CHARACTER,
                speakerName = "蜜莓",
                content = "医务室还有人吗",
                speakerRole = "supporting",
            ),
        )
        assertTrue(user.contains("配角"))
        assertTrue(user.contains("克制"))
    }

    @Test
    fun user_withNarrationDirectedLine_treatsItAsSceneDirective() {
        val user = NovelPromptBuilder.buildUser(
            chapterTitle = "第 1 话", summary = "", opening = "", requirements = "", script = emptyList(),
            directed = NovelPromptBuilder.DirectedLine(
                speakerType = NovelScriptParser.TYPE_NARRATION,
                speakerName = "旁白",
                content = "警报突然响起",
            ),
        )
        assertTrue(user.contains("[用户刚写下的一行"))
        assertTrue(user.contains("场景"))
        assertTrue(user.contains("已经发生的事实"))
    }

    @Test
    fun user_withoutDirectedLine_keepsPromptUnchanged() {
        // 无用户主导行时提示词必须与旧版一致：不出现推进段，且保留原续写指令
        val user = NovelPromptBuilder.buildUser("t", "", "", "", emptyList())
        assertFalse(user.contains("[用户刚写下的一行"))
        assertTrue(user.contains("从新内容开始"))
    }
}
