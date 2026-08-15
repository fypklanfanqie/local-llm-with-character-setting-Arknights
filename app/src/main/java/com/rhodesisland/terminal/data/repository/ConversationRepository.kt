package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.data.local.ConversationDao
import com.rhodesisland.terminal.data.local.ConversationEntity
import com.rhodesisland.terminal.data.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 会话仓库
 * 管理角色的会话列表（新建 / 删除 / 重命名 / 切换），消息历史见 [ChatRepository]。
 */
class ConversationRepository(private val dao: ConversationDao) {

    /** 新会话的默认标题；首条用户消息发出后自动改为消息摘要。 */
    val defaultTitle: String get() = DEFAULT_TITLE

    fun observeByCharacter(characterId: String): Flow<List<Conversation>> =
        dao.observeByCharacter(characterId).map { list -> list.map(::toDomain) }

    suspend fun listByCharacter(characterId: String): List<Conversation> =
        dao.listByCharacter(characterId).map(::toDomain)

    suspend fun getById(id: Long): Conversation? = dao.getById(id)?.let(::toDomain)

    /** 新建会话，返回自增 id。 */
    suspend fun create(characterId: String, title: String = DEFAULT_TITLE): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            ConversationEntity(
                characterId = characterId,
                title = title,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun rename(id: Long, title: String) {
        dao.updateTitle(id, title, System.currentTimeMillis())
    }

    /** 刷新 updatedAt，把会话顶到列表最前（发消息后调用）。 */
    suspend fun touch(id: Long) {
        dao.touch(id, System.currentTimeMillis())
    }

    /** 开启/关闭该会话的 Seedance 自动视频（新会话默认关闭；旧库行迁移后同为关闭）。 */
    suspend fun setAutoVideoEnabled(id: Long, enabled: Boolean) {
        dao.updateAutoVideoEnabled(id, enabled)
    }

    /** 删除会话及其全部消息（事务性，见 ConversationDao.deleteConversation）。 */
    suspend fun delete(id: Long) {
        dao.deleteConversation(id)
    }

    private fun toDomain(e: ConversationEntity) = Conversation(
        id = e.id,
        characterId = e.characterId,
        title = e.title,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        autoVideoEnabled = e.autoVideoEnabled,
    )

    companion object {
        const val DEFAULT_TITLE = "新对话"
    }
}
