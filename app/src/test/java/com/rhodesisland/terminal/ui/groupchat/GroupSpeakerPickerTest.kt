package com.rhodesisland.terminal.ui.groupchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 群成员 round-robin 发言者选择纯函数测试。
 */
class GroupSpeakerPickerTest {

    @Test
    fun pick_emptySetReturnsNull() {
        assertNull(GroupSpeakerPicker.pick(emptySet(), null))
    }

    @Test
    fun pick_singleMemberAlwaysReturnsIt() {
        assertEquals("a", GroupSpeakerPicker.pick(setOf("a"), null))
        assertEquals("a", GroupSpeakerPicker.pick(setOf("a"), "a"))
    }

    @Test
    fun pick_cyclesAllMembersInStableOrder() {
        val ids = setOf("a", "b", "c")
        val first = GroupSpeakerPicker.pick(ids, null) // 随机起点
        val second = GroupSpeakerPicker.pick(ids, first)
        val third = GroupSpeakerPicker.pick(ids, second)
        val fourth = GroupSpeakerPicker.pick(ids, third)
        // 严格轮询：每个成员恰好出现一次后回到起点
        assertEquals(setOf("a", "b", "c"), setOf(first, second, third))
        assertEquals(first, fourth)
    }

    @Test
    fun pick_resumesAfterLastSpeaker() {
        val ids = setOf("a", "b")
        // 排序后 [a, b]；上次 a -> 下次 b
        assertEquals("b", GroupSpeakerPicker.pick(ids, "a"))
        assertEquals("a", GroupSpeakerPicker.pick(ids, "b"))
    }

    @Test
    fun pick_removedLastSpeakerDegradesToRandomStart() {
        val ids = setOf("a", "b")
        val picked = GroupSpeakerPicker.pick(ids, "gone")
        assertTrue(picked in ids)
    }

    // ===== 随机多人答复（二轮）=====

    @Test
    fun randomReplyCount_noMentions_betweenOneAndCap() {
        val r = Random(42)
        repeat(30) {
            val n = GroupSpeakerPicker.randomReplyCount(10, 0, r)
            assertTrue("n=$n", n in 1..4)
        }
    }

    @Test
    fun randomReplyCount_cappedByMemberCount() {
        assertEquals(1, GroupSpeakerPicker.randomReplyCount(1, 0, Random(1)))
        repeat(20) {
            val n = GroupSpeakerPicker.randomReplyCount(2, 0, Random(it.toLong()))
            assertTrue("n=$n", n in 1..2)
        }
    }

    @Test
    fun randomReplyCount_withMentions_atLeastMentionCount() {
        val r = Random(7)
        repeat(30) {
            val n = GroupSpeakerPicker.randomReplyCount(10, 2, r)
            assertTrue("n=$n", n in 2..4)
        }
    }

    @Test
    fun randomReplyCount_mentionsOverCapClampedToCap() {
        assertEquals(4, GroupSpeakerPicker.randomReplyCount(10, 6, Random(3)))
    }

    @Test
    fun pickRandom_targetsFirstThenRandomFill_noDuplicates() {
        val r = Random(5)
        val picked = GroupSpeakerPicker.pickRandom(setOf("a", "b", "c", "d", "e"), listOf("c", "a"), 4, r)
        assertEquals(listOf("c", "a"), picked.take(2))
        assertEquals(4, picked.size)
        assertEquals(picked.size, picked.toSet().size)
    }

    @Test
    fun pickRandom_unknownTargetIgnored_countShortfallFillsFromRest() {
        val r = Random(9)
        val picked = GroupSpeakerPicker.pickRandom(setOf("a", "b"), listOf("gone"), 2, r)
        assertEquals(2, picked.size)
        assertEquals(setOf("a", "b"), picked.toSet())
    }
}