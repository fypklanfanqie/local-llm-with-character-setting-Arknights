package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.data.local.ChatHistoryEntity
import com.rhodesisland.terminal.data.local.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊实体<->领域映射测试：
 * - [ConversationEntity.toDomain] 往返 isGroup/memberIdsJson（容错空串/非法 JSON）；
 * - [ChatHistoryEntity.toMessage] 回填行级 characterId（群聊按条发言人）。
 */
class GroupConversationMapperTest {

    private fun entity(isGroup: Boolean = false, memberIdsJson: String = "") = ConversationEntity(
        id = 5, characterId = "group_chat", title = "群聊",
        createdAt = 100, updatedAt = 200, autoVideoEnabled = false,
        isGroup = isGroup, memberIdsJson = memberIdsJson,
    )

    @Test
    fun toDomain_groupWithMembers_decodesMemberIds() {
        val domain = entity(isGroup = true, memberIdsJson = "[\"a\",\"b\"]").toDomain()
        assertTrue(domain.isGroup)
        assertEquals(listOf("a", "b"), domain.memberIds)
    }

    @Test
    fun toDomain_legacyRow_defaultsToListIsGroupFalse() {
        val domain = entity().toDomain()
        assertFalse(domain.isGroup)
        assertTrue(domain.memberIds.isEmpty())
    }

    @Test
    fun toDomain_invalidJsonTolerated() {
        val domain = entity(isGroup = true, memberIdsJson = "{not-json").toDomain()
        assertTrue(domain.isGroup)
        assertTrue(domain.memberIds.isEmpty())
    }

    @Test
    fun encodeDecodeMemberIds_roundTrip() {
        assertEquals(listOf("a", "b", "c"), decodeMemberIds(encodeMemberIds(listOf("a", "b", "c"))))
        assertEquals(emptyList<String>(), decodeMemberIds(encodeMemberIds(emptyList())))
        assertEquals("", encodeMemberIds(emptyList()))
    }

    @Test
    fun toMessage_backfillsCharacterIdFromRow() {
        val entity = ChatHistoryEntity(
            id = 1, characterId = "la-pluma", conversationId = 5,
            role = "assistant", content = "大家好", timestamp = 100,
        )
        val msg = entity.toMessage()
        assertEquals("la-pluma", msg.characterId)
    }

    @Test
    fun toEntity_keepsSpeakerIdAsRowColumn() {
        // 写路径单事实源：addMessage 的 characterId 参数（发言人）直接落行，非消息字段。
        val msg = com.rhodesisland.terminal.data.model.ChatMessage(role = "user", content = "hi")
        val entity = msg.toEntity(characterId = "group_chat", conversationId = 5)
        assertEquals("group_chat", entity.characterId)
        assertNull(msg.characterId)
    }
}