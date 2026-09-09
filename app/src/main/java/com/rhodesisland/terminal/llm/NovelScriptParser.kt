package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.config.AppConfig

/**
 * 小说模式脚本解析（纯函数，JVM 可测）。
 *
 * AI 续写输出 → 结构化脚本行。容错策略：
 * - 剥 `<think>` 思考段、markdown 代码围栏、粗体/井号场景标题行；
 * - `旁白：` / 主控名 / 已知角色名 + 冒号（全/半角）开头 → 对应类型；
 * - 无前缀行并入上一行（续行）；未知前缀整行归旁白（不丢弃内容）。
 */
object NovelScriptParser {

    const val TYPE_NARRATION = "narration"
    const val TYPE_USER = "user"
    const val TYPE_CHARACTER = "character"

    /** 解析产物行（speakerName 为显示名快照；characterId 由调用方按名字回填）。 */
    data class ScriptLine(
        val speakerType: String,
        val speakerName: String,
        val characterId: String?,
        val content: String,
    )

    private val thinkRegex = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val fenceRegex = Regex("```[a-zA-Z]*\\n?([\\s\\S]*?)```")
    private val headerRegex = Regex("^[#*>\\-\\s]*(?:第[0-9一二三四五六七八九十百千]+[话章节幕])?.*[#*>\\-\\s]*$") // l10n:ignore 非界面文案（提示词/正则/内部消息，仅日志或经映射）

    /**
     * 解析 AI 输出为脚本行列表。
     *
     * @param knownSpeakers 参与角色名集合（含自定义 NPC）
     * @param protagonistName 主控显示名（{{user}} 的替换名）；空串表示无主控
     */
    fun parse(raw: String, knownSpeakers: Set<String>, protagonistName: String): List<ScriptLine> {
        var text = thinkRegex.replace(raw, "")
        // 剥代码围栏（保留内部内容）
        fenceRegex.findAll(text).forEach { m ->
            text = text.replace(m.value, m.groupValues[1])
        }
        val lines = mutableListOf<ScriptLine>()
        text.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            // 剔除 markdown 装饰行（围栏残余/粗体/井号标题/分隔线）
            if (line.startsWith("#") || line.startsWith("*") || line.startsWith(">") ||
                line.startsWith("---") || line.startsWith("```") ||
                (line.startsWith("**") && line.endsWith("**"))
            ) return@forEach

            val colonIdx = line.indexOfFirst { it == '：' || it == ':' }
            if (colonIdx in 1..20) {
                val speaker = line.substring(0, colonIdx).trim()
                val content = line.substring(colonIdx + 1).trim()
                when {
                    speaker == "旁白" -> {
                        if (content.isNotEmpty()) lines += ScriptLine(TYPE_NARRATION, "旁白", null, content)
                        return@forEach
                    }
                    protagonistName.isNotBlank() && speaker == protagonistName.trim() -> {
                        if (content.isNotEmpty()) lines += ScriptLine(TYPE_USER, speaker, null, content)
                        return@forEach
                    }
                    knownSpeakers.any { it == speaker } -> {
                        if (content.isNotEmpty()) {
                            lines += ScriptLine(TYPE_CHARACTER, speaker, null, content)
                        }
                        return@forEach
                    }
                    // 未知前缀：整行（含前缀）归旁白，不丢内容
                }
            }
            // 无前缀行：并入上一行（续行）；首行无前缀 → 新旁白
            if (lines.isEmpty()) {
                lines += ScriptLine(TYPE_NARRATION, "旁白", null, line)
            } else {
                val last = lines.last()
                lines[lines.size - 1] = last.copy(content = last.content + "\n" + line)
            }
        }
        return lines
    }
}
