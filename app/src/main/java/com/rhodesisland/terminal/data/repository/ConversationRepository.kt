package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.data.local.ConversationDao
import com.rhodesisland.terminal.data.local.ConversationEntity
import com.rhodesisland.terminal.data.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 会话仓库
 * 管理角色的会话列表（新建 / 删除 / 重命名 / 切换），消息历史见 [ChatRepository]。
 * 群聊复用本仓库（哨兵 characterId + isGroup/memberIdsJson 列）。
 */
class ConversationRepository(private val dao: ConversationDao) {

    /** 新会话的默认标题；首条用户消息发出后自动改为消息摘要。 */
    val defaultTitle: String get() = DEFAULT_TITLE

    fun observeByCharacter(characterId: String): Flow<List<Conversation>> =
        dao.observeByCharacter(characterId).map { list -> list.map { it.toDomain() } }

    suspend fun listByCharacter(characterId: String): List<Conversation> =
        dao.listByCharacter(characterId).map { it.toDomain() }

    suspend fun getById(id: Long): Conversation? = dao.getById(id)?.toDomain()

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

    // ===== 群聊 =====

    /** 全部群聊会话（最近活跃在前；多群聊）。 */
    suspend fun listGroups(): List<Conversation> = dao.listGroups().map { it.toDomain() }

    /** 群聊会话列表 Flow（群列表页观察用）。 */
    fun observeGroups(): Flow<List<Conversation>> =
        dao.observeGroups().map { list -> list.map { it.toDomain() } }

    /** 标记某会话为群聊（用于 [create] 落普通字段后追加群聊标记）。 */
    suspend fun markGroup(id: Long) {
        dao.markGroup(id)
    }

    /** 更新群成员列表并刷新 updatedAt。 */
    suspend fun setGroupMembers(id: Long, memberIds: List<String>) {
        dao.updateGroupMembers(id, encodeMemberIds(memberIds), System.currentTimeMillis())
    }

    /** 更新群封面（null=清除）并刷新 updatedAt。 */
    suspend fun setGroupCover(id: Long, coverPath: String?) {
        dao.updateGroupCover(id, coverPath, System.currentTimeMillis())
    }

    /** 开启/关闭该会话的 Seedance 自动视频（新会话默认关闭；旧库行迁移后同为关闭）。 */
    suspend fun setAutoVideoEnabled(id: Long, enabled: Boolean) {
        dao.updateAutoVideoEnabled(id, enabled)
    }

    /**
     * 删除会话及其全部消息（事务性，见 ConversationDao.deleteConversation）。
     * 特殊邂逅导航壳被保护：返回 false 表示不可删除（调用方提示用户），事件回忆不受影响。
     */
    suspend fun delete(id: Long): Boolean {
        return dao.deleteConversation(id)
    }

    /** 清空全部聊天记录（存储管理用；Seedance 任务记录保留）。 */
    suspend fun clearAll() {
        dao.clearAllConversations()
    }

    companion object {
        const val DEFAULT_TITLE = "新对话"
    }
}

// ===== 转换（顶层 internal，便于单测；纯函数无 Android 依赖）=====

private val memberJson = Json { ignoreUnknownKeys = true }

/**
 * 实体 -> 领域会话。[memberIdsJson] 解析为成员 id 列表；空串/非法 JSON 容错为空列表
 * （迁移旧行 / 脏数据不崩溃）。
 */
internal fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    characterId = characterId,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    autoVideoEnabled = autoVideoEnabled,
    isGroup = isGroup,
    memberIds = decodeMemberIds(memberIdsJson),
    coverImagePath = coverImagePath,
)

/** 成员 id 列表 -> JSON 数组字符串（空列表编码为空串）。 */
internal fun encodeMemberIds(memberIds: List<String>): String =
    if (memberIds.isEmpty()) "" else memberJson.encodeToString(ListSerializer(String.serializer()), memberIds)

/** JSON 数组字符串 -> 成员 id 列表（空串/非法 JSON 容错为空列表）。 */
internal fun decodeMemberIds(raw: String): List<String> =
    if (raw.isBlank()) emptyList()
    else runCatching { memberJson.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrDefault(emptyList())