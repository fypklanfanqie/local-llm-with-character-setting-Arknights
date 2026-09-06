package com.rhodesisland.terminal.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Insert
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 小说模式（仿猫箱）：故事 → 章节（话）→ 脚本行。
 *
 * - [NovelStoryEntity]：一部故事。memberIdsJson 选中应用角色；customNpcsJson 自定义 NPC
 *   （名+人设 JSON 数组）；主控（protagonist）对应脚本里的 {{user}}。
 * - [NovelChapterEntity]：一章（话）。orderIndex 决定排序（swap 事务重排）；
 *   summary/opening/requirements 为「本话设定」四件套之三（title 之外）。
 * - [NovelLineEntity]：一行脚本。speakerType narration|user|character；
 *   speakerName 为显示名快照（改名不影响历史行）。
 */
@Entity(tableName = "novel_story")
data class NovelStoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val background: String = "",
    /** 参与的应用角色 id JSON 数组。 */
    val memberIdsJson: String = "[]",
    /** 自定义 NPC JSON 数组：[{"name":"...","persona":"..."}]。 */
    val customNpcsJson: String = "[]",
    /** 主控显示名（空 = 无主控，脚本无 user 行）。 */
    val protagonistName: String = "",
    val protagonistPersona: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "novel_chapter",
    indices = [Index(value = ["storyId", "orderIndex"])],
)
data class NovelChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storyId: Long,
    val orderIndex: Int,
    val title: String = "",
    /** 前情/本话摘要。 */
    val summary: String = "",
    /** 本话开场白。 */
    val opening: String = "",
    /** 发生、发展、结果与写作要求。 */
    val requirements: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "novel_line",
    indices = [Index(value = ["chapterId", "lineOrder"])],
)
data class NovelLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val lineOrder: Int,
    /** narration | user | character */
    val speakerType: String,
    /** 显示名快照（旁白/主控名/角色名）。 */
    val speakerName: String,
    /** 角色行对应的应用角色 id（可空：自定义 NPC 或改名后失配）。 */
    val characterId: String? = null,
    val content: String,
)

/** 章节 + 行聚合。 */
data class NovelChapterWithLines(
    @Embedded val chapter: NovelChapterEntity,
    @Relation(parentColumn = "id", entityColumn = "chapterId")
    val lines: List<NovelLineEntity>,
)

@Dao
interface NovelDao {

    // ===== 故事 =====

    @Query("SELECT * FROM novel_story ORDER BY updatedAt DESC")
    fun observeStories(): Flow<List<NovelStoryEntity>>

    @Query("SELECT * FROM novel_story WHERE id = :storyId")
    suspend fun getStory(storyId: Long): NovelStoryEntity?

    @Query("SELECT * FROM novel_story WHERE id = :storyId")
    fun observeStory(storyId: Long): Flow<NovelStoryEntity?>

    @Insert
    suspend fun insertStory(entity: NovelStoryEntity): Long

    @Update
    suspend fun updateStory(entity: NovelStoryEntity)

    @Query("UPDATE novel_story SET updatedAt = :now WHERE id = :storyId")
    suspend fun touchStory(storyId: Long, now: Long)

    @Query("DELETE FROM novel_story WHERE id = :storyId")
    suspend fun deleteStory(storyId: Long)

    // ===== 章节 =====

    @Query("SELECT * FROM novel_chapter WHERE storyId = :storyId ORDER BY orderIndex ASC")
    fun observeChapters(storyId: Long): Flow<List<NovelChapterEntity>>

    @Transaction
    @Query("SELECT * FROM novel_chapter WHERE id = :chapterId")
    fun observeChapterWithLines(chapterId: Long): Flow<NovelChapterWithLines?>

    @Query("SELECT * FROM novel_chapter WHERE id = :chapterId")
    suspend fun getChapter(chapterId: Long): NovelChapterEntity?

    @Query("SELECT MAX(orderIndex) FROM novel_chapter WHERE storyId = :storyId")
    suspend fun maxChapterOrder(storyId: Long): Int?

    @Insert
    suspend fun insertChapter(entity: NovelChapterEntity): Long

    @Update
    suspend fun updateChapter(entity: NovelChapterEntity)

    /** 相邻章节交换顺序（上移/下移）：orderIndex 互换，同事务原子完成。 */
    @Transaction
    suspend fun swapChapterOrder(firstId: Long, secondId: Long, firstOrder: Int, secondOrder: Int) {
        val now = System.currentTimeMillis()
        updateChapterOrderRaw(firstId, -1, now) // 先错开避免冲突（保持确定性）
        updateChapterOrderRaw(firstId, secondOrder, now)
        updateChapterOrderRaw(secondId, firstOrder, now)
    }

    @Query("UPDATE novel_chapter SET orderIndex = :orderIndex, updatedAt = :now WHERE id = :chapterId")
    suspend fun updateChapterOrderRaw(chapterId: Long, orderIndex: Int, now: Long)

    @Query("DELETE FROM novel_chapter WHERE id = :chapterId")
    suspend fun deleteChapter(chapterId: Long)

    // ===== 行 =====

    @Query("SELECT * FROM novel_line WHERE chapterId = :chapterId ORDER BY lineOrder ASC")
    suspend fun getLines(chapterId: Long): List<NovelLineEntity>

    @Query("SELECT MAX(lineOrder) FROM novel_line WHERE chapterId = :chapterId")
    suspend fun maxLineOrder(chapterId: Long): Int?

    @Insert
    suspend fun insertLine(entity: NovelLineEntity): Long

    @Update
    suspend fun updateLine(entity: NovelLineEntity)

    @Query("DELETE FROM novel_line WHERE id = :lineId")
    suspend fun deleteLine(lineId: Long)

    /** 删除章节时级联清行（无外键，事务手动级联）。 */
    @Transaction
    suspend fun deleteChapterCascade(chapterId: Long) {
        deleteLinesOfChapter(chapterId)
        deleteChapter(chapterId)
    }

    /** 删除故事时级联清章节与行。 */
    @Transaction
    suspend fun deleteStoryCascade(storyId: Long) {
        getChapterIds(storyId).forEach { chapterId ->
            deleteLinesOfChapter(chapterId)
        }
        deleteChaptersOfStory(storyId)
        deleteStory(storyId)
    }

    @Query("DELETE FROM novel_line WHERE chapterId = :chapterId")
    suspend fun deleteLinesOfChapter(chapterId: Long)

    @Query("SELECT id FROM novel_chapter WHERE storyId = :storyId")
    suspend fun getChapterIds(storyId: Long): List<Long>

    @Query("DELETE FROM novel_chapter WHERE storyId = :storyId")
    suspend fun deleteChaptersOfStory(storyId: Long)
}
