package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.config.AppConfig

/**
 * 小说模式提示词构建（纯函数，JVM 可测）。
 *
 * system 稳定区 = 体例指令 + 故事背景 + 角色卡 + 主控 + 语言约束（内容只随故事设置变化）；
 * user = 章节上下文（标题/摘要/开场白/要求）+ 已有正文窗口 + 续写指令。
 */
object NovelPromptBuilder {

    /** 体例与输出格式约束（system 固定头）。 */
    private const val STYLE_CONTRACT =
        "你是一部互动小说的执笔者。每次输出若干行剧情，格式严格遵守：\n" +
            "- 旁白：环境、动作、心理描写（第三人称）\n" +
            "- 角色名：该角色的对白或第一人称行动\n" +
            "每行以「名字：」或「旁白：」开头（冒号用全角），一行一个动作/一句对白，" +
            "禁止 markdown、场景标题、括号注解、旁白以外的元叙述。"

    /**
     * 组装 system（稳定区：故事设置不变则逐字节稳定）。
     *
     * @param castProtagonists 主角显示名（应用角色名或自定义 NPC 名），可多个
     * @param castSupporting 配角显示名
     */
    fun buildSystem(
        background: String,
        characterSheets: List<String>,
        protagonistName: String,
        protagonistPersona: String,
        castProtagonists: List<String> = emptyList(),
        castSupporting: List<String> = emptyList(),
    ): String = buildString {
        append(STYLE_CONTRACT)
        if (background.isNotBlank()) {
            append("\n\n[故事背景]\n")
            append(background.trim())
        }
        if (characterSheets.isNotEmpty()) {
            append("\n\n[角色设定]")
            characterSheets.forEach { sheet ->
                append("\n- ")
                append(sheet.take(AppConfig.Novel.PERSONA_MAX_CHARS))
            }
        }
        if (protagonistName.isNotBlank()) {
            append("\n\n[主控角色] 名字：")
            append(protagonistName.trim())
            if (protagonistPersona.isNotBlank()) {
                append("。设定：")
                append(protagonistPersona.trim().take(AppConfig.Novel.PERSONA_MAX_CHARS))
            }
            append("。主控的对白/行动以「")
            append(protagonistName.trim())
            append("：」开头输出。")
        }
        appendCastSection(castProtagonists, castSupporting)
        append(OutputLanguage.current())
    }

    /**
     * 角色阵容段落：把「谁是主角谁是配角」写进 system 稳定区。
     *
     * 为什么放在 system：阵容只随故事设置变化，与每轮章节上下文/正文窗口无关——
     * 放稳定区才能命中服务端前缀缓存，且改了阵容才会刷新缓存（这正是我们要的）。
     * 两个名单都为空时不输出任何内容（老故事/未设置阵容的提示词逐字节不变）。
     */
    private fun StringBuilder.appendCastSection(
        castProtagonists: List<String>,
        castSupporting: List<String>,
    ) {
        val leads = castProtagonists.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val rests = castSupporting.map { it.trim() }.filter { it.isNotEmpty() && it !in leads }.distinct()
        if (leads.isEmpty() && rests.isEmpty()) return

        append("\n\n[角色阵容]")
        if (leads.isNotEmpty()) {
            append("\n- 【主角】")
            append(leads.joinToString("、"))
            append("：叙事以他为中心，环境、动作与心理描写尽量从他的视角展开，主线由他推动，")
            append("每次续写都要有他的行动或感受。")
        }
        if (rests.isNotEmpty()) {
            append("\n- 【配角】")
            append(rests.joinToString("、"))
            append("：服务于主线，可以推动情节、提供信息或制造冲突，但戏份克制，")
            append("不要喧宾夺主，不要替主角做决定。")
        }
    }

    /** 组装 user（章节上下文 + 正文窗口 + 续写指令）。 */
    fun buildUser(
        chapterTitle: String,
        summary: String,
        opening: String,
        requirements: String,
        script: List<NovelScriptParser.ScriptLine>,
    ): String = buildString {
        append("[本话] ")
        append(chapterTitle.ifBlank { "未命名" })
        if (summary.isNotBlank()) {
            append("\n[前情/本话摘要] ")
            append(summary.trim())
        }
        if (opening.isNotBlank()) {
            append("\n[开场白] ")
            append(opening.trim())
        }
        if (requirements.isNotBlank()) {
            append("\n[发生、发展、结果与写作要求] ")
            append(requirements.trim())
        }
        append("\n\n[已有正文]")
        val window = script.takeLast(AppConfig.Novel.MAX_CONTEXT_LINES)
        if (window.isEmpty()) {
            append("（暂无，从开场写起）")
        } else {
            window.forEach { line ->
                append("\n")
                append(line.speakerName)
                append("：")
                append(line.content)
            }
        }
        append("\n\n请续写 ")
        append(AppConfig.Novel.CONTINUE_MIN_LINES)
        append("-")
        append(AppConfig.Novel.CONTINUE_MAX_LINES)
        append(" 行剧情：从新内容开始，不要重复已有正文，不要总结。")
    }
}
