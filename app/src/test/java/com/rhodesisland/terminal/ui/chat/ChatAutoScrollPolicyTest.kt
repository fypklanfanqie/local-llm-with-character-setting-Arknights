package com.rhodesisland.terminal.ui.chat

import com.rhodesisland.terminal.ui.chat.ChatAutoScrollPolicy.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatAutoScrollPolicy] 状态机契约测试（Task 3）+ 跟随锚点契约（滚动回退 bug 修复）。
 *
 * 覆盖：初始跟随、会话切换重置、接近/离开底部、回到底部按钮的显隐与恢复、
 * 锚点校验（快速上翻落定瞬间恰逢内容增长时不得误跟随）。
 */
class ChatAutoScrollPolicyTest {

    @Test
    fun initial_followBottomTrue_noReturnButton() {
        val s = State()
        assertTrue(s.followBottom)
        assertFalse(s.showReturnToBottom)
        assertNull(s.followAnchorTotal)
    }

    @Test
    fun conversationChanged_resetsToFollowing() {
        // 用户先上滑离开底部
        val away = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = false, totalItems = 10)
        assertFalse(away.followBottom)
        assertTrue(away.showReturnToBottom)
        // 切换会话：重置跟随
        val reset = ChatAutoScrollPolicy.onConversationChanged(away)
        assertTrue(reset.followBottom)
        assertFalse(reset.showReturnToBottom)
    }

    @Test
    fun settleNearBottom_keepsFollowingAndAnchorsTotal() {
        val s = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = true, totalItems = 12)
        assertTrue(s.followBottom)
        assertFalse(s.showReturnToBottom)
        org.junit.Assert.assertEquals(12, s.followAnchorTotal)
    }

    @Test
    fun settleAwayFromBottom_pausesAndShowsReturnButton() {
        val s = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = false, totalItems = 10)
        assertFalse("离开底部后仍自动跟随", s.followBottom)
        assertTrue("离开底部后未显示回到底部按钮", s.showReturnToBottom)
    }

    @Test
    fun returnToBottom_resumesFollowingAndHidesButton() {
        val away = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = false, totalItems = 10)
        val restored = ChatAutoScrollPolicy.onReturnToBottom(away)
        assertTrue(restored.followBottom)
        assertFalse(restored.showReturnToBottom)
    }

    // ===== 跟随锚点校验（shouldFollowBottom）=====

    @Test
    fun shouldFollow_noAnchor_meansAnchored_follows() {
        // 初始状态（从未 settle 过）：视为已锚定，允许跟随
        assertTrue(ChatAutoScrollPolicy.shouldFollowBottom(State(), totalItems = 5, lastVisibleIndex = 0))
    }

    @Test
    fun shouldFollow_pausedByUser_neverFollows() {
        val away = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = false, totalItems = 20)
        assertFalse(ChatAutoScrollPolicy.shouldFollowBottom(away, totalItems = 25, lastVisibleIndex = 3))
    }

    @Test
    fun shouldFollow_anchorVisible_afterContentGrowth_follows() {
        // 锚定时 10 条；内容长到 12 条，视口仍能看到旧末项（index 9）→ 跟随
        val anchored = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = true, totalItems = 10)
        assertTrue(ChatAutoScrollPolicy.shouldFollowBottom(anchored, totalItems = 12, lastVisibleIndex = 9))
    }

    @Test
    fun shouldFollow_anchorScrolledOut_rejectsFollow() {
        // 修复场景：快速上翻落定瞬间（策略还没切到暂停）恰逢 chunk 到达。
        // 锚定时 10 条，用户已翻到 index 2（旧末项 index 9 已滚出视口）→ 拒绝跟随
        val anchored = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = true, totalItems = 10)
        assertFalse(ChatAutoScrollPolicy.shouldFollowBottom(anchored, totalItems = 10, lastVisibleIndex = 2))
    }

    @Test
    fun shouldFollow_emptyList_rejects() {
        val anchored = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = true, totalItems = 10)
        assertFalse(ChatAutoScrollPolicy.shouldFollowBottom(anchored, totalItems = 0, lastVisibleIndex = 0))
    }

    @Test
    fun shouldFollow_anchorBeyondTotal_listReset_treatsAsAnchored() {
        // 锚点 10，列表被重置成 3 条（如清空重载）：锚点失效，视为已锚定
        val anchored = ChatAutoScrollPolicy.onScrollSettled(State(), isNearBottom = true, totalItems = 10)
        assertTrue(ChatAutoScrollPolicy.shouldFollowBottom(anchored, totalItems = 3, lastVisibleIndex = 2))
    }
}
