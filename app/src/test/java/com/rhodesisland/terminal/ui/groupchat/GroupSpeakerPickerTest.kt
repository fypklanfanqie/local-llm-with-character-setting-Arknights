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

    // ===== 用户回合答复名单（二轮）=====

    @Test
    fun resolveReplySpeakers_withMentions_returnsOnlyMentionedInOrder() {
        val r = Random(42)
        repeat(30) {
            val picked = GroupSpeakerPicker.resolveReplySpeakers(
                setOf("a", "b", "c", "d", "e"), listOf("c", "a"), r,
            )
            assertEquals(listOf("c", "a"), picked)
        }
    }

    @Test
    fun resolveReplySpeakers_unknownMentionsFiltered_noRandomFill() {
        val r = Random(7)
        repeat(30) {
            val picked = GroupSpeakerPicker.resolveReplySpeakers(setOf("a", "b"), listOf("gone"), r)
            assertTrue("picked=$picked", picked.all { it in setOf("a", "b") } || picked.isEmpty())
        }
        // 全部提及无效 -> 走随机路径
        val picked = GroupSpeakerPicker.resolveReplySpeakers(setOf("a", "b"), listOf("gone"), Random(9))
        assertTrue(picked.isNotEmpty() && picked.size <= 2)
    }

    @Test
    fun resolveReplySpeakers_duplicateMentionsDeduped() {
        assertEquals(
            listOf("b", "a"),
            GroupSpeakerPicker.resolveReplySpeakers(setOf("a", "b"), listOf("b", "a", "b")),
        )
    }

    @Test
    fun resolveReplySpeakers_emptyMembersReturnsEmpty() {
        assertTrue(GroupSpeakerPicker.resolveReplySpeakers(emptySet(), listOf("a")).isEmpty())
    }

    @Test
    fun resolveReplySpeakers_noMentions_betweenOneAndCap() {
        val r = Random(42)
        repeat(30) {
            val picked = GroupSpeakerPicker.resolveReplySpeakers(setOf("a", "b", "c", "d", "e"), emptyList(), r)
            assertTrue("size=${picked.size}", picked.size in 1..4)
            assertEquals(picked.size, picked.toSet().size)
        }
    }

    @Test
    fun resolveReplySpeakers_noMentions_cappedByMemberCount() {
        assertEquals(1, GroupSpeakerPicker.resolveReplySpeakers(setOf("a"), emptyList(), Random(1)).size)
        repeat(20) {
            val picked = GroupSpeakerPicker.resolveReplySpeakers(setOf("a", "b"), emptyList(), Random(it.toLong()))
            assertTrue("size=${picked.size}", picked.size in 1..2)
        }
    }
}