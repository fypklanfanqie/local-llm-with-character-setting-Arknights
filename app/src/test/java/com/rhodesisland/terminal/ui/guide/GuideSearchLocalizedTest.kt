package com.rhodesisland.terminal.ui.guide

import com.rhodesisland.terminal.i18n.AppLanguage
import com.rhodesisland.terminal.i18n.L10nRuntime
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指南搜索的多语言回归测试：数据层是中文原文，但搜索要能匹配**当前界面语言**的译文，
 * 否则英文/日文用户按译文关键词搜不到任何话题（曾经的已知限制）。
 */
class GuideSearchLocalizedTest {

    @After
    fun restoreChinese() {
        L10nRuntime.update(AppLanguage.ZH, "zh")
    }

    @Test
    fun `chinese keyword matches in chinese ui`() {
        L10nRuntime.update(AppLanguage.ZH, "zh")
        assertTrue("中文关键词「快速上手」应命中", searchGuideTopics("快速上手").isNotEmpty())
    }

    @Test
    fun `english keyword matches translated title in english ui`() {
        L10nRuntime.update(AppLanguage.EN, "en")
        val hits = searchGuideTopics("Quick start")
        assertTrue("英文界面下按译文标题「Quick start」应命中", hits.isNotEmpty())
        // 中文原文仍应可搜（数据层未变）
        assertTrue("英文界面下中文关键词仍应命中", searchGuideTopics("快速上手").isNotEmpty())
    }

    @Test
    fun `japanese keyword matches translated title in japanese ui`() {
        L10nRuntime.update(AppLanguage.JA, "ja")
        val hits = searchGuideTopics("クイックスタート")
        assertTrue("日文界面下按译文标题应命中", hits.isNotEmpty())
    }

    @Test
    fun `ascii alias still matches in any language`() {
        L10nRuntime.update(AppLanguage.EN, "en")
        assertTrue("ASCII 别名（deepseek）应始终命中", searchGuideTopics("deepseek").isNotEmpty())
    }
}
