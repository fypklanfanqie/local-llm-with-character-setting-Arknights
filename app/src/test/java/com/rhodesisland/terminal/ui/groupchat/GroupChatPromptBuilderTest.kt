package com.rhodesisland.terminal.ui.groupchat

import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.Worldview
import com.rhodesisland.terminal.data.model.WorldviewTargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊提示词构建纯函数测试。
 *
 * 断言：system 注入全员人设（截断）+「以 X 身份」；assistant 历史改写为「名字：内容」并剥离思考；
 * user 保持 user；截尾 MAX_CONTEXT_MESSAGES；ask-user 与 discuss 指令不同；前缀剥离全/半角冒号与引导符。
 */
class GroupChatPromptBuilderTest {

    private fun char(id: String, name: String, prompt: String = "人设-$name") = Character(
        id = id, name = name, code = "code-$id", role = "干员", race = "种族", systemPrompt = prompt,
    )

    @Test
    fun buildApiMessages_firstIsSystemContainingAllPersonasAndSpeakAs() {
        val a = char("a", "阿米娅")
        val b = char("b", "能天使")
        val messages = GroupChatPromptBuilder.buildApiMessages(
            members = listOf(a, b), speaker = a, history = emptyList(), askUser = false,
        )
        val system = messages.first().content
        assertEquals("system", messages.first().role)
        assertTrue(system.contains("阿米娅"))
        assertTrue(system.contains("能天使"))
        assertTrue(system.contains("人设-阿米娅"))
        assertTrue(system.contains("只输出阿米娅要说的话本身"))
    }

    @Test
    fun buildSystemPrompt_truncatesPersonaToMaxChars() {
        val longPrompt = "x".repeat(1000)
        val a = char("a", "阿米娅", prompt = longPrompt)
        val system = GroupChatPromptBuilder.buildSystemPrompt(listOf(a), a, askUser = false)
        assertTrue(system.contains(longPrompt.take(AppConfig.GroupChat.PERSONA_MAX_CHARS)))
        assertFalse(system.contains(longPrompt.take(AppConfig.GroupChat.PERSONA_MAX_CHARS + 1)))
    }

    @Test
    fun buildApiMessages_assistantHistoryPrefixedWithSpeakerName() {
        val a = char("a", "阿米娅")
        val b = char("b", "能天使")
        val history = listOf(
            ChatMessage(role = "assistant", content = "大家好啊", characterId = "b"),
            ChatMessage(role = "user", content = "你们在聊什么", characterId = "group_chat"),
        )
        val messages = GroupChatPromptBuilder.buildApiMessages(
            members = listOf(a, b), speaker = a, history = history, askUser = false,
        )
        assertEquals("能天使：大家好啊", messages[1].content)
        assertEquals("assistant", messages[1].role)
        assertEquals("你们在聊什么", messages[2].content)
        assertEquals("user", messages[2].role)
    }

    @Test
    fun buildApiMessages_assistantHistoryUnknownSpeakerFallsBack() {
        val a = char("a", "阿米娅")
        val history = listOf(ChatMessage(role = "assistant", content = "你好", characterId = "removed-id"))
        val messages = GroupChatPromptBuilder.buildApiMessages(
            members = listOf(a), speaker = a, history = history, askUser = false,
        )
        assertEquals("群聊成员：你好", messages[1].content)
    }

    /**
     * 思考块标记为 (think) 与 (/think) 形式（见 MarkdownParser.stripThink 的 openTag/closeTag）。
     * 用字符串拼接构造并逐字符比对，避免源码/工具链层面对字面量的转码歧义。
     */
    @Test
    fun buildApiMessages_stripsDeepThinkingFromHistory() {
        val a = char("a", "阿米娅")
        val thinkText = buildString {
            append(" ")
            append("<").append("think").append(">")
            append("内部推理")
            append(" ").append("<").append("/").append("think").append(">")
            append("正文内容")
        }
        val history = listOf(
            ChatMessage(role = "assistant", content = thinkText, characterId = "a"),
        )
        val messages = GroupChatPromptBuilder.buildApiMessages(
            members = listOf(a), speaker = a, history = history, askUser = false,
        )
        assertEquals("阿米娅：正文内容", messages[1].content)
    }

    @Test
    fun buildApiMessages_takesLastContextMessages() {
        val a = char("a", "阿米娅")
        val history = (1..(AppConfig.GroupChat.MAX_CONTEXT_MESSAGES + 10)).map { i ->
            ChatMessage(role = "user", content = "msg-$i")
        }
        val messages = GroupChatPromptBuilder.buildApiMessages(
            members = listOf(a), speaker = a, history = history, askUser = false,
        )
        // 1 system + MAX_CONTEXT_MESSAGES 条历史
        assertEquals(AppConfig.GroupChat.MAX_CONTEXT_MESSAGES + 1, messages.size)
        assertEquals("msg-11", messages[1].content)
    }

    @Test
    fun askUserDirectiveDiffersFromDiscuss() {
        val a = char("a", "阿米娅")
        val ask = GroupChatPromptBuilder.buildSystemPrompt(listOf(a), a, askUser = true)
        val discuss = GroupChatPromptBuilder.buildSystemPrompt(listOf(a), a, askUser = false)
        assertTrue(ask.contains("提问"))
        assertTrue(discuss.contains("自然地会话"))
        assertTrue(ask != discuss)
    }

    @Test
    fun stripSpeakerPrefix_fullAndHalfWidthColon() {
        val names = listOf("阿米娅", "能天使")
        assertEquals("你好", GroupChatPromptBuilder.stripSpeakerPrefix("阿米娅：你好", names))
        assertEquals("你好", GroupChatPromptBuilder.stripSpeakerPrefix("阿米娅:你好", names))
        assertEquals("你好", GroupChatPromptBuilder.stripSpeakerPrefix("能天使：你好", names))
    }

    @Test
    fun stripSpeakerPrefix_stripsSurroundingQuotes() {
        val names = listOf("阿米娅")
        assertEquals("你好", GroupChatPromptBuilder.stripSpeakerPrefix("\"你好\"", names))
        assertEquals("你好", GroupChatPromptBuilder.stripSpeakerPrefix("「你好」", names))
    }

    @Test
    fun stripSpeakerPrefix_unprefixedTextUntouched() {
        val names = listOf("阿米娅")
        assertEquals("正常说话内容", GroupChatPromptBuilder.stripSpeakerPrefix("正常说话内容", names))
        assertEquals("阿米娅和能天使", GroupChatPromptBuilder.stripSpeakerPrefix("阿米娅和能天使", names))
    }

    // ===== 二轮：@ 指令 / 博士档案 =====

    @Test
    fun buildSystemPrompt_targetedAddsMentionDirectiveOnlyWhenTargeted() {
        val a = char("a", "阿米娅")
        val targeted = GroupChatPromptBuilder.buildSystemPrompt(listOf(a), a, askUser = false, targeted = true)
        val normal = GroupChatPromptBuilder.buildSystemPrompt(listOf(a), a, askUser = false, targeted = false)
        assertTrue(targeted.contains("专门对你说的"))
        assertFalse(normal.contains("专门对你说的"))
    }

    @Test
    fun buildSystemPrompt_userProfileInjectedOnlyWhenPresent() {
        val a = char("a", "阿米娅")
        val with = GroupChatPromptBuilder.buildSystemPrompt(
            listOf(a), a, askUser = false, userPersona = "温和的博士", userRelationship = "战友",
        )
        assertTrue(with.contains("用户（博士）的信息"))
        assertTrue(with.contains("人设：温和的博士"))
        assertTrue(with.contains("他与群成员的关系：战友"))
        val without = GroupChatPromptBuilder.buildSystemPrompt(listOf(a), a, askUser = false)
        assertFalse(without.contains("用户（博士）的信息"))
        assertFalse(without.contains("他与群成员的关系"))
    }

    @Test
    fun extractMentions_orderedByAppearanceAndRestrictedToMembers() {
        val names = listOf("阿米娅", "能天使", "德克萨斯")
        assertEquals(
            listOf("能天使", "阿米娅"),
            GroupChatPromptBuilder.extractMentions("@能天使 来接我 @阿米娅 也来吧 @能天使", names),
        )
        // 非成员不返回
        assertEquals(emptyList<String>(), GroupChatPromptBuilder.extractMentions("@路人甲 你好", names))
        // 名字后紧跟文字（无边界）不误匹配
        assertEquals(emptyList<String>(), GroupChatPromptBuilder.extractMentions("@阿米娅酱 你好", names))
    }

    // ===== 自定义世界观注入 =====

    @Test
    fun buildSystemPrompt_worldviewInjectedNearTopWhenPresent() {
        val a = char("a", "阿米娅")
        val directive = Worldview("id", "末日", "故事发生在末日废土。", WorldviewTargetType.GROUP, "1")
            .directiveText()
        val with = GroupChatPromptBuilder.buildSystemPrompt(
            listOf(a), a, askUser = false, worldviewDirective = directive,
        )
        assertTrue(with.contains("[世界观设定]"))
        assertTrue(with.contains("故事发生在末日废土。"))
        assertTrue(with.contains("请严格遵循以上世界观的设定进行对话"))
        // 世界观在群聊开场白之后、成员人设之前（靠近顶部，权重高）
        val openingEnd = with.indexOf("」。\n") + "」。\n".length
        val personaStart = with.indexOf("以下是群成员人设")
        val worldviewIdx = with.indexOf("[世界观设定]")
        assertTrue(worldviewIdx in openingEnd until personaStart)

        val without = GroupChatPromptBuilder.buildSystemPrompt(listOf(a), a, askUser = false)
        assertFalse(without.contains("[世界观设定]"))
    }

    @Test
    fun buildApiMessages_worldviewFlowsThroughToSystemMessage() {
        val a = char("a", "阿米娅")
        val messages = GroupChatPromptBuilder.buildApiMessages(
            members = listOf(a), speaker = a, history = emptyList(), askUser = false,
            worldviewDirective = "\n[世界观设定]\nW\n请严格遵循以上世界观的设定进行对话。",
        )
        assertEquals("system", messages.first().role)
        assertTrue(messages.first().content.contains("[世界观设定]"))
    }
}