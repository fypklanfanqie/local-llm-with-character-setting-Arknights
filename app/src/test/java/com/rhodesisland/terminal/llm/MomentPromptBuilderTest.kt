package com.rhodesisland.terminal.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MomentPromptBuilder] 契约测试：发圈/评论回复提示词的关键约束。
 */
class MomentPromptBuilderTest {

    @Test
    fun postUserMessage_containsJsonContract_andCaptionRules() {
        val msg = MomentPromptBuilder.buildPostUserMessage("阿米娅", "用户：早上好", imageCount = 2)
        assertTrue(msg.contains("阿米娅"))
        assertTrue(msg.contains("{\"caption\""))
        assertTrue(msg.contains("imagePrompt"))
        assertTrue(msg.contains("早上好"))
        // 带图要求
        assertTrue(msg.contains("英文生图提示词"))
    }

    @Test
    fun postUserMessage_zeroImages_requiresEmptyImagePrompt() {
        val msg = MomentPromptBuilder.buildPostUserMessage("德克萨斯", "", imageCount = 0)
        assertTrue(msg.contains("imagePrompt 填空字符串"))
        assertFalse(msg.contains("英文生图提示词"))
    }

    @Test
    fun imageGenMessage_mentionsCountAndReference() {
        val msg = MomentPromptBuilder.buildImageGenUserMessage("a sunny park", 2)
        assertTrue(msg.contains("2 张"))
        assertTrue(msg.contains("参考图"))
        assertTrue(msg.contains("a sunny park"))
    }

    @Test
    fun replyPrompt_directsFirstPersonReply() {
        val msg = MomentPromptBuilder.buildReplyPrompt("今天好累", "辛苦了")
        assertTrue(msg.contains("今天好累"))
        assertTrue(msg.contains("辛苦了"))
        assertTrue(msg.contains("只输出回复正文"))
    }

    @Test
    fun replyPromptForUserPost_notesImages() {
        val withImg = MomentPromptBuilder.buildReplyPromptForUserPost("晒猫", hasImages = true, commentContent = "好可爱")
        assertTrue(withImg.contains("附了几张照片"))
        val noImg = MomentPromptBuilder.buildReplyPromptForUserPost("晒猫", hasImages = false, commentContent = "好可爱")
        assertFalse(noImg.contains("附了几张照片"))
    }
}
