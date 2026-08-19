package com.rhodesisland.terminal.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceConfigTest {

    @Test
    fun voiceOnlyOverrideIsValidAndLegacyResourceIsIgnored() {
        assertNull(VoiceConfig().validationError("中文"))
        assertNull(VoiceConfig("S_voice").validationError("中文"))
        assertNull(VoiceConfig("S_voice", "seed-icl-2.0").validationError("中文"))
        assertEquals(
            "中文 Resource ID 已保存，但缺少音色 ID",
            VoiceConfig("", "seed-icl-2.0").validationError("中文"),
        )
    }

    @Test
    fun onlyApiKeyIsAcceptedForSimpleVoiceCloneConfiguration() {
        assertEquals(TtsAuthMode.API_KEY, TtsConfig(apiKey = "key", defaultVoiceId = "S_default", appId = "id", accessKey = "token").authMode())
        assertEquals(TtsAuthMode.NONE, TtsConfig(appId = "id", accessKey = "token").authMode())
        assertEquals("请填写默认自定义音色 ID（speaker_id）", TtsConfig(apiKey = "key").validationError())
    }
}
