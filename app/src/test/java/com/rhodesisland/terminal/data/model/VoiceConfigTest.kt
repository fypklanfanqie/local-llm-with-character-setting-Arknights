package com.rhodesisland.terminal.data.model

import com.rhodesisland.terminal.manager.selectVoiceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceConfigTest {

    @Test
    fun voiceBindingRequiresVoiceAndResourceTogether() {
        assertNull(VoiceConfig().validationError("中文"))
        assertNull(VoiceConfig("S_voice", "seed-icl-2.0").validationError("中文"))
        assertEquals(
            "中文音色已填写，但缺少对应 Resource ID",
            VoiceConfig("S_voice", "").validationError("中文"),
        )
        assertEquals(
            "中文 Resource ID 已填写，但缺少对应音色 ID",
            VoiceConfig("", "seed-icl-2.0").validationError("中文"),
        )
    }

    @Test
    fun apiKeyIsPreferredOverLegacyCredentials() {
        assertEquals(TtsAuthMode.API_KEY, TtsConfig(apiKey = "key", appId = "id", accessKey = "token").authMode())
        assertEquals(TtsAuthMode.LEGACY, TtsConfig(appId = "id", accessKey = "token").authMode())
        assertEquals(TtsAuthMode.NONE, TtsConfig(appId = "id").authMode())
    }

    @Test
    fun selectVoiceConfigUsesLanguageSpecificBinding() {
        val pair = VoicePair(
            zh = VoiceConfig("S_cn", "seed-icl-2.0"),
            ja = VoiceConfig("S_ja", "seed-icl-1.0"),
        )

        assertEquals(VoiceConfig("S_cn", "seed-icl-2.0"), selectVoiceConfig(pair, TtsLanguage.ZH))
        assertEquals(VoiceConfig("S_ja", "seed-icl-1.0"), selectVoiceConfig(pair, TtsLanguage.JA))
    }
}
