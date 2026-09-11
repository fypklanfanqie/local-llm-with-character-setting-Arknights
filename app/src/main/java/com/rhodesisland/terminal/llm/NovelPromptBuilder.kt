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
        directed: DirectedLine? = null,
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
        if (directed != null) appendDirectedLine(directed)
        append("\n\n请续写 ")
        append(AppConfig.Novel.CONTINUE_MIN_LINES)
        append("-")
        append(AppConfig.Novel.CONTINUE_MAX_LINES)
        append(" 行剧情：")
        if (directed != null) {
            // 明确「从哪一行之后接着写」：否则模型容易把用户刚写的那行当成待补全的上下文而复述它
            append("从「")
            append(directed.speakerName)
            append("：")
            append(directed.content.trim().take(40))
            append("…」之后接着写，")
        } else {
            append("从新内容开始，")
        }
        append("不要重复已有正文，不要总结。")
    }

    /**
     * 用户在编辑器里**亲自写下**的一行（含发言人身份）。
     *
     * 为什么需要它：用户选角色发言的意图是「按这个角色的路子把剧情推下去」，
     * 而不是「往正文里塞一行字」。只把这一行混在 [已有正文] 里，模型既可能当作
     * 普通上下文一笔带过，也可能 OOC 地让这个角色马上改口——所以单独成段给出推进要求。
     */
    data class DirectedLine(
        /** narration | user | character（与 NovelLineEntity.speakerType 同口径）。 */
        val speakerType: String,
        val speakerName: String,
        val content: String,
        /** 该发言人在阵容里的定位：protagonist / supporting / null（未设定阵容）。 */
        val speakerRole: String? = null,
    )

    /**
     * 「用户主导的一步」段落：把用户这一行变成推进剧情的起点，并按发言身份给出差异化要求。
     *
     * 三种身份语义不同：角色发言要贴人设（用户选 A 就按 A 的写法走）、旁白是场景指令
     * （当作既成事实）、主控是用户自己的行动（角色按人设回应）。配角额外提醒不要抢主线，
     * 与 system 里的 [角色阵容] 主次约定一致。
     */
    private fun StringBuilder.appendDirectedLine(line: DirectedLine) {
        val content = line.content.trim()
        if (content.isEmpty()) return
        append("\n\n[用户刚写下的一行 · 本次要顺着它推进]")
        append("\n")
        append(line.speakerName)
        append("：")
        append(content)
        append("\n推进要求：")
        when (line.speakerType) {
            "character" -> {
                append("\n- 这一行是「")
                append(line.speakerName)
                append("」亲口说的，是他的既定立场：他后续的言行必须严格贴合其人设")
                append("（性格、语气、立场、说话习惯），不得 OOC、不得自相矛盾，也不要让他马上改口或反悔。")
                append("\n- 让剧情由这一行往下走：其他角色按各自人设做出反应（赞同、反对、追问、沉默皆可），")
                append("并引出新的行动、信息或冲突，而不是原地重复当前气氛。")
                if (line.speakerRole == "supporting") {
                    append("\n- 他是本作配角：推进时戏份保持克制，不要抢主角的主导权，但要让主线确实向前一步。")
                } else if (line.speakerRole == "protagonist") {
                    append("\n- 他是本作主角：剧情重心与描写视角围绕他展开，这一行应当成为本段推进的主轴。")
                }
            }
            "narration" -> {
                append("\n- 这一行是用户写的旁白/场景描写：把它当作已经发生的事实，从这个场景继续写下去。")
                append("\n- 角色按各自人设对这个场景变化做出反应，推动剧情前进。")
            }
            else -> {
                append("\n- 这一行是主控（用户本人）说的/做的：各角色按各自人设回应，围绕他的行动推进主线。")
            }
        }
        append("\n- 不要复述用户这一行，不要解释或总结它；直接写接下来的新内容。")
    }
}
