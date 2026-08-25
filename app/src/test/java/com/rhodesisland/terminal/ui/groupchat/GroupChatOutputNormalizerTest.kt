package com.rhodesisland.terminal.ui.groupchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupChatOutputNormalizerTest {

    @Test
    fun stripsThinkingBeforeRemovingExpectedSpeakerPrefix() {
        val result = normalizeGeneratedReply(
            raw = "<think>内部推理</think>阿米娅：你好，博士。",
            expectedSpeakerName = "阿米娅",
            memberNames = listOf("阿米娅", "能天使"),
        )
        assertEquals(GroupChatReplyNormalization.Valid("你好，博士。"), result)
    }

    @Test
    fun rejectsForeignSpeakerPrefixInsteadOfSilentlyRelabelingIt() {
        val result = normalizeGeneratedReply(
            raw = "能天使：这不是阿米娅会说的话。",
            expectedSpeakerName = "阿米娅",
            memberNames = listOf("阿米娅", "能天使"),
        )
        assertEquals(
            GroupChatReplyNormalization.ForeignSpeakerPrefix("能天使"),
            result,
        )
    }

    @Test
    fun rejectsNestedForeignPrefixAfterExpectedPrefix() {
        val result = normalizeGeneratedReply(
            raw = "阿米娅：能天使：错误串台",
            expectedSpeakerName = "阿米娅",
            memberNames = listOf("阿米娅", "能天使"),
        )
        assertTrue(result is GroupChatReplyNormalization.ForeignSpeakerPrefix)
    }

    @Test
    fun emptyThinkingOnlyReplyIsNotPersistable() {
        val result = normalizeGeneratedReply(
            raw = "<think>只有思考</think>",
            expectedSpeakerName = "阿米娅",
            memberNames = listOf("阿米娅"),
        )
        assertEquals(GroupChatReplyNormalization.Empty, result)
    }
}
