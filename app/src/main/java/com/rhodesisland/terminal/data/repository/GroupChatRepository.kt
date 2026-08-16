package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.Conversation
import kotlinx.coroutines.flow.Flow

/**
 * 群聊仓库（多群聊）：每个群 = 一行 `characterId = [GROUP_CHARACTER_ID]` 的 conversation（isGroup=1），
 * 群名称复用 title 列、群封面存 coverImagePath、成员存 memberIdsJson；消息复用 chat_history
 * （每行 `characterId` 记发言人；user 消息的 characterId = 哨兵 id）。
 *
 * 发送走 [ChatRepository.addMessage]（**而非**自动视频 [ChatCompletionRepository.finalizeAssistant]），
 * 因此群成员发言绝不触发 Seedance 自动视频。
 */
class GroupChatRepository(
    private val conversationRepository: ConversationRepository,
    private val chatRepository: ChatRepository,
) {

    companion object {
        /** 群聊会话的哨兵 characterId（碰撞不到内置/自定义 id）。 */
        const val GROUP_CHARACTER_ID = "group_chat"

        /** 新群默认标题。 */
        const val GROUP_TITLE = "群聊"
    }

    /** 全部群聊会话（最近活跃在前）。 */
    suspend fun listGroups(): List<Conversation> = conversationRepository.listGroups()

    /** 群聊会话列表 Flow（群列表页观察用）。 */
    fun observeGroups(): Flow<List<Conversation>> = conversationRepository.observeGroups()

    /** 取指定群；非群聊会话返回 null。 */
    suspend fun getGroup(id: Long): Conversation? =
        conversationRepository.getById(id)?.takeIf { it.isGroup }

    /** 新建群聊：落普通字段 -> 标记群聊 -> 写成员 -> 写封面。返回群 id。 */
    suspend fun createGroup(name: String, coverPath: String?, memberIds: List<String>): Long {
        val id = conversationRepository.create(GROUP_CHARACTER_ID, name.ifBlank { GROUP_TITLE })
        conversationRepository.markGroup(id)
        conversationRepository.setGroupMembers(id, memberIds)
        if (!coverPath.isNullOrBlank()) {
            conversationRepository.setGroupCover(id, coverPath)
        }
        return id
    }

    /** 重命名群。 */
    suspend fun setGroupName(id: Long, name: String) {
        conversationRepository.rename(id, name.ifBlank { GROUP_TITLE })
    }

    /** 更新群封面（null=清除）。 */
    suspend fun setGroupCover(id: Long, coverPath: String?) {
        conversationRepository.setGroupCover(id, coverPath)
    }

    /** 删除群及其全部消息（Seedance 任务记录不受影响）。 */
    suspend fun deleteGroup(id: Long) {
        conversationRepository.delete(id)
    }

    /** 最近一条消息预览（内容 -> 时间戳），供群列表副标题；无消息返回 null。 */
    suspend fun lastMessagePreview(groupId: Long): Pair<String, Long>? =
        chatRepository.getHistory(groupId).lastOrNull()?.let { it.content to it.timestamp }

    /** 落库用户消息（发言人 = 哨兵 id），返回行 ID。 */
    suspend fun sendUserMessage(conversationId: Long, message: ChatMessage): Long {
        val id = chatRepository.addMessage(GROUP_CHARACTER_ID, conversationId, message)
        conversationRepository.touch(conversationId)
        return id
    }

    /** 落库某成员的 assistant 消息，返回行 ID。 */
    suspend fun sendMemberMessage(conversationId: Long, speakerId: String, content: String): Long {
        val id = chatRepository.addMessage(
            speakerId, conversationId, ChatMessage(role = "assistant", content = content),
        )
        conversationRepository.touch(conversationId)
        return id
    }
}