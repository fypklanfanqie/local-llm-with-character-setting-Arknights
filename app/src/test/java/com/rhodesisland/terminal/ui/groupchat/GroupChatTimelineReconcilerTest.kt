package com.rhodesisland.terminal.ui.groupchat

import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.DisplayMessage
import com.rhodesisland.terminal.ui.chat.PendingFinal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊时间线协调器测试：逐条按行级 characterId 解析发言人；
 * 乐观完成消息为**列表**（一轮多人答复连续落库），未确认的 pending 全部保留、确认/跨会话的返回移除。
 */
class GroupChatTimelineReconcilerTest {

    private fun nameOf(id: String?) = when (id) {
        "a" -> "阿米娅"
        "b" -> "能天使"
        else -> GroupChatPromptBuilder.FALLBACK_NAME
    }

    private fun historyRow(id: Long, role: String, content: String, characterId: String? = null) =
        ChatMessage(role = role, content = content, databaseId = id, characterId = characterId, timestamp = id)

    private fun pending(messageId: Long, conversationId: Long = 9L, sender: String = "阿米娅", characterId: String = "a") =
        PendingFinal(
            conversationId = conversationId,
            databaseId = messageId,
            message = DisplayMessage(
                id = "msg-$messageId", role = "assistant", content = "pending-$messageId", segments = emptyList(),
                sender = sender, databaseId = messageId, characterId = characterId,
            ),
        )

    @Test
    fun renderHistory_assistantUsesPerMessageSpeaker() {
        val history = listOf(
            historyRow(1, "user", "你们好", characterId = "group_chat"),
            historyRow(2, "assistant", "你好", characterId = "a"),
            historyRow(3, "assistant", "嗨嗨", characterId = "b"),
        )
        val result = GroupChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 9L, pendingFinals = emptyList(), streaming = null,
            speakerNameOf = ::nameOf,
        )
        assertEquals(3, result.messages.size)
        assertEquals("YOU", result.messages[0].sender)
        assertEquals("阿米娅", result.messages[1].sender)
        assertEquals("a", result.messages[1].characterId)
        assertEquals("能天使", result.messages[2].sender)
        assertEquals("b", result.messages[2].characterId)
        assertFalse(result.showWelcome)
        assertTrue(result.resolvedPendingIds.isEmpty())
    }

    @Test
    fun renderHistory_unknownSpeakerFallsBackToGroupMember() {
        val history = listOf(historyRow(2, "assistant", "你好", characterId = "removed"))
        val result = GroupChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 9L, pendingFinals = emptyList(), streaming = null,
            speakerNameOf = ::nameOf,
        )
        assertEquals("群聊成员", result.messages[0].sender)
    }

    @Test
    fun welcomeStateWhenNothingToShow() {
        val result = GroupChatTimelineReconciler.reconcile(
            history = emptyList(), activeConversationId = 9L, pendingFinals = emptyList(), streaming = null,
            speakerNameOf = ::nameOf,
        )
        assertTrue(result.showWelcome)
        assertTrue(result.messages.isEmpty())
    }

    @Test
    fun pendingKeptUntilRoomConfirms() {
        val history = listOf(historyRow(1, "user", "hi", characterId = "group_chat"))
        val result = GroupChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 9L,
            pendingFinals = listOf(pending(2)), streaming = null,
            speakerNameOf = ::nameOf,
        )
        assertEquals(2, result.messages.size)
        assertEquals("msg-2", result.messages[1].id)
        assertTrue(result.resolvedPendingIds.isEmpty())
    }

    @Test
    fun multiPending_allKeptUntilEachConfirmedByRoom() {
        // 一轮 3 条答复已落库，但 Room 快照只回填了其中 1 条：其余两条必须保留展示，不得消失
        val history = listOf(
            historyRow(1, "user", "hi", characterId = "group_chat"),
            historyRow(2, "assistant", "pending-2", characterId = "a"),
        )
        val result = GroupChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 9L,
            pendingFinals = listOf(pending(2), pending(3, sender = "能天使", characterId = "b"), pending(4)),
            streaming = null,
            speakerNameOf = ::nameOf,
        )
        // 已确认的 id=2 返回移除；未确认的 3/4 保留
        assertEquals(setOf(2L), result.resolvedPendingIds)
        val ids = result.messages.map { it.id }
        assertTrue("msg-3" in ids)
        assertTrue("msg-4" in ids)
        assertEquals(1, result.messages.count { it.id == "msg-2" }) // Room 回填版只显示一次
    }

    @Test
    fun pendingFromOtherConversationDropped() {
        val history = listOf(historyRow(1, "user", "hi", characterId = "group_chat"))
        val result = GroupChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 9L,
            pendingFinals = listOf(pending(2, conversationId = 999L)),
            streaming = null,
            speakerNameOf = ::nameOf,
        )
        assertEquals(setOf(2L), result.resolvedPendingIds)
        assertEquals(1, result.messages.size)
    }

    @Test
    fun streamingAppendedOnce() {
        val history = listOf(historyRow(1, "user", "hi", characterId = "group_chat"))
        val streaming = DisplayMessage(
            id = "streaming", role = "streaming", content = "正在", segments = emptyList(),
            sender = "阿米娅", isStreaming = true, characterId = "a",
        )
        val result = GroupChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 9L,
            pendingFinals = emptyList(), streaming = streaming,
            speakerNameOf = ::nameOf,
        )
        assertEquals(2, result.messages.size)
        assertEquals("streaming", result.messages.last().id)
    }
}