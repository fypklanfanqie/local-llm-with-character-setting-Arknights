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
    private val affinityRepository: AffinityRepository,
    private val conversations: ConversationRepository,
    private val chats: ChatRepository,
    private val settings: SettingsRepository,
    private val catalog: SpecialEventCatalog,
) {
    suspend fun launch(characterId: String, threshold: Int): SpecialEventLaunchResult = database.withTransaction {
        val event = database.affinityDao().getSpecialEvent(characterId, threshold)
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
        settings.setActiveConversation(characterId, conversationId)
        // 事件由云端主动开场；进入事件时永久保留云端选择，退出后用户可自行切换。
        settings.setActiveProvider(ChatProviderType.CLOUD)
        SpecialEventLaunchResult.Ready(updated.toDomain(), script.opening)
    }

    suspend fun markRead(eventId: Long) {
        val event = database.affinityDao().getSpecialEventById(eventId) ?: return
        if (!event.isRead) database.affinityDao().updateSpecialEvent(event.copy(isRead = true))
    }
}
