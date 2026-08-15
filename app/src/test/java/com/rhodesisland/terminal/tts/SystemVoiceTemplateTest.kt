package com.rhodesisland.terminal.tts

import com.rhodesisland.terminal.data.model.SystemVoiceTemplate
import com.rhodesisland.terminal.data.model.TtsLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 系统语音模板匹配纯函数测试。
 *
 * 覆盖：目标语言过滤（zh/ja 前缀）、模板关键词命中、无命中回落首个、无目标语言语音返回 null、
 * 默认模板直接取首个、locale 归一化（大小写/连字符）。
 */
class SystemVoiceTemplateTest {

    private val voices = listOf(
        SystemVoiceInfo("zh-CN-X-XiaoyiNeural", "zh_CN"),
        SystemVoiceInfo("zh-CN-X-YunxiNeural", "zh_CN"),
        SystemVoiceInfo("ja-JP-X-NanamiNeural", "ja_JP"),
        SystemVoiceInfo("en-US-X-AriaNeural", "en_US"),
    )

    @Test
    fun gentleFemale_matchesFemaleKeywordVoice() {
        val matched = matchSystemVoiceForTemplate(voices, SystemVoiceTemplate.GENTLE_FEMALE, TtsLanguage.ZH)
        assertEquals("zh-CN-X-XiaoyiNeural", matched?.name)
    }

    @Test
    fun steadyMale_matchesMaleKeywordVoice() {
        val matched = matchSystemVoiceForTemplate(voices, SystemVoiceTemplate.STEADY_MALE, TtsLanguage.ZH)
        assertEquals("zh-CN-X-YunxiNeural", matched?.name)
    }

    @Test
    fun japaneseOnlyConsidersJaVoices() {
        val matched = matchSystemVoiceForTemplate(voices, SystemVoiceTemplate.GENTLE_FEMALE, TtsLanguage.JA)
        assertEquals("ja-JP-X-NanamiNeural", matched?.name)
    }

    @Test
    fun noVoiceForLanguage_returnsNull() {
        val pool = listOf(SystemVoiceInfo("en-US-X-AriaNeural", "en_US"))
        assertNull(matchSystemVoiceForTemplate(pool, SystemVoiceTemplate.GENTLE_FEMALE, TtsLanguage.ZH))
    }

    @Test
    fun noKeywordHit_fallsBackToFirstLanguageVoice() {
        val pool = listOf(
            SystemVoiceInfo("zh-CN-X-UnknownVoice1", "zh_CN"),
            SystemVoiceInfo("zh-CN-X-UnknownVoice2", "zh_CN"),
        )
        val matched = matchSystemVoiceForTemplate(pool, SystemVoiceTemplate.GENTLE_FEMALE, TtsLanguage.ZH)
        assertEquals("zh-CN-X-UnknownVoice1", matched?.name)
    }

    @Test
    fun defaultTemplate_returnsFirstLanguageVoice() {
        val matched = matchSystemVoiceForTemplate(voices, SystemVoiceTemplate.DEFAULT, TtsLanguage.ZH)
        assertEquals("zh-CN-X-XiaoyiNeural", matched?.name)
    }

    @Test
    fun emptyVoiceList_returnsNull() {
        assertNull(matchSystemVoiceForTemplate(emptyList(), SystemVoiceTemplate.GENTLE_FEMALE, TtsLanguage.ZH))
    }

    @Test
    fun localeNormalization_handlesDashAndCase() {
        assertEquals("zh_cn", normalizeVoiceLocale("zh-CN"))
        assertEquals("zh_cn", normalizeVoiceLocale("ZH_CN"))
        assertEquals("zh_cn", normalizeVoiceLocale(" zh-CN "))
    }
}
