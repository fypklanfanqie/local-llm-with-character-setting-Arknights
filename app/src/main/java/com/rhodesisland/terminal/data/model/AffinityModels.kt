package com.rhodesisland.terminal.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CharacterAffinity(
    val characterId: String,
    val value: Float,
    val updatedAt: Long,
)

@Serializable
data class LungmenWallet(
    val balance: Long,
    val updatedAt: Long,
)

@Serializable
data class GiftDefinition(
    val id: Long,
    val name: String,
    val description: String,
    val imagePath: String,
    val price: Long,
    val affinityGain: Float,
    val createdAt: Long,
)

@Serializable
data class GiftInventory(
    val giftId: Long,
    val quantity: Int,
    val updatedAt: Long,
)

@Serializable
data class GiftHistory(
    val id: Long,
    val characterId: String,
    val giftId: Long,
    val giftName: String,
    val giftDescription: String,
    val giftImagePath: String,
    val price: Long,
    val affinityGain: Float,
    val sentAt: Long,
    val conversationId: Long,
)

@Serializable
data class SpecialEvent(
    val id: Long,
    val characterId: String,
    val threshold: Int,
    val title: String,
    val sceneKey: String,
    val unlockedAt: Long,
    val startedAt: Long? = null,
    val conversationId: Long? = null,
    val isRead: Boolean = false,
)
