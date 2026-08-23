package com.rhodesisland.terminal.ui.groupchat

import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.util.MarkdownParser

/**
 * 群聊提示词构建（纯函数，JVM 可测）。
 *
 * OpenAI 兼容 chat 消息的 assistant 角色无法表达「谁在发言」——多说话人靠**名字织进正文**表达：
 * - system：一次注入「这是罗德岛干员群聊」+ 全员人设（截断）+「以 X 的身份回话，只输出 X 说的话本身」。
 * - assistant 历史：改写为 `名字：内容`（剥离深度思考）。
 * - user 历史：保持 user（用户=博士）。
 *
 * [buildApiMessages] 供 [com.rhodesisland.terminal.ui.groupchat.GroupChatViewModel] 的流式路径
 * （`provider.chat(List<ChatMessage>)`）复用；后台 Worker 再映射为 `ChatMessageDto` 走 `chatOnce`。
 */
object GroupChatPromptBuilder {

    /** 未知成员（已被移出群）的名字兜底。 */
    const val FALLBACK_NAME = "群聊成员"

    /** 构建完整 API 消息序列：system + 尾部最近历史（assistant 带 `名字：` 前缀）。 */
    fun buildApiMessages(
        members: List<Character>,
        speaker: Character,
        history: List<ChatMessage>,
        askUser: Boolean,
        userPersona: String? = null,
        userRelationship: String? = null,
        targeted: Boolean = false,
        worldviewDirective: String = "",
    ): List<ChatMessage> {
        val nameById = members.associate { it.id to it.name }
        val mappedHistory = history.takeLast(AppConfig.GroupChat.MAX_CONTEXT_MESSAGES).mapNotNull { m ->
            val clean = MarkdownParser.stripThink(m.content).trim()
            if (clean.isEmpty()) return@mapNotNull null
            when (m.role) {
                "user" -> ChatMessage(role = "user", content = clean)
                "assistant" -> {
                    val name = m.characterId?.let { nameById[it] } ?: FALLBACK_NAME
                    ChatMessage(role = "assistant", content = "$name：$clean")
                }
                else -> null
            }
        }
        return buildList {
            add(ChatMessage(role = "system", content = buildSystemPrompt(members, speaker, askUser, userPersona, userRelationship, targeted, worldviewDirective)))
            addAll(mappedHistory)
        }
    }

    fun buildSystemPrompt(
        members: List<Character>,
        speaker: Character,
        askUser: Boolean,
        userPersona: String? = null,
        userRelationship: String? = null,
        targeted: Boolean = false,
        worldviewDirective: String = "",
    ): String = buildString {
        append("这是一个罗德岛干员群聊。你在群里扮演「", speaker.name, "」。\n")
        if (worldviewDirective.isNotBlank()) {
            append(worldviewDirective.trim(), "\n")
        }
        append("以下是群成员人设：\n")
        members.forEach { m ->
            append("- ", m.name, "（", m.role, "）：", m.systemPrompt.take(AppConfig.GroupChat.PERSONA_MAX_CHARS), "\n")
        }
        append("对话规则：\n")
        append("- user 发言是用户（博士）说的。\n")
        append("- assistant 消息均以「名字：」开头，表示该成员发言。\n")
        if (!userPersona.isNullOrBlank() || !userRelationship.isNullOrBlank()) {
            append("用户（博士）的信息：")
            if (!userPersona.isNullOrBlank()) append("人设：", userPersona.trim(), "。")
            if (!userRelationship.isNullOrBlank()) append("他与群成员的关系：", userRelationship.trim(), "。")
            append("\n")
        }
        append("现在请你以「", speaker.name, "」的身份回一条消息。\n")
        if (targeted) {
            append("注意：用户这条消息 @ 了你，是专门对你说的，请务必回应。\n")
        }
        if (askUser) {
            append("直接向群里的用户（博士）提问，自然得像群聊里 @ 人，不要太突然。\n")
        } else {
            append("接着上一句自然地会话，可以回应 / 吐槽 / 补充其他成员，不要抢所有人风头。\n")
        }
        append("只输出", speaker.name, "要说的话本身：不要角色名前缀、不要引号、不要任何解释，1-3 句话。")
    }

    /**
     * 从用户消息文本里按出现顺序提取被 @ 的成员名（只看群成员；`@名字` 后须是边界，避免 `@名字X` 误匹配）。
     */
    fun extractMentions(text: String, memberNames: List<String>): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        memberNames.forEach { name ->
            var idx = text.indexOf("@$name")
            while (idx >= 0) {
                val after = idx + 1 + name.length
                val boundaryOk = after >= text.length || !text[after].isLetterOrDigit()
                if (boundaryOk) found.add(idx to name)
                idx = text.indexOf("@$name", idx + 1)
            }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    /**
     * 剥掉模型偶尔带头输出的「名字：」前缀（全角/半角冒号）与包裹引号。
     * 云端无本地 [com.rhodesisland.terminal.llm.IncrementalScriptDetector] 剧本截断，此处做轻量后处理。
     */
    fun stripSpeakerPrefix(text: String, memberNames: List<String>): String {
        var result = text.trim()
        for (name in memberNames) {
            for (colon in listOf("：", ":")) {
                val prefix = "$name$colon"
                if (result.startsWith(prefix)) {
                    result = result.removePrefix(prefix).trim()
                }
            }
        }
        var out = result.trim()
        if (out.length >= 2 && out.startsWith("\"") && out.endsWith("\"")) out = out.substring(1, out.length - 1)
        if (out.length >= 2 && out.startsWith("「") && out.endsWith("」")) out = out.substring(1, out.length - 1)
        return out.trim()
    }
}