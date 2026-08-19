package com.rhodesisland.terminal.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.rhodesisland.terminal.data.model.CharacterAffinity
import com.rhodesisland.terminal.data.model.GiftDefinition
import com.rhodesisland.terminal.data.model.GiftHistory
import com.rhodesisland.terminal.data.model.GiftInventory
import com.rhodesisland.terminal.data.model.LungmenWallet
import com.rhodesisland.terminal.data.model.SpecialEvent
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "character_affinity")
data class CharacterAffinityEntity(
    @PrimaryKey val characterId: String,
    val value: Float,
    val updatedAt: Long,
)

@Entity(tableName = "lungmen_wallet")
data class LungmenWalletEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val balance: Long,
    val updatedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(tableName = "daily_checkin")
data class DailyCheckinEntity(
    @PrimaryKey val dayKey: String,
    val claimedAt: Long,
)

/** 每天首次进入 App 的签到提示状态；提示关闭并不代表已经领取。 */
@Entity(tableName = "daily_checkin_prompt")
data class DailyCheckinPromptEntity(
    @PrimaryKey val dayKey: String,
    val shownAt: Long,
)

@Entity(tableName = "gift_definition")
data class GiftDefinitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val imagePath: String,
    val price: Long,
    val affinityGain: Float,
    val createdAt: Long,
)

@Entity(tableName = "gift_inventory")
data class GiftInventoryEntity(
    @PrimaryKey val giftId: Long,
    val quantity: Int,
    val updatedAt: Long,
)

@Entity(
    tableName = "gift_history",
    indices = [Index(value = ["characterId", "sentAt"]), Index(value = ["giftId"])],
)
data class GiftHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: String,
    val giftId: Long,
    val giftName: String,
    val giftDescription: String,
    val giftImagePath: String,
    val price: Long,
    val affinityGain: Float,
    val sentAt: Long,
    val conversationId: Long,
    val thankYouText: String = "",
)

@Entity(
    tableName = "special_event",
    indices = [
        Index(value = ["characterId", "threshold"], unique = true),
        Index(value = ["conversationId"], unique = true),
    ],
)
data class SpecialEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: String,
    val threshold: Int,
    val title: String,
    val sceneKey: String,
    val unlockedAt: Long,
    val startedAt: Long? = null,
    val conversationId: Long? = null,
    val isRead: Boolean = false,
    val openingMessageId: Long? = null,
)

/** 以 sourceKey 做唯一约束，阻止消息/视频重试重复增加好感。 */
@Entity(tableName = "affinity_reward")
data class AffinityRewardEntity(
    @PrimaryKey val sourceKey: String,
    val characterId: String,
    val amount: Float,
    val source: String,
    val createdAt: Long,
)

data class OwnedGiftRow(
    val id: Long,
    val name: String,
    val description: String,
    val imagePath: String,
    val price: Long,
    val affinityGain: Float,
    val createdAt: Long,
    val quantity: Int,
    val inventoryUpdatedAt: Long,
)

@Dao
interface AffinityDao {
    @Query("SELECT * FROM character_affinity WHERE characterId = :characterId")
    suspend fun getAffinity(characterId: String): CharacterAffinityEntity?

    @Query("SELECT * FROM character_affinity WHERE characterId = :characterId")
    fun observeAffinity(characterId: String): Flow<CharacterAffinityEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAffinity(entity: CharacterAffinityEntity)

    @Query("SELECT * FROM lungmen_wallet WHERE id = ${LungmenWalletEntity.SINGLETON_ID}")
    suspend fun getWallet(): LungmenWalletEntity?

    @Query("SELECT * FROM lungmen_wallet WHERE id = ${LungmenWalletEntity.SINGLETON_ID}")
    fun observeWallet(): Flow<LungmenWalletEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWallet(entity: LungmenWalletEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCheckin(entity: DailyCheckinEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCheckinPrompt(entity: DailyCheckinPromptEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM daily_checkin_prompt WHERE dayKey = :dayKey)")
    suspend fun hasShownCheckinPrompt(dayKey: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM daily_checkin WHERE dayKey = :dayKey)")
    fun observeCheckinClaimed(dayKey: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM daily_checkin WHERE dayKey = :dayKey)")
    suspend fun isCheckinClaimed(dayKey: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReward(entity: AffinityRewardEntity): Long

    @Query("SELECT threshold FROM special_event WHERE characterId = :characterId")
    suspend fun unlockedThresholds(characterId: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSpecialEvent(entity: SpecialEventEntity): Long

    @Query("SELECT * FROM special_event WHERE characterId = :characterId ORDER BY threshold ASC")
    fun observeSpecialEvents(characterId: String): Flow<List<SpecialEventEntity>>

    @Query("SELECT * FROM special_event WHERE characterId = :characterId AND threshold = :threshold")
    suspend fun getSpecialEvent(characterId: String, threshold: Int): SpecialEventEntity?

    @Query("SELECT * FROM special_event WHERE id = :eventId")
    suspend fun getSpecialEventById(eventId: Long): SpecialEventEntity?

    @Query("SELECT * FROM special_event WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getSpecialEventByConversation(conversationId: Long): SpecialEventEntity?

    @Query("SELECT COUNT(*) FROM special_event WHERE characterId = :characterId AND isRead = 0")
    fun observeUnreadUnlockCount(characterId: String): Flow<Int>

    @Update
    suspend fun updateSpecialEvent(entity: SpecialEventEntity)

    @Insert
    suspend fun insertGift(entity: GiftDefinitionEntity): Long

    @Update
    suspend fun updateGift(entity: GiftDefinitionEntity)

    @Query("DELETE FROM gift_definition WHERE id = :giftId")
    suspend fun deleteGift(giftId: Long)

    @Query("SELECT * FROM gift_definition WHERE id = :giftId")
    suspend fun getGift(giftId: Long): GiftDefinitionEntity?

    @Query("SELECT * FROM gift_definition ORDER BY createdAt DESC")
    fun observeGifts(): Flow<List<GiftDefinitionEntity>>

    @Query(
        "SELECT d.id, d.name, d.description, d.imagePath, d.price, d.affinityGain, d.createdAt, " +
            "COALESCE(i.quantity, 0) AS quantity, COALESCE(i.updatedAt, 0) AS inventoryUpdatedAt FROM gift_definition d " +
            "LEFT JOIN gift_inventory i ON d.id = i.giftId ORDER BY d.createdAt DESC"
    )
    fun observeOwnedGifts(): Flow<List<OwnedGiftRow>>

    @Query("SELECT * FROM gift_inventory WHERE giftId = :giftId")
    suspend fun getInventory(giftId: Long): GiftInventoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInventory(entity: GiftInventoryEntity)

    @Query("UPDATE gift_inventory SET quantity = quantity - 1, updatedAt = :updatedAt WHERE giftId = :giftId AND quantity > 0")
    suspend fun decrementInventoryIfAvailable(giftId: Long, updatedAt: Long): Int

    @Insert
    suspend fun insertGiftHistory(entity: GiftHistoryEntity): Long

    @Query("SELECT * FROM gift_history WHERE id = :historyId")
    suspend fun getGiftHistory(historyId: Long): GiftHistoryEntity?

    @Query("UPDATE gift_history SET thankYouText = :text WHERE id = :historyId")
    suspend fun updateGiftThankYouText(historyId: Long, text: String)

    @Query("SELECT * FROM gift_history WHERE characterId = :characterId ORDER BY sentAt DESC")
    fun observeGiftHistory(characterId: String): Flow<List<GiftHistoryEntity>>

    @Transaction
    suspend fun claimCheckinIfAvailable(dayKey: String, now: Long, reward: Long): Boolean {
        if (insertCheckin(DailyCheckinEntity(dayKey, now)) == -1L) return false
        val wallet = getWallet() ?: LungmenWalletEntity(balance = 0L, updatedAt = now)
        upsertWallet(wallet.copy(balance = wallet.balance + reward, updatedAt = now))
        return true
    }
}

internal fun CharacterAffinityEntity.toDomain() = CharacterAffinity(characterId, value, updatedAt)
internal fun LungmenWalletEntity.toDomain() = LungmenWallet(balance, updatedAt)
internal fun GiftDefinitionEntity.toDomain() = GiftDefinition(id, name, description, imagePath, price, affinityGain, createdAt)
internal fun GiftInventoryEntity.toDomain() = GiftInventory(giftId, quantity, updatedAt)
internal fun GiftHistoryEntity.toDomain() = GiftHistory(id, characterId, giftId, giftName, giftDescription, giftImagePath, price, affinityGain, sentAt, conversationId, thankYouText)
internal fun SpecialEventEntity.toDomain() = SpecialEvent(id, characterId, threshold, title, sceneKey, unlockedAt, startedAt, conversationId, isRead, openingMessageId)
