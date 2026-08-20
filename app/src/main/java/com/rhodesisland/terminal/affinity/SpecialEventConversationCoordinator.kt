package com.rhodesisland.terminal.affinity

import androidx.room.withTransaction
import com.rhodesisland.terminal.data.local.AppDatabase
import com.rhodesisland.terminal.data.local.SpecialEventEntity
import com.rhodesisland.terminal.data.local.toDomain
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.SpecialEvent
import com.rhodesisland.terminal.data.repository.ChatRepository
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

class SpecialEventConversationCoordinator(
    private val database: AppDatabase,
    private val conversations: ConversationRepository,
    private val chats: ChatRepository,
    private val settings: SettingsRepository,
    private val catalog: SpecialEventCatalog,
) {
    suspend fun launch(characterId: String, threshold: Int): SpecialEventLaunchResult {
        val result = database.withTransaction {
            val event = database.affinityDao().getSpecialEvent(characterId, threshold)
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
            if (event.conversationId != null) {
                return@withTransaction SpecialEventLaunchResult.Existing(event.toDomain())
            }
            val script = catalog.eventFor(characterId, threshold)
            val conversationId = conversations.create(characterId, script.title)
            val openingId = chats.addMessage(
                characterId,
                conversationId,
                ChatMessage(role = "assistant", content = script.opening),
            )
            val updated = event.copy(
                title = script.title,
                sceneKey = SpecialEventCatalog.keyOf(characterId, threshold),
                startedAt = System.currentTimeMillis(),
                conversationId = conversationId,
                isRead = true,
                openingMessageId = openingId,
            )
            database.affinityDao().updateSpecialEvent(updated)
            SpecialEventLaunchResult.Ready(updated.toDomain(), script.opening)
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
