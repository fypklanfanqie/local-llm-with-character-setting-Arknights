package com.rhodesisland.terminal.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L10n 语义与词典完整性单测。
 *
 * 词典是「中文原文即 key」的渐进式覆盖方案，最怕两件事：**查不到（回退中文）** 和
 * **重复 key（静默覆盖）**。前者由回退语义兜底（永不崩），后者由本测试在 CI 阶段拦截。
 */
class L10nTest {

    // ===== 回退语义 =====

    @Test
    fun `missing key falls back to chinese`() {
        val unknown = "这条词条一定不存在于词典里"
        assertEquals(unknown, L10n.t(AppLanguage.EN, unknown))
        assertEquals(unknown, L10n.t(AppLanguage.JA, unknown))
    }

    @Test
    fun `chinese and unresolved system return source text`() {
        val zh = "语言"
        assertEquals(zh, L10n.t(AppLanguage.ZH, zh))
        // SYSTEM 未解析时按中文返回（调用方忘了 resolve 也不会显示空白）
        assertEquals(zh, L10n.t(AppLanguage.SYSTEM, zh))
    }

    @Test
    fun `known key returns translation`() {
        assertEquals("Language", L10n.t(AppLanguage.EN, "语言"))
        assertEquals("言語", L10n.t(AppLanguage.JA, "语言"))
    }

    // ===== 占位符 =====

    @Test
    fun `format replaces placeholders`() {
        assertEquals("Current: 3", L10n.format(AppLanguage.EN, "当前：{0}", 3))
        assertEquals("現在：3", L10n.format(AppLanguage.JA, "当前：{0}", 3))
    }

    @Test
    fun `format keeps missing-key template readable`() {
        val unknown = "已选择 {0} 个角色"
        assertEquals("已选择 5 个角色", L10n.format(AppLanguage.EN, unknown, 5))
    }

    @Test
    fun `format without args still translates`() {
        // 无占位符时等价于 t()：翻译照常生效
        assertEquals("Language", L10n.format(AppLanguage.EN, "语言"))
        assertEquals("这条词条一定不存在于词典里", L10n.format(AppLanguage.EN, "这条词条一定不存在于词典里"))
    }

    @Test
    fun `format tolerates null arg`() {
        assertEquals("已选择  个角色", L10n.format(AppLanguage.EN, "已选择 {0} 个角色", null))
    }

    // ===== SYSTEM 解析 =====

    @Test
    fun `explicit language passes through resolve`() {
        assertEquals(AppLanguage.EN, L10n.resolve(AppLanguage.EN, "zh-Hans-CN"))
        assertEquals(AppLanguage.JA, L10n.resolve(AppLanguage.JA, "en-US"))
        assertEquals(AppLanguage.ZH, L10n.resolve(AppLanguage.ZH, "ja-JP"))
    }

    @Test
    fun `system follows system language tag`() {
        assertEquals(AppLanguage.ZH, L10n.resolve(AppLanguage.SYSTEM, "zh"))
        assertEquals(AppLanguage.ZH, L10n.resolve(AppLanguage.SYSTEM, "zh-Hans-CN"))
        assertEquals(AppLanguage.ZH, L10n.resolve(AppLanguage.SYSTEM, "ZH-Hant-TW"))
        assertEquals(AppLanguage.JA, L10n.resolve(AppLanguage.SYSTEM, "ja"))
        assertEquals(AppLanguage.JA, L10n.resolve(AppLanguage.SYSTEM, "ja-JP"))
        assertEquals(AppLanguage.EN, L10n.resolve(AppLanguage.SYSTEM, "en-US"))
    }

    @Test
    fun `system falls back to english for unsupported locales`() {
        assertEquals(AppLanguage.EN, L10n.resolve(AppLanguage.SYSTEM, "ko"))
        assertEquals(AppLanguage.EN, L10n.resolve(AppLanguage.SYSTEM, "fr-FR"))
        assertEquals(AppLanguage.EN, L10n.resolve(AppLanguage.SYSTEM, ""))
        assertEquals(AppLanguage.EN, L10n.resolve(AppLanguage.SYSTEM, null))
    }

    // ===== 持久化 key =====

    @Test
    fun `fromKey round trips and defaults`() {
        AppLanguage.entries.forEach { assertEquals(it, AppLanguage.fromKey(it.key)) }
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromKey(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromKey(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromKey("klingon"))
    }

    // ===== 词典完整性 =====

    @Test
    fun `english dictionary has no duplicate keys and no blank values`() {
        assertDictionaryHealthy("en", EnEntries, EnStrings)
    }

    @Test
    fun `japanese dictionary has no duplicate keys and no blank values`() {
        assertDictionaryHealthy("ja", JaEntries, JaStrings)
    }

    @Test
    fun `dictionaries stay in sync`() {
        // 两份词典按同一批中文原文推进；缺一边会导致切到该语言时局部回退中文。
        val onlyEn = EnStrings.keys - JaStrings.keys
        val onlyJa = JaStrings.keys - EnStrings.keys
        assertEquals("仅英文收录: $onlyEn", emptySet<String>(), onlyEn)
        assertEquals("仅日文收录: $onlyJa", emptySet<String>(), onlyJa)
    }

    @Test
    fun `has and size agree with the dictionary`() {
        assertEquals(EnStrings.size, L10n.size(AppLanguage.EN))
        assertEquals(JaStrings.size, L10n.size(AppLanguage.JA))
        assertTrue(L10n.has(AppLanguage.EN, "语言"))
        assertFalse(L10n.has(AppLanguage.EN, "这条词条一定不存在于词典里"))
        // 中文无需词典，恒为 true
        assertTrue(L10n.has(AppLanguage.ZH, "这条词条一定不存在于词典里"))
    }

    private fun assertDictionaryHealthy(
        name: String,
        entries: List<Pair<String, String>>,
        map: Map<String, String>,
    ) {
        val duplicates = entries.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
        assertEquals("$name 词典存在重复 key（后者会静默覆盖前者）: $duplicates", emptySet<String>(), duplicates)
        assertEquals("$name 词典有重复 key", entries.size, map.size)
        entries.forEach { (key, value) ->
            assertTrue("$name 词典出现空 key", key.isNotBlank())
            assertTrue("$name 词典空译文: $key", value.isNotBlank())
        }
    }
}
