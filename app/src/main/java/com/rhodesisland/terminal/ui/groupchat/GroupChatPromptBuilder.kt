package com.rhodesisland.terminal.ui.groupchat

import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.util.MarkdownParser
import com.rhodesisland.terminal.util.PromptWindowAnchor

/**
 * 群聊提示词构建（纯函数，JVM 可测）。
 *
 * OpenAI 兼容 chat 消息的 assistant 角色无法表达「谁在发言」——多说话人靠**名字织进正文**表达：
 * - system：「稳定头」（群聊定位+世界观+世界书静态头+全员人设+规则，不含本轮发言者信息）+
 *   末尾「本轮任务」块（以 X 的身份回话、@ 提醒、提问/接话、只输出 X 说的话本身）。
 *   发言者每轮轮换，若出现在 system 开头则云端 prompt 前缀缓存永不命中；
 *   收敛到尾部增量后，「全员人设」等长稳定前缀可持续复用。
 * - assistant 历史：改写为 `名字：内容`（剥离深度思考）。
 * - user 历史：保持 user（用户=博士）。
 * - 历史窗口经 [PromptWindowAnchor] 锚定截断（cap=40/step=10），避免逐条滑动破坏前缀。
 *
 * 世界书（移植大众版，缓存友好布局）：[lorebookStaticHead] 为常驻条目静态段，拼进 system
 * （内容只随条目编辑变化 → system 逐字节稳定 → 云端前缀缓存复用）；[lorebookTailMessages]
 * （动态命中，每轮不同）拆成独立消息插到最后一条 user 之前——绝不进 system 打破头部缓存。
 *
 * [buildApiMessages] 供 [com.rhodesisland.terminal.ui.groupchat.GroupChatViewModel] 的流式路径
 * （`provider.chat(List<ChatMessage>)`）复用；后台 Worker 再映射为 `ChatMessageDto` 走 `chatOnce`。
 */
object GroupChatPromptBuilder {

    /** 未知成员（已被移出群）的名字兜底。 */
    const val FALLBACK_NAME = "群聊成员"

    /** 构建完整 API 消息序列：system + 尾部最近历史（assistant 带 `名字：` 前缀）+ 世界书动态尾。
     *  [lorebook] 为世界书解析结果（[LorebookEngine.resolve] 产出）：蓝灯段进 system 稳定区、
     *  绿灯尾块折入尾部（末条 user 之前 / 无 user 时追加）；null = 不注入，输出与 legacy 一致。 */
    fun buildApiMessages(
        members: List<Character>,
        speaker: Character,
        history: List<ChatMessage>,
        askUser: Boolean,
        userPersona: String? = null,
        userRelationship: String? = null,
        targeted: Boolean = false,
        worldviewDirective: String? = null,
        lorebook: com.rhodesisland.terminal.llm.LorebookEngine.Resolved? = null,
    ): List<ChatMessage> = buildApiMessages(
        members, speaker, history, askUser, userPersona, userRelationship, targeted, worldviewDirective,
        lorebookStaticHead = lorebook?.stableBeforeChar.orEmpty() +
            (if (lorebook?.stableAfterChar.isNullOrBlank()) "" else "\n" + lorebook!!.stableAfterChar.trim()),
        lorebookTailMessages = listOfNotNull(lorebook?.let { com.rhodesisland.terminal.llm.LorebookEngine.tailBlockMessageOf(it) }),
    )

    /** 重载：显式静态头/动态尾消息（缓存友好布局的底层实现；[buildApiMessages] 的 Resolved 便捷入口委托到此）。 */
    fun buildApiMessages(
        members: List<Character>,
        speaker: Character,
        history: List<ChatMessage>,
        askUser: Boolean,
        userPersona: String? = null,
        userRelationship: String? = null,
        targeted: Boolean = false,
        worldviewDirective: String? = null,
        lorebookStaticHead: String = "",
        lorebookTailMessages: List<ChatMessage> = emptyList(),
    ): List<ChatMessage> {
        val nameById = members.associate { it.id to it.name }
        // 锚定截断（step=10）：溢出量在一个量子块内增长时窗口起点不动，云端前缀缓存可持续复用
        val mappedHistory = PromptWindowAnchor.anchoredWindow(
            history, AppConfig.GroupChat.MAX_CONTEXT_MESSAGES, step = PromptWindowAnchor.GROUP_TRIM_STEP,
        ).mapNotNull { m ->
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
        val systemMessage = ChatMessage(
            role = "system",
            content = buildSystemPrompt(members, speaker, askUser, userPersona, userRelationship, targeted, worldviewDirective, lorebookStaticHead),
        )
        return if (lorebookTailMessages.isEmpty()) {
            buildList {
                add(systemMessage)
                addAll(mappedHistory)
            }
        } else {
            // 动态世界书插在最后一条 user 消息之前（贴近对话、不触碰头部缓存）；
            // mappedHistory 已完成映射，此处插入不会被 mapNotNull 过滤。
            buildList {
                add(systemMessage)
                val lastUserIdx = mappedHistory.indexOfLast { it.role == "user" }
                if (lastUserIdx >= 0) {
                    addAll(mappedHistory.take(lastUserIdx))
                    addAll(lorebookTailMessages)
                    addAll(mappedHistory.drop(lastUserIdx))
                } else {
                    addAll(mappedHistory)
                    addAll(lorebookTailMessages)
                }
            }
        }
    }

    fun buildSystemPrompt(
        members: List<Character>,
        speaker: Character,
        askUser: Boolean,
        userPersona: String? = null,
        userRelationship: String? = null,
        targeted: Boolean = false,
        worldviewDirective: String? = null,
        lorebookStaticHead: String = "",
    ): String = buildString {
        // 稳定头：不含本轮发言者信息——群聊每轮换人，speaker 出现在开头会使云端
        // prompt 前缀缓存每轮全失效。随 speaker/轮次变化的内容全部集中在下方
        // 「本轮任务」块，保住「定位+世界观+世界书静态头+全员人设+规则」这段长稳定前缀。
        append("这是一个罗德岛干员群聊。\n")
        if (!worldviewDirective.isNullOrBlank()) {
            append(worldviewDirective.trim(), "\n")
        }
        // 世界书常驻条目静态段（只随条目编辑变化）：世界观之后、人设之前——稳定头内逐字节稳定
        if (lorebookStaticHead.isNotBlank()) {
            append(lorebookStaticHead.trim(), "\n")
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
        // 变化尾：「本轮任务」块——speaker / targeted / askUser 指令集中于此
        append("————本轮任务————\n")
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