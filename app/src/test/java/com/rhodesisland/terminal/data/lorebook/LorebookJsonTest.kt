package com.rhodesisland.terminal.data.lorebook

import com.rhodesisland.terminal.data.model.Lorebook
import com.rhodesisland.terminal.data.model.LorebookEntry
import com.rhodesisland.terminal.data.model.LorebookInsertPosition
import com.rhodesisland.terminal.data.model.LorebookSecondaryLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LorebookJson] 解析与导出测试：ST 标准 map 形态、数组变体、V2 角色卡内嵌蛇形字段、
 * 容错分支、导出→导入 roundtrip、超限截断。
 */
class LorebookJsonTest {

    @Test
    fun parsesStandardMapForm() {
        val text = """
            {"name":"修仙世界","entries":{
              "0":{"uid":0,"key":["青云宗","玄真子"],"keysecondary":["掌门"],"comment":"青云宗",
                   "content":"十大正派之首","constant":false,"selectiveLogic":2,"order":88,
                   "position":1,"disable":false,"probability":80,"useProbability":true,
                   "depth":6,"scan_depth":5,"case_sensitive":true,"match_whole_words":false,
                   "excludeRecursion":true,"preventRecursion":false},
              "1":{"uid":1,"key":[],"comment":"总纲","content":"世界宪法","constant":true,
                   "position":4,"depth":9,"disable":false}
            }}
        """.trimIndent()
        val result = LorebookJson.parseSillyTavern(text)
        assertTrue(result is LorebookJson.ParseResult.Ok)
        result as LorebookJson.ParseResult.Ok
        assertEquals("修仙世界", result.name)
        assertNull(result.warning)
        assertEquals(2, result.entries.size)

        val first = result.entries.first { it.title == "青云宗" }
        assertEquals(listOf("青云宗", "玄真子"), first.keys)
        assertEquals(listOf("掌门"), first.secondaryKeys)
        assertEquals("十大正派之首", first.content)
        assertEquals(LorebookSecondaryLogic.NOT_ANY, first.logic)
        assertEquals(88, first.order)
        assertEquals(LorebookInsertPosition.AFTER_CHAR, first.position)
        assertEquals(80, first.probability)
        assertEquals(6, first.depth)
        assertEquals(5, first.scanDepthOverride)
        assertTrue(first.caseSensitive)
        assertFalse(first.preventRecursion)
        assertTrue(first.excludeRecursion)

        val second = result.entries.first { it.title == "总纲" }
        assertTrue(second.constant)
        assertEquals(LorebookInsertPosition.AT_DEPTH, second.position)
        assertEquals(9, second.depth)
        assertNull(second.scanDepthOverride)
    }

    @Test
    fun parsesArrayVariant() {
        val text = """{"entries":[{"key":["甲"],"content":"内容甲"},{"key":["乙"],"content":"内容乙"}]}"""
        val result = LorebookJson.parseSillyTavern(text)
        assertTrue(result is LorebookJson.ParseResult.Ok)
        assertEquals(2, (result as LorebookJson.ParseResult.Ok).entries.size)
    }

    @Test
    fun parsesV2CharacterBook() {
        val text = """
            {"data":{"name":"某角色","character_book":{
                "name":"内嵌世界书",
                "entries":[{
                    "keys":["灵石"],"secondary_keys":["货币"],"name":"货币条目",
                    "content":"灵石是通用货币","enabled":false,"insertion_order":55,
                    "case_sensitive":false,
                    "extensions":{"scan_depth":3,"prevent_recursion":true}
                }]
            }}}
        """.trimIndent()
        val result = LorebookJson.parseSillyTavern(text)
        assertTrue(result is LorebookJson.ParseResult.Ok)
        result as LorebookJson.ParseResult.Ok
        assertEquals("内嵌世界书", result.name)
        val e = result.entries.single()
        assertEquals(listOf("灵石"), e.keys)
        assertEquals(listOf("货币"), e.secondaryKeys)
        assertEquals("货币条目", e.title)
        assertEquals(false, e.enabled) // V2 enabled 直读（非 disable 取反）
        assertEquals(55, e.order)
        assertEquals(3, e.scanDepthOverride)
        assertTrue(e.preventRecursion)
        assertEquals(LorebookInsertPosition.AFTER_CHAR, e.position) // V2 无 position：缺省归 AFTER（与 ST 语义一致）
    }

    @Test
    fun positionMappingOnlyExplicitFourIsDepth() {
        fun pos(p: Int): LorebookInsertPosition {
            val r = LorebookJson.parseSillyTavern("""{"entries":[{"key":["k"],"content":"c","position":$p}]}""")
            return (r as LorebookJson.ParseResult.Ok).entries.single().position
        }
        assertEquals(LorebookInsertPosition.BEFORE_CHAR, pos(0))
        assertEquals(LorebookInsertPosition.AFTER_CHAR, pos(1))
        assertEquals(LorebookInsertPosition.AFTER_CHAR, pos(2)) // ↑AN 归 AFTER
        assertEquals(LorebookInsertPosition.AFTER_CHAR, pos(6)) // ↓EM 归 AFTER
        assertEquals(LorebookInsertPosition.AT_DEPTH, pos(4))
    }

    @Test
    fun probabilityFallbacksAndClamp() {
        fun prob(body: String): Int {
            val r = LorebookJson.parseSillyTavern("""{"entries":[{$body}]}""")
            return (r as LorebookJson.ParseResult.Ok).entries.single().probability
        }
        assertEquals(100, prob(""""key":["k"],"content":"c","useProbability":false,"probability":30"""))
        assertEquals(30, prob(""""key":["k"],"content":"c","useProbability":true,"probability":30"""))
        assertEquals(100, prob(""""key":["k"],"content":"c","probability":150"""))
        // 无 probability 字段默认必触发（结尾避免引号：原始字符串以 " 收尾会吞掉终止符）
        assertEquals(100, prob(""""key":["k"],"content":"c","order":5"""))
    }

    @Test
    fun disableInvertsToEnabled() {
        val r = LorebookJson.parseSillyTavern("""{"entries":[{"key":["k"],"content":"c","disable":true}]}""")
        assertEquals(false, (r as LorebookJson.ParseResult.Ok).entries.single().enabled)
    }

    @Test
    fun blankContentEntriesSkipped() {
        val r = LorebookJson.parseSillyTavern(
            """{"entries":[{"key":["a"],"content":"  "},{"key":["b"],"content":"有效"}]}""",
        )
        assertEquals(1, (r as LorebookJson.ParseResult.Ok).entries.size)
    }

    @Test
    fun invalidInputsFail() {
        assertTrue(LorebookJson.parseSillyTavern("not json at all") is LorebookJson.ParseResult.Fail)
        assertTrue(LorebookJson.parseSillyTavern("""{"foo":1}""") is LorebookJson.ParseResult.Fail)
        assertTrue(LorebookJson.parseSillyTavern("""{"entries":{}}""") is LorebookJson.ParseResult.Fail)
    }

    @Test
    fun exportImportRoundtrip() {
        val book = Lorebook(
            id = "lb-x", name = "往返书", enabled = false,
            entries = listOf(
                LorebookEntry(
                    id = "lbe-a", title = "标题", keys = listOf("关键词一", "kw2"),
                    secondaryKeys = listOf("次级"), logic = LorebookSecondaryLogic.NOT_ALL,
                    content = "正文内容", constant = false, enabled = false,
                    position = LorebookInsertPosition.AT_DEPTH, depth = 7, order = 42,
                    probability = 65, caseSensitive = true, matchWholeWords = true,
                    scanDepthOverride = 4, preventRecursion = true, excludeRecursion = true,
                ),
                LorebookEntry(id = "lbe-b", title = "", keys = emptyList(), content = "常驻", constant = true),
            ),
        )
        val json = LorebookJson.toSillyTavernJson(book)
        // scope 是本 app 特有路由概念：ST 格式不含绑定信息，导出内容不随作用域变化
        assertFalse(json.contains("scope"))
        val reparsed = LorebookJson.parseSillyTavern(json) as LorebookJson.ParseResult.Ok
        assertEquals("往返书", reparsed.name)
        assertEquals(2, reparsed.entries.size)

        val a = reparsed.entries[0]
        assertEquals("标题", a.title)
        assertEquals(listOf("关键词一", "kw2"), a.keys)
        assertEquals(listOf("次级"), a.secondaryKeys)
        assertEquals(LorebookSecondaryLogic.NOT_ALL, a.logic)
        assertEquals(false, a.enabled) // export disable=!enabled → reparse 取反还原
        assertEquals(LorebookInsertPosition.AT_DEPTH, a.position)
        assertEquals(7, a.depth)
        assertEquals(42, a.order)
        assertEquals(65, a.probability)
        assertTrue(a.caseSensitive)
        assertTrue(a.matchWholeWords)
        assertEquals(4, a.scanDepthOverride)
        assertTrue(a.preventRecursion)
        assertTrue(a.excludeRecursion)

        val b = reparsed.entries[1]
        assertTrue(b.constant)
        assertNull(b.scanDepthOverride)
    }

    @Test
    fun importOverCapTruncatesWithWarning() {
        val entries = (0 until LorebookJson.MAX_IMPORT_ENTRIES + 1)
            .joinToString(",") { """{"key":["k$it"],"content":"内容$it"}""" }
        val result = LorebookJson.parseSillyTavern("""{"entries":[$entries]}""")
        assertTrue(result is LorebookJson.ParseResult.Ok)
        result as LorebookJson.ParseResult.Ok
        assertEquals(LorebookJson.MAX_IMPORT_ENTRIES, result.entries.size)
        assertNotNull(result.warning)
    }

    /** 导出文件必须能被酒馆再导入：顶层含 entries 且条目含 key/content 基本字段。 */
    @Test
    fun exportHasSillyTavernCompatibleShape() {
        val json = LorebookJson.toSillyTavernJson(
            Lorebook(id = "lb", name = "n", entries = listOf(LorebookEntry(id = "lbe", keys = listOf("k"), content = "c"))),
        )
        assertTrue(json.contains("\"entries\""))
        assertTrue(json.contains("\"key\""))
        assertTrue(json.contains("\"content\""))
        assertTrue(json.contains("\"position\""))
    }
}
