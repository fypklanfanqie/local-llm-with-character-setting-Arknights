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
     * @param castProtagonists 主角显示名（应用角色名或自定义 NPC 名），**可多个也可为空**（群像）
     * @param castSupporting 配角显示名
     * @param castRelations 主角之间的关系描述（自由文本；空 = 未填写）
     */
    fun buildSystem(
        background: String,
        characterSheets: List<String>,
        protagonistName: String,
        protagonistPersona: String,
        castProtagonists: List<String> = emptyList(),
        castSupporting: List<String> = emptyList(),
        castRelations: String = "",
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
        appendCastSection(castProtagonists, castSupporting, castRelations)
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
        castRelations: String = "",
    ) {
        val leads = castProtagonists.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val rests = castSupporting.map { it.trim() }.filter { it.isNotEmpty() && it !in leads }.distinct()
        if (leads.isEmpty() && rests.isEmpty()) return

        append("\n\n[角色阵容]")
        if (leads.isNotEmpty()) {
            // 多主角合法（双主/群像）：叙述视角在主角间轮换，主线共同推动
            append("\n- 【主角】")
            append(leads.joinToString("、"))
            append("：叙事以他们为中心，环境、动作与心理描写尽量从他们的视角展开，主线由他们共同推动，")
            append("每次续写都要有他们的行动或感受。")
        }
        if (rests.isNotEmpty()) {
            append("\n- 【配角】")
            append(rests.joinToString("、"))
            append("：服务于主线，可以推动情节、提供信息或制造冲突，但戏份克制，")
            append("不要喧宾夺主，不要替主角做决定。")
        }
        // 用户明确选择「不设主角」：群像推进，禁止 AI 自行虚构一个主导角色
        if (leads.isEmpty()) {
            append("\n- 【群像】本篇不设单一主角：各角色按人设轮流推动剧情，戏份均衡，不要虚构新的主导角色。")
        }
        val relations = castRelations.trim()
        if (relations.isNotEmpty() && leads.isNotEmpty()) {
            append("\n- 【主角关系】")
            append(relations)
            append("：写对手戏时体现这层关系带来的默契、张力与分寸。")
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
     * 为什么需要它：用户选角色发言的意图是「让剧情顺着这句话走」，
     * 而不是「往正文里塞一行字」。单独成段给出推进要求，模型才不会无视它
     * 或 OOC 地替这个角色继续发挥。
     */
    data class DirectedLine(
        /** narration | user | character（与 NovelLineEntity.speakerType 同口径）。 */
        val speakerType: String,
        val speakerName: String,
        val content: String,
        /** 该发言人在阵容里的定位：protagonist / supporting / null（未设置或群像）。 */
        val speakerRole: String? = null,
        /** 用户用 @ 点名要回应的角色名（仅主控/我 发言时使用；未被点名者不出声）。 */
        val mentioned: List<String> = emptyList(),
    )

    /**
     * 「用户主导的一步」段落。
     *
     * 核心规则（用户需求）：**AI 绝不代写用户刚发出的那个声音**——
     * - 用户以角色 A 发言：A 这句话是既定表态，AI 只写其他角色与旁白如何接话推进；
     * - 用户以主控（我）发言：AI 只写其他角色的回应；@ 点名谁就只有谁出声，其他角色沉默；
     * - 用户写旁白：当作既成事实的场景指令。
     * 角色 A 的人设依然约束「别人如何反应」，但 A 本身不再被 AI 代笔。
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
                append("\n- 「")
                append(line.speakerName)
                append("」由用户亲自扮演：接下来**绝对不要替 ")
                append(line.speakerName)
                append(" 写任何新的台词、动作或心理**，这一行就是他的全部表态。")
                append("\n- 只写其他角色与旁白：他们按各自人设对这句话做出反应（赞同、反对、追问、沉默皆可），")
                append("并引出新的行动、信息或冲突。反应的分寸要与 ")
                append(line.speakerName)
                append(" 的身份立场相称。")
                if (line.speakerRole == "supporting") {
                    append("\n- 他是本作配角：推进时戏份保持克制，不要抢主角的主导权，但要让主线确实向前一步。")
                } else if (line.speakerRole == "protagonist") {
                    append("\n- 他是本作主角：主线仍围绕他的处境展开，但此刻通过其他角色与环境来体现，不要用他的台词填充篇幅。")
                }
            }
            "narration" -> {
                append("\n- 这一行是用户写的旁白/场景描写：把它当作已经发生的事实，从这个场景继续写下去。")
                append("\n- 角色按各自人设对这个场景变化做出反应，推动剧情前进。")
            }
            else -> {
                append("\n- 这一行是主控（用户本人）说的/做的：接下来**绝对不要替主控写任何新的台词或行动**，")
                append("不要输出任何以「")
                append(line.speakerName)
                append("：」开头的新行。")
                if (line.mentioned.isNotEmpty()) {
                    append("\n- 只有被 @ 点名的角色出声回应本行：")
                    append(line.mentioned.joinToString("、"))
                    append("。未被点名的角色保持沉默（可以以在场动作/表情存在，但不得说话推动剧情）。")
                } else {
                    append("\n- 各角色按各自人设回应主控，围绕他的行动推进主线。")
                }
            }
        }
        append("\n- 不要复述用户这一行，不要解释或总结它；直接写接下来的新内容。")
    }

    /**
     * 主控推荐回复：围绕当前剧情生成 4 条「我」可能的发言供用户挑选。
     *
     * 用户需求：AI 不主动替主控写台词——只有用户主动点「推荐」时才生成候选，
     * 且生成的是**候选文本**（不落库），用户选中后以主控身份插入。
     */
    fun buildReplySuggestions(
        protagonistName: String,
        protagonistPersona: String,
        script: List<NovelScriptParser.ScriptLine>,
    ): String = buildString {
        append("以下是互动小说的当前剧情，主控角色（用户本人）是「")
        append(protagonistName.trim())
        append("」。")
        if (protagonistPersona.isNotBlank()) {
            append("主控设定：")
            append(protagonistPersona.trim())
            append("。")
        }
        append("\n\n[已有剧情]")
        script.takeLast(AppConfig.Novel.MAX_CONTEXT_LINES).forEach {
            append("\n")
            append(it.speakerName)
            append("：")
            append(it.content)
        }
        append("\n\n请站在主控的视角，生成 4 条「")
        append(protagonistName)
        append("」接下来最可能的发言：")
        append("\n- 每条一行，以「1.」「2.」「3.」「4.」开头，编号后直接是要说的话")
        append("\n- 每条不超过 40 字，第一人称，贴合主控设定与当前剧情情境")
        append("\n- 4 条风格要有差异（如推进剧情 / 谨慎试探 / 幽默缓和 / 强硬表态）")
        append("\n- 只输出这 4 条，不要解释，不要写其他角色的反应，不要续写剧情")
    }
}
