package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.data.local.ChatHistoryEntity
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.MessageCompletionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ChatRepository 实体<->领域消息映射测试（Task 3 Step 1）。
 *
 * 纯 JVM 单测：映射函数 [toMessage]/[toEntity] 为顶层 internal 纯函数，无 Android 依赖。
 * 核心断言：`modelContent` 精确持久化；旧行（modelContent=null）经 `modelContent ?: content` 兼容。
 */
class ChatRepositoryMappingTest {

    @Test
    fun toMessage_preservesModelContent() {
        val entity = ChatHistoryEntity(
            id = 1, characterId = "c1", conversationId = 1,
            role = "assistant", content = "display", modelContent = "raw", timestamp = 100,
        )
        val msg = entity.toMessage()
        assertEquals("raw", msg.modelContent)
        assertEquals("display", msg.content)
    }

    @Test
    fun toMessage_legacyRow_hasNullModelContent() {
        val entity = ChatHistoryEntity(
            id = 1, characterId = "c1", conversationId = 1,
            role = "assistant", content = "display", modelContent = null, timestamp = 100,
        )
        val msg = entity.toMessage()
        assertNull(msg.modelContent)
        // 旧行兼容规则：modelContent ?: content（本地历史构造消息时用此式取模型可见文本）
        assertEquals("display", msg.modelContent ?: msg.content)
    }

    @Test
    fun toMessage_backfillsDatabaseIdFromRoomPrimaryKey() {
        // Task 2：持久消息以 Room 主键为稳定标识（Compose key + 完成消息协调）。
        val entity = ChatHistoryEntity(
            id = 42, characterId = "c1", conversationId = 1,
            role = "assistant", content = "display", modelContent = "raw", timestamp = 100,
        )
        val msg = entity.toMessage()
        assertEquals(42L, msg.databaseId)
    }

    @Test
    fun toEntity_leavesDatabaseIdToRoomAutoGenerate() {
        // databaseId 为应用内标识，不持久化：toEntity 恒产生 id=0，由 Room 自增。
        val msg = ChatMessage(
            role = "assistant", content = "display", modelContent = "raw",
            timestamp = 100, databaseId = 99,
        )
        val entity = msg.toEntity(characterId = "c1", conversationId = 1)
        assertEquals(0L, entity.id)
    }

    @Test
    fun toEntity_persistsModelContent() {
        val msg = ChatMessage(role = "assistant", content = "display", modelContent = "raw", timestamp = 100)
        val entity = msg.toEntity(characterId = "c1", conversationId = 1)
        assertEquals("raw", entity.modelContent)
        assertEquals("display", entity.content)
    }

    @Test
    fun toEntity_userMessageHasNullModelContent() {
        val msg = ChatMessage(role = "user", content = "hi", modelContent = null)
        val entity = msg.toEntity(characterId = "c1", conversationId = 1)
        assertNull(entity.modelContent)
    }

    @Test
    fun roundTrip_preservesModelContent() {
        // modelText 可能含 <think>...</think> 等 display 阶段会剥离的内容，须原样留存
        val original = ChatMessage(
            role = "assistant", content = "display",
            modelContent = "raw<think>reasoning</think>answer", timestamp = 1,
        )
        val restored = original.toEntity("c1", 1).toMessage()
        assertEquals(original.modelContent, restored.modelContent)
        assertEquals(original.content, restored.content)
    }

    @Test
    fun roundTrip_legacyNullModelContent_fallsBackToContent() {
        val original = ChatMessage(role = "assistant", content = "display", modelContent = null)
        val restored = original.toEntity("c1", 1).toMessage()
        assertNull(restored.modelContent)
        assertEquals("display", restored.modelContent ?: restored.content)
    }

    // ===== 停止状态持久化（Task 6）=====

    @Test
    fun toMessage_preservesCompletionState() {
        val entity = ChatHistoryEntity(
            id = 1, characterId = "c1", conversationId = 1,
            role = "assistant", content = "display", modelContent = "raw", timestamp = 100,
            completionState = MessageCompletionState.STOPPED_PARTIAL.storageKey,
        )
        val msg = entity.toMessage()
        assertEquals(MessageCompletionState.STOPPED_PARTIAL, msg.completionState)
    }

    @Test
    fun toMessage_unknownCompletionStateFallsBackToComplete() {
        val entity = ChatHistoryEntity(
            id = 1, characterId = "c1", conversationId = 1,
            role = "assistant", content = "display", timestamp = 100,
            completionState = "SOME_FUTURE_STATE",
        )
        val msg = entity.toMessage()
        assertEquals(MessageCompletionState.COMPLETE, msg.completionState)
    }

    @Test
    fun toEntity_persistsCompletionState() {
        val msg = ChatMessage(
            role = "assistant", content = "display", timestamp = 100,
            completionState = MessageCompletionState.STOPPED_BEFORE_FINAL,
        )
        val entity = msg.toEntity(characterId = "c1", conversationId = 1)
        assertEquals(MessageCompletionState.STOPPED_BEFORE_FINAL.storageKey, entity.completionState)
    }

    @Test
    fun roundTrip_preservesCompletionState() {
        val original = ChatMessage(
            role = "assistant", content = "display", modelContent = "raw", timestamp = 1,
            completionState = MessageCompletionState.STOPPED_PARTIAL,
        )
        val restored = original.toEntity("c1", 1).toMessage()
        assertEquals(original.completionState, restored.completionState)
        assertEquals(original.modelContent, restored.modelContent)
    }
}
