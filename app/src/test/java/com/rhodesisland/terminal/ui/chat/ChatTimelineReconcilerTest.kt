package com.rhodesisland.terminal.ui.chat

import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.DisplayMessage
import com.rhodesisland.terminal.data.model.MessageCompletionState
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.model.SeedanceVideo
import com.rhodesisland.terminal.data.model.SeedanceVideoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatTimelineReconciler] 协调契约测试（Task 2）。
 *
 * 核心断言：Room 在精确行 ID 确认前必须保留乐观完成消息，确认后只显示一次且绝不重复；
 * 不同会话的 pending 不得串台；流式气泡在生成期间持续保留。
 */
class ChatTimelineReconcilerTest {

    private fun user(id: Long?, content: String) = ChatMessage(
        role = "user", content = content, databaseId = id,
    )

    private fun assistant(
        id: Long?,
        content: String,
        modelContent: String? = null,
        completionState: MessageCompletionState = MessageCompletionState.COMPLETE,
    ) = ChatMessage(
        role = "assistant", content = content, modelContent = modelContent, databaseId = id,
        completionState = completionState,
    )

    private fun pending(conv: Long, dbId: Long, content: String) = PendingFinal(
        conversationId = conv,
        databaseId = dbId,
        message = DisplayMessage(
            id = "msg-$dbId",
            role = "assistant",
            content = content,
            segments = emptyList(),
            sender = "AI",
        ),
    )

    private fun streaming(id: String = "streaming", content: String = "思考中") = DisplayMessage(
        id = id, role = "streaming", content = content, segments = emptyList(), sender = "AI", isStreaming = true,
    )

    @Test
    fun staleUserOnlyHistory_preservesPendingAssistant() {
        // Room 仍只有用户消息（延迟/旧快照），UI 已乐观展示完成回复：不得被覆盖。
        val history = listOf(user(id = 1, content = "你好"))
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = pending(conv = 1L, dbId = 99L, content = "这是回答"),
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertTrue("pending 回复被延迟快照覆盖", result.messages.any { it.id == "msg-99" })
        assertFalse("Room 尚未确认行 99", result.pendingResolved)
        assertEquals(2, result.messages.size)
        assertFalse(result.showWelcome)
    }

    @Test
    fun roomAcknowledgesPending_showsOnceAndResolves() {
        val history = listOf(
            user(id = 1, content = "你好"),
            assistant(id = 99, content = "这是回答", modelContent = "raw"),
        )
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = pending(conv = 1L, dbId = 99L, content = "这是回答"),
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertTrue("Room 已回填但 pending 未清除", result.pendingResolved)
        assertEquals("msg-99 出现次数 != 1", 1, result.messages.count { it.id == "msg-99" })
        assertEquals(2, result.messages.size)
    }

    @Test
    fun databaseId_isCarriedOntoDisplayMessagesForDeletion() {
        // 气泡「删除」按行 ID 精确删行：持久消息的 databaseId 必须透传到展示层。
        val history = listOf(
            user(id = 1L, content = "你好"),
            assistant(id = 7L, content = "回复", modelContent = "raw"),
        )
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = null,
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertEquals(1L, result.messages.first { it.role == "user" }.databaseId)
        assertEquals(7L, result.messages.first { it.role == "assistant" }.databaseId)
        assertTrue("流式气泡 databaseId 应为 null", streaming().databaseId == null)
    }

    @Test
    fun identicalAssistantTexts_areDistinguishedByRowId() {
        // 两次回复文本完全相同，必须按行 ID 区分，不能因文本相等而误清 pending。
        val history = listOf(
            user(id = 1, content = "问1"),
            assistant(id = 2, content = "相同的回答"),
            user(id = 3, content = "问2"),
        )
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = pending(conv = 1L, dbId = 4, content = "相同的回答"),
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertFalse(result.pendingResolved)
        assertEquals(2, result.messages.count { it.content == "相同的回答" })
        assertTrue(result.messages.any { it.id == "msg-2" })
        assertTrue(result.messages.any { it.id == "msg-4" })
    }

    @Test
    fun pendingForAnotherConversation_isNeverMerged() {
        // 当前活跃会话 B，pending 属于 A：必须丢弃且标记清除，绝不串台。
        val history = listOf(user(id = 1, content = "B 的问题"))
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 2L,
            pendingFinal = pending(conv = 1L, dbId = 99L, content = "A 的回答"),
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertTrue("A 会话 pending 串入 B 会话", result.messages.none { it.id == "msg-99" })
        assertTrue("跨会话 pending 未标记清除", result.pendingResolved)
        assertEquals(1, result.messages.size)
    }

    @Test
    fun roomBackfillBeforeOptimisticReplace_doesNotDuplicate() {
        // Room 回填先于乐观替换：两者同一行 ID，只显示一次。
        val history = listOf(
            user(id = 1, content = "你好"),
            assistant(id = 99, content = "回答", modelContent = "raw"),
        )
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = pending(conv = 1L, dbId = 99L, content = "回答"),
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertEquals(1, result.messages.count { it.id == "msg-99" })
        assertEquals(2, result.messages.size)
        assertTrue(result.pendingResolved)
    }

    @Test
    fun streamingBubble_isPreservedDuringGeneration() {
        val history = listOf(user(id = 1, content = "你好"))
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = null,
            streaming = streaming(),
            showThink = true,
            characterName = "AI",
        )
        assertTrue("流式气泡未保留", result.messages.any { it.id == "streaming" })
        assertEquals("streaming 应为最后一条", "streaming", result.messages.last().id)
        assertFalse(result.showWelcome)
    }

    @Test
    fun emptyHistory_showsWelcomeOnlyWithoutPendingOrStreaming() {
        val result = ChatTimelineReconciler.reconcile(
            history = emptyList(),
            activeConversationId = 1L,
            pendingFinal = null,
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertTrue(result.showWelcome)
        assertTrue(result.messages.isEmpty())
    }

    @Test
    fun persistedMessages_useDatabaseIdAsStableKey() {
        val history = listOf(user(id = 1, content = "你好"))
        val result = ChatTimelineReconciler.reconcile(
            history = history,
            activeConversationId = 1L,
            pendingFinal = null,
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertEquals("msg-1", result.messages.first().id)
    }

    @Test
    fun nonPersistedMessage_fallsBackToTimestampIndexKey() {
        // 无 databaseId 的消息（旧库行/纯内存构造）用 timestamp-index 兜底，保持稳定。
        val legacy = ChatMessage(role = "user", content = "旧消息", timestamp = 500)
        val result = ChatTimelineReconciler.reconcile(
            history = listOf(legacy),
            activeConversationId = 1L,
            pendingFinal = null,
            streaming = null,
            showThink = false,
            characterName = "AI",
        )
        assertEquals("msg-500-0", result.messages.first().id)
    }

    // ===== 停止状态渲染（Task 6/7）=====

    @Test
    fun stoppedMessageCompletionState_isRenderedFromHistory() {
        val history = listOf(
            assistant(id = 1, content = "部分正文", completionState = MessageCompletionState.STOPPED_PARTIAL),
        )
        val result = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = null, streaming = null,
            showThink = true, characterName = "AI",
        )
        val rendered = result.messages.first { it.id == "msg-1" }
        assertEquals(MessageCompletionState.STOPPED_PARTIAL, rendered.completionState)
    }

    @Test
    fun stopState_isPreservedWithThinkShowOnAndOff() {
        val history = listOf(
            assistant(id = 1, content = "<think>r</think>正文", completionState = MessageCompletionState.STOPPED_PARTIAL),
        )
        val on = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = null, streaming = null,
            showThink = true, characterName = "AI",
        )
        assertEquals(MessageCompletionState.STOPPED_PARTIAL, on.messages.first { it.id == "msg-1" }.completionState)

        // 关闭思考展示：思考段被 strip，但停止状态仍保留（badge 独立于 content）。
        val off = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = null, streaming = null,
            showThink = false, characterName = "AI",
        )
        assertEquals(MessageCompletionState.STOPPED_PARTIAL, off.messages.first { it.id == "msg-1" }.completionState)
    }

    @Test
    fun stoppedPending_survivesRoomBackfillWithoutDuplicate() {
        // 用户停止后以乐观消息（含停止状态）展示，Room 回填同一行：只显示一次且状态保留。
        val history = listOf(
            user(id = 1, content = "你好"),
            assistant(id = 99, content = "未闭合思考", completionState = MessageCompletionState.STOPPED_BEFORE_FINAL),
        )
        val p = PendingFinal(
            conversationId = 1L,
            databaseId = 99L,
            message = DisplayMessage(
                id = "msg-99",
                role = "assistant",
                content = "未闭合思考",
                segments = emptyList(),
                sender = "AI",
                completionState = MessageCompletionState.STOPPED_BEFORE_FINAL,
            ),
        )
        val result = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = p, streaming = null,
            showThink = false, characterName = "AI",
        )
        assertTrue(result.pendingResolved)
        assertEquals(1, result.messages.count { it.id == "msg-99" })
        assertEquals(MessageCompletionState.STOPPED_BEFORE_FINAL, result.messages.first { it.id == "msg-99" }.completionState)
    }

    @Test
    fun showThinkToggle_doesNotDropUnacknowledgedPending() {
        // 深度思考开关触发重渲染时，pending 尚未被 Room 确认也必须保留。
        val history = listOf(user(id = 1, content = "你好"))
        val on = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = pending(1L, 99L, "回答"), streaming = null,
            showThink = true, characterName = "AI",
        )
        assertTrue(on.messages.any { it.id == "msg-99" })
        val off = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = pending(1L, 99L, "回答"), streaming = null,
            showThink = false, characterName = "AI",
        )
        assertTrue("关掉思考后 pending 丢失", off.messages.any { it.id == "msg-99" })
    }

    // ===== Seedance 视频任务附加（Task 7）=====

    private fun video(
        id: Long,
        sourceAssistantMessageId: Long,
        state: SeedanceVideoState = SeedanceVideoState.QUEUED,
        updatedAt: Long = id,
    ) = SeedanceVideo(
        id = id,
        taskUuid = "uuid-$id",
        triggerType = "auto",
        sourceConversationId = 1L,
        sourceUserMessageId = 100L,
        sourceAssistantMessageId = sourceAssistantMessageId,
        characterIdSnapshot = "char-1",
        characterNameSnapshot = "阿米娅",
        characterRoleSnapshot = "罗德岛领袖",
        characterSystemPromptSnapshot = "你是阿米娅。",
        userTextSnapshot = "你好",
        assistantTextSnapshot = "回答",
        sceneDescriptionSnapshot = "",
        promptBaseUrlSnapshot = "https://api.example.com/v1",
        promptModelSnapshot = "doubao-text-pro",
        promptJson = null,
        finalPrompt = null,
        characterImageSourceSnapshot = "asset://amiya.png",
        backgroundImageSourceSnapshot = null,
        characterImagePath = null,
        characterImageMime = null,
        characterImageSha256 = null,
        backgroundImagePath = null,
        backgroundImageMime = null,
        backgroundImageSha256 = null,
        modelVariant = SeedanceModelVariant.STANDARD,
        resolution = SeedanceResolution.P720,
        ratio = SeedanceRatio.PORTRAIT,
        durationSeconds = 5,
        generateAudio = true,
        watermark = false,
        state = state,
        remoteStatus = null,
        generationAttempt = 0,
        submissionAttemptId = null,
        submissionStartedAt = null,
        requestFingerprint = null,
        remoteTaskId = null,
        remoteVideoUrl = null,
        remoteVideoUrlObservedAt = null,
        remoteVideoUrlExpiresAt = null,
        remoteRequestId = null,
        previousRemoteTasksJson = "",
        localVideoPath = null,
        videoMime = null,
        videoByteSize = null,
        videoSha256 = null,
        downloadedAt = null,
        automaticRetryCount = 0,
        nextRetryAt = null,
        errorStage = null,
        errorCode = null,
        errorMessage = null,
        retryDisposition = null,
        requiresCostConfirmation = false,
        createdAt = 1L,
        updatedAt = updatedAt,
    )

    @Test
    fun video_attachesToAssistantByExactSourceAssistantMessageId() {
        val history = listOf(
            user(id = 1, content = "你好"),
            assistant(id = 2, content = "这是回答"),
        )
        val result = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = null, streaming = null,
            showThink = true, characterName = "AI",
            videos = listOf(video(id = 10, sourceAssistantMessageId = 2L, state = SeedanceVideoState.QUEUED)),
        )
        val rendered = result.messages.first { it.id == "msg-2" }
        assertEquals(10L, rendered.video?.id)
        assertEquals(SeedanceVideoState.QUEUED, rendered.video?.state)
        assertNull("用户消息不得附加视频", result.messages.first { it.id == "msg-1" }.video)
    }

    @Test
    fun identicalAssistantTexts_videoAttachesToCorrectRow() {
        // 两次回复文本完全相同，视频只附加到 sourceAssistantMessageId 对应的那一行。
        val history = listOf(
            user(id = 1, content = "问1"),
            assistant(id = 2, content = "相同的回答"),
            user(id = 3, content = "问2"),
            assistant(id = 4, content = "相同的回答"),
        )
        val result = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = null, streaming = null,
            showThink = true, characterName = "AI",
            videos = listOf(video(id = 20, sourceAssistantMessageId = 4L, state = SeedanceVideoState.RUNNING)),
        )
        assertNull(result.messages.first { it.id == "msg-2" }.video)
        assertEquals(20L, result.messages.first { it.id == "msg-4" }.video?.id)
    }

    @Test
    fun video_lastWriteWinsByUpdatedAtOnCollision() {
        // 同一助手行出现两条视频记录（理论上唯一索引拦截，防御性覆盖）：
        // updatedAt 更大的后写覆盖。
        val history = listOf(assistant(id = 2, content = "回答"))
        val result = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = null, streaming = null,
            showThink = true, characterName = "AI",
            videos = listOf(
                video(id = 20, sourceAssistantMessageId = 2L, state = SeedanceVideoState.QUEUED, updatedAt = 200L),
                video(id = 21, sourceAssistantMessageId = 2L, state = SeedanceVideoState.RUNNING, updatedAt = 300L),
            ),
        )
        assertEquals(21L, result.messages.first { it.id == "msg-2" }.video?.id)
        assertEquals(SeedanceVideoState.RUNNING, result.messages.first { it.id == "msg-2" }.video?.state)
    }

    @Test
    fun video_attachesAfterRoomAcknowledgesPendingFinal() {
        // 乐观完成消息期间 Room 尚未回填 -> 无视频；回填后附加视频且 pending 解析。
        val optimistic = ChatTimelineReconciler.reconcile(
            history = listOf(user(id = 1, content = "你好")), activeConversationId = 1L,
            pendingFinal = pending(1L, 99L, "回答"), streaming = null,
            showThink = true, characterName = "AI",
            videos = listOf(video(id = 30, sourceAssistantMessageId = 99L, state = SeedanceVideoState.SNAPSHOT_PENDING)),
        )
        assertTrue(optimistic.messages.any { it.id == "msg-99" })
        assertNull("乐观 pending 阶段不可见视频", optimistic.messages.first { it.id == "msg-99" }.video)

        val confirmed = ChatTimelineReconciler.reconcile(
            history = listOf(
                user(id = 1, content = "你好"),
                assistant(id = 99, content = "回答"),
            ), activeConversationId = 1L,
            pendingFinal = pending(1L, 99L, "回答"), streaming = null,
            showThink = true, characterName = "AI",
            videos = listOf(video(id = 30, sourceAssistantMessageId = 99L, state = SeedanceVideoState.SNAPSHOT_PENDING)),
        )
        assertTrue(confirmed.pendingResolved)
        assertEquals(30L, confirmed.messages.first { it.id == "msg-99" }.video?.id)
    }

    @Test
    fun video_doesNotInterfereWithStreamingOrWelcome() {
        // 有视频列表时流式气泡仍保留为最后一条；空历史仍显示欢迎态。
        val withStream = ChatTimelineReconciler.reconcile(
            history = listOf(user(id = 1, content = "你好")), activeConversationId = 1L,
            pendingFinal = null, streaming = streaming(), showThink = true, characterName = "AI",
            videos = listOf(video(id = 40, sourceAssistantMessageId = 99L)),
        )
        assertEquals("streaming", withStream.messages.last().id)

        val welcome = ChatTimelineReconciler.reconcile(
            history = emptyList(), activeConversationId = 1L,
            pendingFinal = null, streaming = null, showThink = true, characterName = "AI",
            videos = listOf(video(id = 41, sourceAssistantMessageId = 99L)),
        )
        assertTrue(welcome.showWelcome)
        assertTrue(welcome.messages.isEmpty())
    }

    @Test
    fun deletedOrTrimmedSource_videoIsSafelyIgnored() {
        // 源 assistant 已被删除/修剪（历史不含该行）：视频不附加且不崩溃。
        val history = listOf(user(id = 1, content = "你好"))
        val result = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = null, streaming = null,
            showThink = true, characterName = "AI",
            videos = listOf(video(id = 50, sourceAssistantMessageId = 99L, state = SeedanceVideoState.RUNNING)),
        )
        assertTrue(result.messages.none { it.video != null })
        assertEquals(1, result.messages.size)
    }

    @Test
    fun videoStatusUpdate_reattachesToSameAssistantRow() {
        // 视频状态推进（QUEUED -> READY）触发重渲染：仍附加到同一助手行。
        val history = listOf(assistant(id = 2, content = "回答"))
        val queued = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = null, streaming = null, showThink = true, characterName = "AI",
            videos = listOf(video(id = 60, sourceAssistantMessageId = 2L, state = SeedanceVideoState.QUEUED)),
        )
        val ready = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = null, streaming = null, showThink = true, characterName = "AI",
            videos = listOf(video(id = 60, sourceAssistantMessageId = 2L, state = SeedanceVideoState.READY)),
        )
        assertEquals(SeedanceVideoState.QUEUED, queued.messages.first { it.id == "msg-2" }.video?.state)
        assertEquals(SeedanceVideoState.READY, ready.messages.first { it.id == "msg-2" }.video?.state)
    }

    @Test
    fun showThinkToggle_keepsVideoAttached() {
        val history = listOf(assistant(id = 2, content = "<think>r</think>正文"))
        val on = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = null, streaming = null, showThink = true, characterName = "AI",
            videos = listOf(video(id = 70, sourceAssistantMessageId = 2L)),
        )
        val off = ChatTimelineReconciler.reconcile(
            history = history, activeConversationId = 1L,
            pendingFinal = null, streaming = null, showThink = false, characterName = "AI",
            videos = listOf(video(id = 70, sourceAssistantMessageId = 2L)),
        )
        assertEquals(70L, on.messages.first { it.id == "msg-2" }.video?.id)
        assertEquals(70L, off.messages.first { it.id == "msg-2" }.video?.id)
    }
}
