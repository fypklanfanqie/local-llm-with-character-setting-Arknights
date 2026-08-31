package com.rhodesisland.terminal.llm

import com.rhodesisland.terminal.data.local.ChatDao
import com.rhodesisland.terminal.data.local.ChatHistoryEntity
import com.rhodesisland.terminal.data.local.ConversationDao
import com.rhodesisland.terminal.data.local.ConversationEntity
import com.rhodesisland.terminal.config.AppConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RollingSummarizer] 编排测试（假 DAO 内存态 + 注入模型调用）。
 *
 * 锁定行为契约：
 * - 仅当未摘要数 > TRIGGER 才折叠；折叠批 = 最旧 FOLD_BATCH 条原文；
 * - 新摘要经 sanitize 后与推进后的水位线（批次最后一条 id）一并写回；
 * - 模型失败 / 空白输出 → 静默放弃、绝不推进水位线、绝不阻塞聊天；
 * - 同一会话并发折叠请求去重（后到者直接让路）。
 */
class RollingSummarizerTest {

    // ===== 内存假仓库 =====

    private class FakeChatDao(var rows: List<ChatHistoryEntity> = emptyList()) : ChatDao {
        var lastUnfoldedLimit = -1

        override fun getHistory(conversationId: Long) =
            throw UnsupportedOperationException()
        override suspend fun getHistoryList(conversationId: Long): List<ChatHistoryEntity> = rows
        override suspend fun getHistoryListForPrompt(conversationId: Long): List<ChatHistoryEntity> = rows
        override suspend fun getAllHistoryList(conversationId: Long): List<ChatHistoryEntity> = rows
        override suspend fun insert(entity: ChatHistoryEntity): Long = -1
        override suspend fun count(conversationId: Long): Int = rows.size
        override suspend fun trimOldest(conversationId: Long, limit: Int) {}
        override suspend fun clearHistory(conversationId: Long) {}
        override suspend fun deleteById(conversationId: Long, id: Long) {}

        override suspend fun getUnfoldedOldestBatch(
            conversationId: Long,
            watermark: Long,
            limit: Int,
        ): List<ChatHistoryEntity> {
            lastUnfoldedLimit = limit
            return rows.filter { it.conversationId == conversationId && it.id > watermark }
                .sortedBy { it.id }
                .take(limit)
        }

        override suspend fun countUnfolded(conversationId: Long, watermark: Long): Int =
            rows.count { it.conversationId == conversationId && it.id > watermark }
    }

    private class FakeConversationDao(initial: ConversationEntity) : ConversationDao {
        var stored = initial
        var updateSummaryCalls = 0
        var eventShell = false

        override fun observeByCharacter(characterId: String) = throw UnsupportedOperationException()
        override suspend fun listByCharacter(characterId: String): List<ConversationEntity> = listOf(stored)
        override suspend fun getById(id: Long): ConversationEntity = stored
        override fun observeGroups() = throw UnsupportedOperationException()
        override suspend fun listGroups(): List<ConversationEntity> = emptyList()
        override suspend fun markGroup(id: Long) {}
        override suspend fun count(characterId: String): Int = 1
        override suspend fun delete(id: Long) {}
        override suspend fun deleteMessages(conversationId: Long) {}
        override suspend fun deleteAllMessagesExceptEventShells() {}
        override suspend fun deleteAllConversationsExceptEventShells() {}
        override suspend fun isSpecialEventShell(conversationId: Long): Boolean = eventShell

        override suspend fun updateSummary(id: Long, summaryText: String, upToMessageId: Long?): Int {
            updateSummaryCalls++
            stored = stored.copy(summaryText = summaryText, summarizedUpToMessageId = upToMessageId)
            return 1
        }
        // 其余 UI 维护方法与本特性无关，空实现
        override suspend fun insert(entity: ConversationEntity): Long = -1
        override suspend fun updateTitle(id: Long, title: String, updatedAt: Long) {}
        override suspend fun touch(id: Long, updatedAt: Long) {}
        override suspend fun updateAutoVideoEnabled(id: Long, enabled: Boolean): Int = 0
        override suspend fun updateGroupMembers(id: Long, json: String, updatedAt: Long) {}
        override suspend fun updateGroupCover(id: Long, coverPath: String?, updatedAt: Long) {}
    }

    private val baseConv = ConversationEntity(
        id = 7L, characterId = "char", title = "t", createdAt = 0L, updatedAt = 0L,
    )

    /** 造 N 条未摘要历史（id 从 startId 起）。 */
    private fun history(n: Int, startId: Long = 100L): List<ChatHistoryEntity> =
        (0 until n).map { i ->
            ChatHistoryEntity(
                characterId = "char", conversationId = 7L,
                role = if (i % 2 == 0) "user" else "assistant",
                content = "消息${startId + i}", timestamp = startId + i,
            ).copy(id = startId + i)
        }

    private fun summarizer(
        chatDao: FakeChatDao,
        convDao: FakeConversationDao,
        gate: suspend () -> Boolean = { true },
        foldBatch: Int = AppConfig.RollingSummary.DEFAULT_FOLD_BATCH,
        model: suspend (String) -> String,
    ) = RollingSummarizer(
        conversationDao = convDao,
        chatDao = chatDao,
        canAttempt = gate,
        foldBatchProvider = { foldBatch },
        completeSummary = model,
    )

    // ===== 行为 =====

    @Test
    fun belowThreshold_skipsModelCall() = runBlocking {
        // 默认批量下恰好停在派生阈值上：不得折叠
        val chat = FakeChatDao(history(RollingSummary.triggerFor(AppConfig.RollingSummary.DEFAULT_FOLD_BATCH)))
        val conv = FakeConversationDao(baseConv)
        var calls = 0
        val folded = summarizer(chat, conv) { calls++; "摘要" }.foldIfDue(7L)
        assertFalse("≤触发阈值不得折叠", folded)
        assertEquals(0, calls)
        assertEquals(0, conv.updateSummaryCalls)
    }

    @Test
    fun aboveThreshold_foldsOldestBatch_andWritesWatermark() = runBlocking {
        val batch = 40 // 显式小批量：越过派生阈值(80)后再折整批
        val rows = history(RollingSummary.triggerFor(batch) + 5)
        val chat = FakeChatDao(rows)
        val conv = FakeConversationDao(baseConv)
        var capturedPrompt = ""
        val folded = summarizer(chat, conv, foldBatch = batch) { prompt ->
            capturedPrompt = prompt
            "<think>推理</think>新摘要内容"
        }.foldIfDue(7L)
        assertTrue(folded)
        assertEquals(batch, chat.lastUnfoldedLimit)
        // 提示词带旧摘要（首折为「无」）与本批首末原文
        assertTrue(capturedPrompt.contains("【已有前情提要】\n无"))
        assertTrue(capturedPrompt.contains(rows.first().content))
        assertTrue(capturedPrompt.contains("消息${100L + batch - 1}"))
        assertFalse("已归档原文不得再次出现在本批", capturedPrompt.contains("消息${100L + batch}"))
        // 写回：清洗后的摘要 + 水位 = 批次最后一条 id
        assertEquals("新摘要内容", conv.stored.summaryText)
        assertEquals(rows[batch - 1].id, conv.stored.summarizedUpToMessageId)
    }

    @Test
    fun existingSummary_isMergedIntoNextPrompt() = runBlocking {
        // 已折 10 条（水位=rows[9].id）后未摘要数再次越过派生阈值 → 触发；批次=最旧 40 条未摘要
        val batch = 40
        val rows = history(RollingSummary.triggerFor(batch) + 1 + 10, startId = 200L)
        val chat = FakeChatDao(rows)
        val oldWatermark = rows[9].id
        val conv = FakeConversationDao(baseConv.copy(summaryText = "旧提要", summarizedUpToMessageId = oldWatermark))
        var capturedPrompt = ""
        summarizer(chat, conv, foldBatch = batch) {
            capturedPrompt = it
            "新提要"
        }.foldIfDue(7L)
        assertTrue("旧摘要须作为合并输入", capturedPrompt.contains("旧提要"))
        assertTrue("水位线之前的原文不得再进批", !capturedPrompt.contains(rows[8].content))
        assertTrue("批次应从水位线后的第一条开始", capturedPrompt.contains(rows[10].content))
        assertEquals(rows[9 + batch].id, conv.stored.summarizedUpToMessageId)
    }

    @Test
    fun modelFailure_silentlyAborts_withoutAdvancingWatermark() = runBlocking {
        val chat = FakeChatDao(history(RollingSummary.triggerFor(40) + 1))
        val conv = FakeConversationDao(baseConv)
        val folded = summarizer(chat, conv, foldBatch = 40) { throw RuntimeException("网络炸了") }.foldIfDue(7L)
        assertFalse(folded)
        assertEquals("", conv.stored.summaryText)
        assertEquals(null, conv.stored.summarizedUpToMessageId)
    }

    @Test
    fun blankModelOutput_rejected() = runBlocking {
        val chat = FakeChatDao(history(RollingSummary.triggerFor(40) + 1))
        val conv = FakeConversationDao(baseConv)
        val folded = summarizer(chat, conv, foldBatch = 40) { "<think>只输出了思考</think>" }.foldIfDue(7L)
        assertFalse(folded)
        assertEquals(0, conv.updateSummaryCalls)
    }

    @Test
    fun gateClosed_skipsEverything() = runBlocking {
        val chat = FakeChatDao(history(500))
        val conv = FakeConversationDao(baseConv)
        var calls = 0
        val folded = summarizer(chat, conv, gate = { false }) { calls++; "" }.foldIfDue(7L)
        assertFalse(folded)
        assertEquals(0, calls)
    }

    @Test
    fun specialEventShell_neverFolded() = runBlocking {
        // 特殊邂逅会话的活跃历史在归档表（id 是另一套序列），chat_history 只是冻结的审计副本——
        // 对其折叠会把归档 id 写进水位线、与发送路径的过滤完全错位。契约：永久跳过。
        val chat = FakeChatDao(history(500))
        val conv = FakeConversationDao(baseConv).also { it.eventShell = true }
        var calls = 0
        val folded = summarizer(chat, conv) { calls++; "摘要" }.foldIfDue(7L)
        assertFalse(folded)
        assertEquals(0, calls)
        assertEquals(0, conv.updateSummaryCalls)
    }

    @Test
    fun concurrentFoldsForSameConversation_deduplicated() = runBlocking {
        val chat = FakeChatDao(history(400))
        val conv = FakeConversationDao(baseConv)
        val release = CompletableDeferred<Unit>()
        val s = summarizer(chat, conv) { release.await(); "慢摘要" }
        val first = async { s.foldIfDue(7L) }
        kotlinx.coroutines.delay(50) // 让第一个进入 in-flight 区
        val second = s.foldIfDue(7L)
        release.complete(Unit)
        assertTrue(first.await())
        assertFalse("进行中的同会话折叠应被去重跳过", second)
    }

    @Test
    fun differentConversations_doNotBlockEachOther() = runBlocking {
        // 会话 7 有足额未摘要触发折叠；会话 8 仅 3 条低于阈值——两者互不影响
        val rows = history(RollingSummary.triggerFor(50) + 20) + (9000L until 9003L).map { id ->
            ChatHistoryEntity(
                characterId = "char", conversationId = 8L,
                role = "user", content = "群八消息$id", timestamp = id,
            ).copy(id = id)
        }
        val chat = FakeChatDao(rows)
        val conv8 = FakeConversationDao(baseConv.copy(id = 8L))
        val conv7 = FakeConversationDao(baseConv.copy(id = 7L))
        var calls = 0
        val model: suspend (String) -> String = { calls++; "s" }
        val s8 = summarizer(chat, conv8) { model(it) }
        val s7 = summarizer(chat, conv7) { model(it) }
        assertFalse(s8.foldIfDue(8L))
        assertTrue(s7.foldIfDue(7L))
        assertEquals("只有会话 7 折叠、模型只被调一次", 1, calls)
    }
}
