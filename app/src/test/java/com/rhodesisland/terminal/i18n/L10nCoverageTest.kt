package com.rhodesisland.terminal.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 词典覆盖度门禁：源码里每一个被 `t("中文")` / `tf(...)` / `L10nRuntime.t(...)` 包装的
 * 中文字面量，都必须在 En/Ja 两份词典里有对应词条。
 *
 * 为什么要有这个测试：中央词典是「中文原文即 key」的，**包了 `t()` 却忘了加词条**时不会报错，
 * 只会在切到英/日时静默回退中文——线上很难发现。`tools/l10n-coverage.ps1` 是人工度量工具，
 * 这个测试把它变成 CI 门禁。
 *
 * 规则（与脚本口径一致）：
 * - 跳过 `i18n/dict`（词典自身）、`config/`（角色人设）、文件名含 `Prompt` 的提示词文件；
 * - 跳过带 `l10n:ignore` 标记的行（提示词片段、比较值、崩溃日志等刻意不翻译项）；
 * - 跳过同一行里属于 `Log.*` 调用的字符串（调试日志不翻译）。
 */
class L10nCoverageTest {

    private val cjk = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf\\u3040-\\u30ff]")

    /** 已包装调用 + 中文字面量（字面量原样保留转义，便于与词典 key 直接比对）。 */
    private val wrapped = Regex(
        """(?<![\w.])(?:t|tf|L10n\.t|L10n\.format|L10nRuntime\.t|L10nRuntime\.format)\("((?:[^"\\]|\\.)*)"""",
    )

    @Test
    fun `every wrapped chinese literal has english and japanese entries`() {
        val root = sourceRoot()
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.replace('\\', '/').contains("/i18n/dict/") }
            .filterNot { it.path.replace('\\', '/').contains("/config/") }
            .filterNot { it.name.contains("Prompt") }
            // i18n 框架自身（L10n.kt / Dictionary.kt）的 KDoc 里有用法示例，不是界面文案
            .filterNot { it.parentFile?.name == "i18n" }
            .toList()

        assertTrue("未找到任何 Kotlin 源文件，路径解析可能有误: $root", files.isNotEmpty())

        val missingEn = sortedSetOf<String>()
        val missingJa = sortedSetOf<String>()

        files.forEach { file ->
            file.readLines().forEach { line ->
                if (line.contains("l10n:ignore")) return@forEach
                if (line.contains("Log.")) return@forEach
                wrapped.findAll(line).forEach { match ->
                    // 源码里是转义写法（\n / \" / \\），词典 key 是运行期值 → 先还原再查表
                    val key = unescape(match.groupValues[1])
                    if (!cjk.containsMatchIn(key)) return@forEach
                    if (!EnStrings.containsKey(key)) missingEn += "${file.name}: $key"
                    if (!JaStrings.containsKey(key)) missingJa += "${file.name}: $key"
                }
            }
        }

        assertTrue(
            "以下中文文案包了 t() 但英文词典缺词条（切到英文会回退中文）:\n" + missingEn.joinToString("\n"),
            missingEn.isEmpty(),
        )
        assertTrue(
            "以下中文文案包了 t() 但日文词典缺词条（切到日文会回退中文）:\n" + missingJa.joinToString("\n"),
            missingJa.isEmpty(),
        )
    }

    /** Kotlin 字符串转义 → 运行期值（只需覆盖 t() 文案里会出现的几种）。 */
    private fun unescape(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    'r' -> append('\r')
                    '"' -> append('"')
                    '\'' -> append('\'')
                    '\\' -> append('\\')
                    '$' -> append('$')
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** 单测的工作目录是模块目录（app/）；向上找一级兜底，兼容不同运行方式。 */
    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/rhodesisland/terminal"),
            File("app/src/main/java/com/rhodesisland/terminal"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，cwd=${File(".").absolutePath}")
    }
}
