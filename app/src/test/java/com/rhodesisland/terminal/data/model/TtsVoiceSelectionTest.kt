package com.rhodesisland.terminal.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsVoiceSelectionTest {
    @Test
    fun characterOverrideWinsOverDefault() {
        val map = mapOf("amiya" to VoicePair(VoiceConfig("S_character"), VoiceConfig()))
        assertEquals("S_character", effectiveVoiceId("amiya", TtsLanguage.ZH, map, "S_default"))
    }

    @Test
    fun missingCharacterOverrideFallsBackToDefaultForBothLanguages() {
        val map = mapOf("amiya" to VoicePair(VoiceConfig(""), VoiceConfig()))
        assertEquals("S_default", effectiveVoiceId("amiya", TtsLanguage.ZH, map, "S_default"))
        assertEquals("S_default", effectiveVoiceId("amiya", TtsLanguage.JA, map, "S_default"))
    }

    @Test
    fun blankDefaultAndOverrideReturnsNull() {
        assertNull(effectiveVoiceId("unknown", TtsLanguage.ZH, emptyMap(), ""))
    }
}
