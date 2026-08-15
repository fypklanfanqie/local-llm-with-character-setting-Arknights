package com.rhodesisland.terminal.ui.chat

import com.rhodesisland.terminal.ui.chat.ChatAutoScrollPolicy.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatAutoScrollPolicy] 状态机契约测试（Task 3）。
 *
 * 覆盖：初始跟随、会话切换重置、接近/离开底部、回到底部按钮的显隐与恢复。
 */
class ChatAutoScrollPolicyTest {

    @Test
    fun initial_followBottomTrue_noReturnButton() {
        val s = State()
        assertTrue(s.followBottom)
        assertFalse(s.showReturnToBottom)
    }

    @Test
    fun conversationChanged_resetsToFollowing() {
        // 用户先上滑离开底部
        val away = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = false)
        assertFalse(away.followBottom)
        assertTrue(away.showReturnToBottom)
        // 切换会话：重置跟随
        val reset = ChatAutoScrollPolicy.onConversationChanged(away)
        assertTrue(reset.followBottom)
        assertFalse(reset.showReturnToBottom)
    }

    @Test
    fun settleNearBottom_keepsFollowing() {
        val s = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = true)
        assertTrue(s.followBottom)
        assertFalse(s.showReturnToBottom)
    }

    @Test
    fun settleAwayFromBottom_pausesAndShowsReturnButton() {
        val s = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = false)
        assertFalse("离开底部后仍自动跟随", s.followBottom)
        assertTrue("离开底部后未显示回到底部按钮", s.showReturnToBottom)
    }

    @Test
    fun returnToBottom_resumesFollowingAndHidesButton() {
        val away = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = false)
        val restored = ChatAutoScrollPolicy.onReturnToBottom(away)
        assertTrue(restored.followBottom)
        assertFalse(restored.showReturnToBottom)
    }
}
