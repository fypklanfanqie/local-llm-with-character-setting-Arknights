package com.rhodesisland.terminal.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsVoiceSelectionTest {
    @Test
    fun ChineseAndJapaneseUseOnlyTheirOwnCharacterSpeaker() {
        val map = mapOf("amiya" to VoicePair(VoiceConfig("S_cn"), VoiceConfig("S_ja")))
        assertEquals("S_cn", speakerIdForLanguage("amiya", TtsLanguage.ZH, map))
        assertEquals("S_ja", speakerIdForLanguage("amiya", TtsLanguage.JA, map))
    }

    @Test
    fun JapaneseDoesNotFallBackToChineseSpeaker() {
        val map = mapOf("amiya" to VoicePair(VoiceConfig("S_cn"), VoiceConfig()))
        assertNull(speakerIdForLanguage("amiya", TtsLanguage.JA, map))
    }

    @Test
    fun missingCharacterSpeakerReturnsNull() {
        assertNull(speakerIdForLanguage("unknown", TtsLanguage.ZH, emptyMap()))
    }
}
