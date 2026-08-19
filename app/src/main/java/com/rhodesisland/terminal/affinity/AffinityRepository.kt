package com.rhodesisland.terminal.affinity

import androidx.room.withTransaction
import com.rhodesisland.terminal.data.local.AffinityDao
import com.rhodesisland.terminal.data.local.AffinityRewardEntity
import com.rhodesisland.terminal.data.local.CharacterAffinityEntity
import com.rhodesisland.terminal.data.local.DailyCheckinEntity
import com.rhodesisland.terminal.data.local.GiftDefinitionEntity
import com.rhodesisland.terminal.data.local.GiftHistoryEntity
import com.rhodesisland.terminal.data.local.GiftInventoryEntity
import com.rhodesisland.terminal.data.local.LungmenWalletEntity
import com.rhodesisland.terminal.data.local.SpecialEventEntity
import com.rhodesisland.terminal.data.local.AppDatabase
import com.rhodesisland.terminal.data.model.CharacterAffinity
import com.rhodesisland.terminal.data.model.GiftDefinition
import com.rhodesisland.terminal.data.model.GiftHistory
import com.rhodesisland.terminal.data.model.GiftInventory
import com.rhodesisland.terminal.data.model.LungmenWallet
import com.rhodesisland.terminal.data.model.SpecialEvent
import com.rhodesisland.terminal.data.local.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

sealed interface AffinityRewardResult {
    data class Applied(
        val affinity: CharacterAffinity,
        val unlockedThresholds: List<Int>,
    ) : AffinityRewardResult
    data object AlreadyApplied : AffinityRewardResult
}

sealed interface CheckinResult {
    data class Claimed(val wallet: LungmenWallet) : CheckinResult
    data class AlreadyClaimed(val wallet: LungmenWallet) : CheckinResult
}

sealed interface GiftPurchaseResult {
    data class Purchased(val wallet: LungmenWallet, val inventory: GiftInventory) : GiftPurchaseResult
    data object InsufficientFunds : GiftPurchaseResult
    data object GiftMissing : GiftPurchaseResult
}

sealed interface GiftSendResult {
    data class Sent(
        val history: GiftHistory,
        val affinity: CharacterAffinity,
        val unlockedThresholds: List<Int>,
    ) : GiftSendResult
    data object InventoryEmpty : GiftSendResult
    data object GiftMissing : GiftSendResult
}

class AffinityRepository(private val database: AppDatabase) {
    private val dao: AffinityDao get() = database.affinityDao()

    fun observeAffinity(characterId: String): Flow<CharacterAffinity> =
        dao.observeAffinity(characterId).map { entity ->
            entity?.toDomain() ?: CharacterAffinity(characterId, 0f, 0L)
        }

    fun observeWallet(): Flow<LungmenWallet> = dao.observeWallet().map { entity ->
        entity?.toDomain() ?: LungmenWallet(0L, 0L)
    }

    fun observeCheckinClaimed(dayKey: String = todayKey()): Flow<Boolean> = dao.observeCheckinClaimed(dayKey)

    fun observeGifts(): Flow<List<GiftDefinition>> = dao.observeGifts().map { list -> list.map { it.toDomain() } }

    fun observeOwnedGifts(): Flow<List<OwnedGift>> = dao.observeOwnedGifts().map { rows ->
        rows.map { row ->
            OwnedGift(
                definition = GiftDefinition(row.id, row.name, row.description, row.imagePath, row.price, row.affinityGain, row.createdAt),
                inventory = GiftInventory(row.id, row.quantity, row.inventoryUpdatedAt),
            )
        }
    }

    fun observeGiftHistory(characterId: String): Flow<List<GiftHistory>> =
        dao.observeGiftHistory(characterId).map { list -> list.map { it.toDomain() } }

    fun observeSpecialEvents(characterId: String): Flow<List<SpecialEvent>> =
        dao.observeSpecialEvents(characterId).map { list -> list.map { it.toDomain() } }

    fun observeUnreadUnlockCount(characterId: String): Flow<Int> = dao.observeUnreadUnlockCount(characterId)

    suspend fun addChatAffinity(characterId: String, messageId: Long): AffinityRewardResult =
        addReward(characterId, CHAT_AFFINITY_GAIN, "chat:$messageId", "chat")

    suspend fun addVideoAffinity(characterId: String, videoId: Long): AffinityRewardResult =
        addReward(characterId, VIDEO_AFFINITY_GAIN, "video:$videoId", "video")

    suspend fun claimDailyCheckin(dayKey: String = todayKey()): CheckinResult = database.withTransaction {
        val now = System.currentTimeMillis()
        val claimed = dao.claimCheckinIfAvailable(dayKey, now, DAILY_CHECKIN_LMD)
        val wallet = (dao.getWallet() ?: LungmenWalletEntity(balance = 0L, updatedAt = now)).toDomain()
        if (claimed) CheckinResult.Claimed(wallet) else CheckinResult.AlreadyClaimed(wallet)
    }

    suspend fun createGift(name: String, description: String, imagePath: String, price: Long): GiftDefinition {
        val gain = requireNotNull(affinityGainForGiftPrice(price)) { "礼物价格必须在 5000 至 20000 龙门币的有效档位内" }
        require(name.isNotBlank()) { "礼物名称不能为空" }
        require(imagePath.isNotBlank()) { "请选择礼物图片" }
        val now = System.currentTimeMillis()
        val id = dao.insertGift(
            GiftDefinitionEntity(
                name = name.trim(),
                description = description.trim(),
                imagePath = imagePath,
                price = price,
                affinityGain = gain,
                createdAt = now,
            ),
        )
        return requireNotNull(dao.getGift(id)).toDomain()
    }

    suspend fun buyGift(giftId: Long): GiftPurchaseResult = database.withTransaction {
        val gift = dao.getGift(giftId) ?: return@withTransaction GiftPurchaseResult.GiftMissing
        val now = System.currentTimeMillis()
        val wallet = dao.getWallet() ?: LungmenWalletEntity(balance = 0L, updatedAt = now)
        if (wallet.balance < gift.price) return@withTransaction GiftPurchaseResult.InsufficientFunds
        val inventory = dao.getInventory(giftId) ?: GiftInventoryEntity(giftId, 0, now)
        dao.upsertWallet(wallet.copy(balance = wallet.balance - gift.price, updatedAt = now))
        val newInventory = inventory.copy(quantity = inventory.quantity + 1, updatedAt = now)
        dao.upsertInventory(newInventory)
        GiftPurchaseResult.Purchased((dao.getWallet() ?: error("钱包写入失败")).toDomain(), newInventory.toDomain())
    }

    suspend fun sendGift(characterId: String, giftId: Long, conversationId: Long): GiftSendResult = database.withTransaction {
        val gift = dao.getGift(giftId) ?: return@withTransaction GiftSendResult.GiftMissing
        val now = System.currentTimeMillis()
        if (dao.decrementInventoryIfAvailable(giftId, now) != 1) return@withTransaction GiftSendResult.InventoryEmpty
        val historyId = dao.insertGiftHistory(
            GiftHistoryEntity(
                characterId = characterId,
                giftId = gift.id,
                giftName = gift.name,
                giftDescription = gift.description,
                giftImagePath = gift.imagePath,
                price = gift.price,
                affinityGain = gift.affinityGain,
                sentAt = now,
                conversationId = conversationId,
            ),
        )
        val reward = applyRewardInTransaction(characterId, gift.affinityGain, "gift:$historyId", "gift", now)
        when (reward) {
            is AffinityRewardResult.Applied -> {
                val history = requireNotNull(dao.getGiftHistory(historyId)).toDomain()
                GiftSendResult.Sent(history, reward.affinity, reward.unlockedThresholds)
            }
            AffinityRewardResult.AlreadyApplied -> error("礼物奖励键冲突")
        }
    }

    suspend fun saveGiftThankYouText(historyId: Long, text: String) {
        dao.updateGiftThankYouText(historyId, text.take(1_000))
    }

    private suspend fun addReward(
        characterId: String,
        amount: Float,
        sourceKey: String,
        source: String,
    ): AffinityRewardResult = database.withTransaction {
        applyRewardInTransaction(characterId, amount, sourceKey, source, System.currentTimeMillis())
    }

    private suspend fun applyRewardInTransaction(
        characterId: String,
        amount: Float,
        sourceKey: String,
        source: String,
        now: Long,
    ): AffinityRewardResult {
        if (dao.insertReward(AffinityRewardEntity(sourceKey, characterId, amount, source, now)) == -1L) {
            return AffinityRewardResult.AlreadyApplied
        }
        val previous = dao.getAffinity(characterId) ?: CharacterAffinityEntity(characterId, 0f, now)
        val currentValue = clampAffinity(previous.value + amount)
        val unlocked = dao.unlockedThresholds(characterId).toSet()
        val thresholds = crossedAffinityThresholds(previous.value, currentValue, unlocked)
        dao.upsertAffinity(previous.copy(value = currentValue, updatedAt = now))
        thresholds.forEach { threshold ->
            dao.insertSpecialEvent(
                SpecialEventEntity(
                    characterId = characterId,
                    threshold = threshold,
                    title = "好感度事件 $threshold",
                    sceneKey = "$characterId-$threshold",
                    unlockedAt = now,
                ),
            )
        }
        return AffinityRewardResult.Applied(CharacterAffinity(characterId, currentValue, now), thresholds)
    }

    companion object {
        fun todayKey(): String = LocalDate.now().toString()
    }
}

data class OwnedGift(
    val definition: GiftDefinition,
    val inventory: GiftInventory,
)
