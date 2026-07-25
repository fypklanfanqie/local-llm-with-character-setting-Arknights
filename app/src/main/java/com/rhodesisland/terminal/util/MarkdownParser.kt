package com.rhodesisland.terminal.util

import com.rhodesisland.terminal.data.model.FormulaToken
import com.rhodesisland.terminal.data.model.MessageSegment
import com.rhodesisland.terminal.data.model.Token

/**
 * Markdown / 代码高亮解析器
 *
 * 完整迁移自小程序 utils/codeHighlight.js
 * 支持：
 * - 纯文本段
 * - 代码块（C/Python，VS Code Dark+ 配色）
 * - 物化公式块（上下标、希腊字母）
 */
object MarkdownParser {

    // VS Code Dark+ 颜色
    val COLORS = mapOf(
        "keyword" to "#569CD6",
        "string" to "#CE9178",
        "comment" to "#6A9955",
        "number" to "#B5CEA8",
        "function" to "#DCDCAA",
        "type" to "#4EC9B0",
        "macro" to "#C586C0",
        "default" to "#D4D4D4",
    )

    private val SCIENCE_COLORS = mapOf(
        "normal" to "#E8E4E0",
        "sub" to "#7EC8E3",
        "sup" to "#E8A87C",
        "greek" to "#B5EAD7",
        "operator" to "#FFB7B2",
        "number" to "#C7CEEA",
        "unit" to "#FFDAC1",
    )

    private val SCIENCE_LANGS = setOf("physics", "chemistry", "formula", "math", "science")

    private val C_KEYWORDS = setOf(
        "auto","break","case","const","continue","default","do","else","enum","extern",
        "for","goto","if","register","return","signed","sizeof","static","struct","switch",
        "typedef","union","unsigned","volatile","while"
    )
    private val C_TYPES = setOf(
        "int","char","float","double","void","long","short","size_t","FILE","NULL","true","false","bool",
        "int8_t","int16_t","int32_t","int64_t","uint8_t","uint16_t","uint32_t","uint64_t"
    )
    private val C_FUNCTIONS = setOf(
        "printf","scanf","malloc","free","calloc","realloc","fopen","fclose","fread","fwrite",
        "fprintf","fscanf","sprintf","sscanf","strlen","strcpy","strcmp","strcat","memcpy","memset",
        "memmove","memcmp","atoi","atof","atol","exit","abs","rand","srand","sqrt","pow","sin","cos",
        "tan","ceil","floor","getchar","putchar","gets","puts","perror","main","qsort","bsearch"
    )
    private val C_MACROS = setOf(
        "include","define","ifdef","ifndef","if","else","elif","endif","pragma","error","undef","line"
    )
    private val PY_KEYWORDS = setOf(
        "False","None","True","and","as","assert","async","await","break","class","continue","def",
        "del","elif","else","except","finally","for","from","global","if","import","in","is","lambda",
        "nonlocal","not","or","pass","raise","return","try","while","with","yield"
    )
    private val PY_BUILTINS = setOf(
        "print","len","range","int","str","float","list","dict","set","tuple","input","open","type",
        "isinstance","hasattr","getattr","setattr","super","self","cls","enumerate","zip","map","filter",
        "sorted","reversed","any","all","max","min","sum","abs","round","ord","chr","bin","hex","oct",
        "format","next","iter","id","dir","vars","help","exec","eval","compile","__init__","__name__",
        "__main__","__file__","__str__"
    )

    /**
     * 解析消息内容为分段列表
     */
    fun parseContent(content: String?): List<MessageSegment> {
        if (content.isNullOrBlank()) return listOf(MessageSegment.Text(""))

        val segments = mutableListOf<MessageSegment>()
        val regex = Regex("""```([A-Za-z0-9_+#-]*)\n?([\s\S]*?)```""")
        var lastIdx = 0

        for (match in regex.findAll(content)) {
            // 代码块前的纯文本
            if (match.range.first > lastIdx) {
                val textBefore = content.substring(lastIdx, match.range.first).trim()
                if (textBefore.isNotEmpty()) {
                    segments.add(MessageSegment.Text(textBefore))
                }
            }

            val language = match.groupValues[1].lowercase().trim()
            val rawCode = match.groupValues[2]

            if (language in SCIENCE_LANGS) {
                // 物化公式块
                val trimmed = rawCode.trim()
                val firstLine = trimmed.lines().firstOrNull() ?: ""
                segments.add(MessageSegment.Science(
                    language = language,
                    rawCode = trimmed,
                    firstLine = firstLine,
                    lines = parseFormula(trimmed),
                ))
            } else {
                // 代码块
                var lang = language
                if (lang.isEmpty()) lang = detectLanguage(rawCode)
                if (lang == "c++" || lang == "cpp") lang = "c"
                if (lang == "py") lang = "python"

                val trimmed = rawCode.trim()
                val firstLine = trimmed.lines().firstOrNull() ?: ""
                segments.add(MessageSegment.Code(
                    language = lang,
                    rawCode = trimmed,
                    firstLine = firstLine,
                    lines = tokenize(trimmed, lang),
                ))
            }

            lastIdx = match.range.last + 1
        }

        // 剩余文本
        if (lastIdx < content.length) {
            val textAfter = content.substring(lastIdx).trim()
            if (textAfter.isNotEmpty()) {
                segments.add(MessageSegment.Text(textAfter))
            }
        }

        return if (segments.isEmpty()) listOf(MessageSegment.Text("")) else segments
    }

    /**
     * 解析消息内容：先把 <think>...</think> 抽成 Think 段，其余文本再走 [parseContent]。
     * 流式时（isStreaming=true）非思考部分用纯 Text 段（与原流式行为一致）；
     * 未闭合的 <think> 也作为 Think 段（streaming=true）输出，边生成边可折叠查看。
     */
    fun parseWithThink(content: String?, isStreaming: Boolean): List<MessageSegment> {
        if (content.isNullOrBlank()) return listOf(MessageSegment.Text(""))

        val segments = mutableListOf<MessageSegment>()
        val openTag = "<think>"
        val closeTag = "</think>"
        var idx = 0
        while (idx < content.length) {
            val thinkStart = content.indexOf(openTag, idx)
            if (thinkStart < 0) {
                addTextPart(segments, content.substring(idx), isStreaming)
                break
            }
            if (thinkStart > idx) {
                addTextPart(segments, content.substring(idx, thinkStart), isStreaming)
            }
            val contentStart = thinkStart + openTag.length
            val thinkEnd = content.indexOf(closeTag, contentStart)
            if (thinkEnd < 0) {
                segments.add(MessageSegment.Think(content.substring(contentStart).trim(), streaming = true))
                break
            }
            segments.add(MessageSegment.Think(content.substring(contentStart, thinkEnd).trim(), streaming = false))
            idx = thinkEnd + closeTag.length
        }
        return if (segments.isEmpty()) listOf(MessageSegment.Text("")) else segments
    }

    /**
     * 移除 <think>...</think> 思考块（含未闭合的 <think>... 至结尾），返回剩余文本。
     * 用途：深度思考关闭时隐藏思考过程；云端历史回传 API 前剥离思考（reasoning 不应回传给对话商）。
     */
    fun stripThink(content: String?): String {
        if (content.isNullOrBlank()) return ""
        val sb = StringBuilder()
        var idx = 0
        val openTag = "<think>"
        val closeTag = "</think>"
        while (idx < content.length) {
            val start = content.indexOf(openTag, idx)
            if (start < 0) {
                sb.append(content.substring(idx))
                break
            }
            sb.append(content.substring(idx, start))
            val end = content.indexOf(closeTag, start + openTag.length)
            if (end < 0) break  // 未闭合：丢弃至结尾
            idx = end + closeTag.length
        }
        return sb.toString().trim()
    }

    private fun addTextPart(segments: MutableList<MessageSegment>, text: String, isStreaming: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (isStreaming) {
            segments.add(MessageSegment.Text(trimmed))
        } else {
            segments.addAll(parseContent(trimmed))
        }
    }

    // ===== 代码分词 =====

    fun tokenize(code: String, language: String): List<List<Token>> {
        val isC = language == "c" || language == "cpp" || language == "c++"
        val isPy = language == "python" || language == "py"

        if (!isC && !isPy) {
            return code.split("\n").map { listOf(Token(it, COLORS["default"]!!)) }
        }

        val lines = code.split("\n")
        return lines.map { line ->
            if (isC && line.trimStart().startsWith("#")) {
                listOf(Token(line, COLORS["macro"]!!))
            } else {
                tokenizeLine(line, if (isC) "c" else "python")
            }
        }
    }

    private fun tokenizeLine(line: String, lang: String): List<Token> {
        if (line.isEmpty()) return listOf(Token("", COLORS["default"]!!))

        val tokens = mutableListOf<Token>()
        val regex = if (lang == "c") {
            Regex("""0x[0-9a-fA-F]+|\d+\.?\d*(?:[eE][+-]?\d+)?|\b[a-zA-Z_]\w*\b|#[ \t]*\w+|::|->|>=|<=|!=|==|\+=|-=|\*=|/=|%=|&&|\|\||[(){}\[\];,:.*&|^~!<>=+\-/%]""")
        } else {
            Regex("""0x[0-9a-fA-F]+|\d+\.?\d*(?:[eE][+-]?\d+)?|\b[a-zA-Z_]\w*\b|@\w+|>=|<=|!=|==|\+=|-=|\*=|/=|%=|\*\*|:=|->|&&|\|\||[(){}\[\];,:.*&|^~!<>=+\-/%]""")
        }

        var lastIdx = 0
        for (match in regex.findAll(line)) {
            if (match.range.first > lastIdx) {
                tokens.add(Token(line.substring(lastIdx, match.range.first), COLORS["default"]!!))
            }
            val text = match.value
            val color = classifyToken(text, lang)
            tokens.add(Token(text, color))
            lastIdx = match.range.last + 1
        }
        if (lastIdx < line.length) {
            tokens.add(Token(line.substring(lastIdx), COLORS["default"]!!))
        }

        return if (tokens.isEmpty()) listOf(Token("", COLORS["default"]!!)) else tokens
    }

    private fun classifyToken(text: String, lang: String): String {
        if (text.matches(Regex("""\d.*""")) || text.startsWith("0x", ignoreCase = true)) {
            return COLORS["number"]!!
        }
        if (lang == "c" && text.startsWith("#")) return COLORS["macro"]!!
        if (text.matches(Regex("""[a-zA-Z_].*"""))) {
            if (lang == "c") {
                if (text in C_TYPES) return COLORS["type"]!!
                if (text in C_KEYWORDS) return COLORS["keyword"]!!
                if (text in C_FUNCTIONS) return COLORS["function"]!!
                if (text in C_MACROS) return COLORS["macro"]!!
                if (text == text.uppercase() && text.length > 1) return COLORS["type"]!!
            } else {
                if (text == "self" || text == "cls") return COLORS["keyword"]!!
                if (text in PY_KEYWORDS) return COLORS["keyword"]!!
                if (text in PY_BUILTINS) return COLORS["function"]!!
            }
        }
        return COLORS["default"]!!
    }

    private fun detectLanguage(code: String): String {
        val c = code.trim()
        if (c.contains(Regex("""#include\b"""))) return "c"
        if (c.contains(Regex("""#define\b"""))) return "c"
        if (c.contains(Regex("""\bint\s+main\b"""))) return "c"
        if (c.contains(Regex("""\bprintf\b"""))) return "c"
        if (c.contains(Regex("""\bdef\s+\w+\s*\("""))) return "python"
        if (c.contains(Regex("""\bimport\s+\w+"""))) return "python"
        if (c.contains(Regex("""\bprint\("""))) return "python"
        if (c.contains(Regex("""\bself\b"""))) return "python"
        return ""
    }

    // ===== 物化公式解析 =====

    fun parseFormula(content: String): List<List<FormulaToken>> {
        return content.split("\n").map { line ->
            if (line.isBlank()) {
                listOf(FormulaToken(" ", SCIENCE_COLORS["normal"]!!, "normal"))
            } else {
                tokenizeFormulaLine(line)
            }
        }
    }

    private fun tokenizeFormulaLine(line: String): List<FormulaToken> {
        val tokens = mutableListOf<FormulaToken>()
        var i = 0

        while (i < line.length) {
            // 上标 ^{...}
            if (line[i] == '^' && i + 1 < line.length && line[i + 1] == '{') {
                val end = line.indexOf('}', i + 2)
                if (end > i) {
                    tokens.add(FormulaToken(line.substring(i + 2, end), SCIENCE_COLORS["sup"]!!, "sup"))
                    i = end + 1
                    continue
                }
            }
            // 下标 _{...}
            if (line[i] == '_' && i + 1 < line.length && line[i + 1] == '{') {
                val end = line.indexOf('}', i + 2)
                if (end > i) {
                    tokens.add(FormulaToken(line.substring(i + 2, end), SCIENCE_COLORS["sub"]!!, "sub"))
                    i = end + 1
                    continue
                }
            }
            // 简化上标 ^x
            if (line[i] == '^' && i + 1 < line.length && line[i + 1] != '{') {
                tokens.add(FormulaToken(line[i + 1].toString(), SCIENCE_COLORS["sup"]!!, "sup"))
                i += 2
                continue
            }
            // 简化下标 _x
            if (line[i] == '_' && i + 1 < line.length && line[i + 1] != '{') {
                tokens.add(FormulaToken(line[i + 1].toString(), SCIENCE_COLORS["sub"]!!, "sub"))
                i += 2
                continue
            }
            // 运算符
            val operators = "→⇌±≈≠≤≥·∂∫ΣΠ√∝∞°′″Å"
            if (line[i].toString() in operators) {
                tokens.add(FormulaToken(line[i].toString(), SCIENCE_COLORS["operator"]!!, "normal"))
                i++
                continue
            }
            // 希腊字母
            val charCode = line[i].code
            if ((charCode in 0x0391..0x03C9) || (charCode in 0x1F00..0x1FFF)) {
                tokens.add(FormulaToken(line[i].toString(), SCIENCE_COLORS["greek"]!!, "normal"))
                i++
                continue
            }
            // 连续数字
            if (line[i].isDigit()) {
                val start = i
                while (i < line.length && (line[i].isDigit() || line[i] == '.')) i++
                tokens.add(FormulaToken(line.substring(start, i), SCIENCE_COLORS["number"]!!, "normal"))
                continue
            }
            // 普通字符
            val start = i
            while (i < line.length &&
                line[i] != '^' && line[i] != '_' &&
                !line[i].isDigit() &&
                line[i].toString() !in operators &&
                !((line[i].code in 0x0391..0x03C9) || (line[i].code in 0x1F00..0x1FFF))
            ) {
                i++
            }
            if (i > start) {
                tokens.add(FormulaToken(line.substring(start, i), SCIENCE_COLORS["normal"]!!, "normal"))
            } else {
                // 兜底：未匹配任何规则的孤立字符（行尾的 ^/_、或未闭合的 ^{ ），按普通字符消费并强制前进，避免死循环
                tokens.add(FormulaToken(line[i].toString(), SCIENCE_COLORS["normal"]!!, "normal"))
                i++
            }
        }

        return if (tokens.isEmpty()) listOf(FormulaToken(" ", SCIENCE_COLORS["normal"]!!, "normal")) else tokens
    }
}
