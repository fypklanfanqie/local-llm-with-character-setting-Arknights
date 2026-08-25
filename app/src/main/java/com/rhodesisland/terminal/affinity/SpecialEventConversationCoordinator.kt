package com.rhodesisland.terminal.affinity

import androidx.room.withTransaction
import com.rhodesisland.terminal.data.local.AppDatabase
import com.rhodesisland.terminal.data.local.SpecialEventEntity
import com.rhodesisland.terminal.data.local.SpecialEventMemoryEntity
import com.rhodesisland.terminal.data.local.SpecialEventMemoryMessageEntity
import com.rhodesisland.terminal.data.local.toDomain
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.SpecialEvent
import com.rhodesisland.terminal.data.repository.ConversationRepository
import com.rhodesisland.terminal.data.repository.SettingsRepository

sealed interface SpecialEventLaunchResult {
    data class Ready(
        val event: SpecialEvent,
        val opening: String,
    ) : SpecialEventLaunchResult
    data class Existing(val event: SpecialEvent) : SpecialEventLaunchResult
    data object Missing : SpecialEventLaunchResult
}

/**
 * 特殊邂逅会话协调器（Room v12 起：永久归档 + 导航壳）。
 *
 * 「开始/继续回忆」在**单个 Room 事务**内原子完成：
 * 1. 查询或补建 special_event；
 * 2. 若导航壳（普通 conversation）不存在则新建并回填 conversationId——修复并发点击下
 *    「检查→创建→更新」窗口产生幽灵会话的竞态（事务串行 + 二次读取）；
 * 3. INSERT OR IGNORE 归档元数据（special_event_memory）与开场消息
 *   （archiveKey = "opening:<eventId>"，幂等）；
 * 4. 更新 openingMemoryMessageId / startedAt / isRead。
 *
 * 开场消息**只写归档表**、不再调用 ChatRepository.addMessage（避免同时落普通表）。
 * 事件后续聊天经 [com.rhodesisland.terminal.data.repository.ChatRepository] 按
 * conversationId 路由到归档；导航壳只是路由/通知跳转用的空壳。
 */
class SpecialEventConversationCoordinator(
    private val database: AppDatabase,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val catalog: SpecialEventCatalog,
) {
    suspend fun launch(characterId: String, threshold: Int): SpecialEventLaunchResult {
        val result = database.withTransaction {
            var event = database.affinityDao().getSpecialEvent(characterId, threshold)
                ?: run {
                    // 事件未落库（好感度经其他路径越过阈值 / 数据异常）时：只要当前好感度达标就自动补建，
                    // 保证「开始」始终可进入对话，而非静默无响应（Missing）。
                    val affinity = database.affinityDao().getAffinity(characterId)?.value ?: 0f
                    if (affinity < threshold) return@withTransaction SpecialEventLaunchResult.Missing
                    val script = catalog.eventFor(characterId, threshold)
                    database.affinityDao().insertSpecialEvent(
                        SpecialEventEntity(
                            characterId = characterId,
                            threshold = threshold,
                            title = script.title,
                            sceneKey = SpecialEventCatalog.keyOf(characterId, threshold),
                            unlockedAt = System.currentTimeMillis(),
                        ),
                    )
                    database.affinityDao().getSpecialEvent(characterId, threshold)
                }
                ?: return@withTransaction SpecialEventLaunchResult.Missing

            // 归档元数据幂等补齐（v12 前旧事件迁移时已回填，此处兜底新触发路径）。
            database.specialEventMemoryDao().insertMemoryIgnore(
                SpecialEventMemoryEntity(
                    eventId = event.id,
                    characterId = event.characterId,
                    threshold = event.threshold,
                    title = event.title,
                    sourceConversationId = event.conversationId,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )

            var openingText: String? = null
            if (event.startedAt == null || event.conversationId == null) {
                val script = catalog.eventFor(characterId, threshold)
                // 并发点击下第二个事务会读到第一个事务已写入的 conversationId（Room 串行），
                // 走 else 分支，不会重复创建会话/开场消息。
                val shellId = event.conversationId?.takeIf { id ->
                    conversations.getById(id) != null
                } ?: run {
                    val newId = conversations.create(characterId, script.title)
                    event = event.copy(conversationId = newId)
                    newId
                }
                // 开场消息幂等写入归档（archiveKey = opening:<eventId>）。
                database.specialEventMemoryDao().insertMessageIgnore(
                    SpecialEventMemoryMessageEntity(
                        eventId = event.id,
                        archiveKey = "opening:${event.id}",
                        role = "assistant",
                        characterId = characterId,
                        content = script.opening,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
                val openingRowId = database.specialEventMemoryDao().findIdBySourcelessOpening(event.id)
                event = event.copy(
                    title = script.title,
                    sceneKey = SpecialEventCatalog.keyOf(characterId, threshold),
                    startedAt = event.startedAt ?: System.currentTimeMillis(),
                    isRead = true,
                    openingMemoryMessageId = openingRowId ?: event.openingMemoryMessageId,
                )
                openingText = script.opening
                database.affinityDao().updateSpecialEvent(event)
            } else {
                database.affinityDao().updateSpecialEvent(event.copy(isRead = true))
            }
            database.specialEventMemoryDao().touchMemory(event.id, System.currentTimeMillis())
            if (openingText != null) SpecialEventLaunchResult.Ready(event.toDomain(), openingText)
            else SpecialEventLaunchResult.Existing(event.toDomain())
        }
        val conversationId = when (result) {
            is SpecialEventLaunchResult.Ready -> result.event.conversationId
            is SpecialEventLaunchResult.Existing -> result.event.conversationId
            SpecialEventLaunchResult.Missing -> null
        }
        if (conversationId != null) {
            // 不在 Room 事务中触碰 DataStore：避免文件 I/O 把数据库写事务长时间占住。
            settings.setActiveCharacter(characterId)
            settings.setActiveConversation(characterId, conversationId)
            settings.setActiveProvider(ChatProviderType.CLOUD)
        }
        return result
    }

    suspend fun markRead(eventId: Long) {
        val event = database.affinityDao().getSpecialEventById(eventId) ?: return
        if (!event.isRead) database.affinityDao().updateSpecialEvent(event.copy(isRead = true))
    }
}
