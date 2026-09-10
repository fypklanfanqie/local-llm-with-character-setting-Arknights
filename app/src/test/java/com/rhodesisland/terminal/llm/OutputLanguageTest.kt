package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.i18n.AppLanguage
import com.rhodesisland.terminal.i18n.L10nRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输出语言约束随界面语言联动：选 English → 角色用英文回答，选 日本語 → 用日文。
 *
 * 人设/世界观/世界书仍是中文设定，只在 system prompt 尾部追加一条输出语言约束，
 * 因此这里断言的是「指令指向的语言」，而不是把提示词整体翻译掉。
 */
class OutputLanguageTest {

    @After
    fun restore() {
        L10nRuntime.update(AppLanguage.ZH, "zh")
    }

    @Test
    fun `chinese and unresolved system use the original chinese directive`() {
        assertEquals(OutputLanguage.ZH_DIRECTIVE, OutputLanguage.directiveFor(AppLanguage.ZH))
        assertEquals(OutputLanguage.ZH_DIRECTIVE, OutputLanguage.directiveFor(AppLanguage.SYSTEM))
        assertTrue(OutputLanguage.ZH_DIRECTIVE.contains("简体中文"))
    }

    @Test
    fun `english ui asks the model to answer in english`() {
        val d = OutputLanguage.directiveFor(AppLanguage.EN)
        assertTrue("英文约束应点名 English", d.contains("English"))
        assertTrue("英文约束必须覆盖思考过程", d.contains("思考"))
        assertTrue("人设仍是中文设定，不应要求翻译人设", d.contains("中文设定"))
    }

    @Test
    fun `japanese ui asks the model to answer in japanese`() {
        val d = OutputLanguage.directiveFor(AppLanguage.JA)
        assertTrue("日文约束应点名 日本語", d.contains("日本語"))
        assertTrue("日文约束必须覆盖思考过程", d.contains("思考"))
    }

    @Test
    fun `three directives are distinct and non-blank`() {
        val all = listOf(OutputLanguage.ZH_DIRECTIVE, OutputLanguage.EN_DIRECTIVE, OutputLanguage.JA_DIRECTIVE)
        all.forEach { assertTrue("指令不能为空", it.isNotBlank()) }
        assertEquals("三种语言的约束必须互不相同", 3, all.toSet().size)
        assertNotEquals(OutputLanguage.ZH_DIRECTIVE, OutputLanguage.EN_DIRECTIVE)
        assertNotEquals(OutputLanguage.ZH_DIRECTIVE, OutputLanguage.JA_DIRECTIVE)
    }

    @Test
    fun `current follows the runtime language cache`() {
        L10nRuntime.update(AppLanguage.EN, "en")
        assertEquals(OutputLanguage.EN_DIRECTIVE, OutputLanguage.current())

        L10nRuntime.update(AppLanguage.JA, "ja")
        assertEquals(OutputLanguage.JA_DIRECTIVE, OutputLanguage.current())

        L10nRuntime.update(AppLanguage.SYSTEM, "zh-Hans-CN")
        assertEquals(OutputLanguage.ZH_DIRECTIVE, OutputLanguage.current())

        L10nRuntime.update(AppLanguage.SYSTEM, "en-US")
        assertEquals("跟随系统 + 系统英文 → 英文回答", OutputLanguage.EN_DIRECTIVE, OutputLanguage.current())
    }

    @Test
    fun `narration name follows the selected language`() {
        assertEquals("简体中文", OutputLanguage.narrationName(AppLanguage.ZH))
        assertEquals("简体中文", OutputLanguage.narrationName(AppLanguage.SYSTEM))
        assertEquals("英文（English）", OutputLanguage.narrationName(AppLanguage.EN))
        assertEquals("日文（日本語）", OutputLanguage.narrationName(AppLanguage.JA))

        // 无参重载读运行期缓存：与 OutputLanguage.current() 同一语言源
        L10nRuntime.update(AppLanguage.EN, "en")
        assertEquals("英文（English）", OutputLanguage.narrationName())
        L10nRuntime.update(AppLanguage.JA, "ja")
        assertEquals("日文（日本語）", OutputLanguage.narrationName())
    }
}
