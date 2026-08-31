package com.rhodesisland.terminal.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Insert
import kotlinx.coroutines.flow.Flow

/**
 * 朋友圈（仿微信）：角色经云端 LLM + 生图 API 发帖，用户/角色互动。
 *
 * - [MomentPostEntity]：一条帖子。authorType 区分用户手发（纯文字+相册图）与角色生成
 *   （LLM 文案 + 生图落盘路径）；characterId 仅角色帖有意义；imagePrompt 存生图提示词
 *   （调试/重生成用）。
 * - [MomentCommentEntity]：评论。authorType 同上；角色评论即「发帖者回复用户评论」。
 * - [MomentLikeEntity]：点赞。unique(postId, characterId) 防重；characterId 为 null 表示用户点赞。
 */
@Entity(
    tableName = "moment_post",
    indices = [Index(value = ["createdAt"])],
)
data class MomentPostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "user" | "character" */
    val authorType: String,
    /** 作者角色 id（authorType = "character" 时非空）。 */
    val characterId: String? = null,
    val content: String,
    /** 落盘图片路径 JSON 数组（filesDir/moment_images 下 file:// URI，空数组=纯文字）。 */
    val imagesJson: String = "[]",
    val createdAt: Long,
    /** 生成该帖使用的生图提示词（authorType = "character" 时；调试/追溯用）。 */
    val imagePrompt: String? = null,
)

@Entity(
    tableName = "moment_comment",
    indices = [Index(value = ["postId", "createdAt"])],
)
data class MomentCommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: Long,
    /** "user" | "character" */
    val authorType: String,
    /** 评论者角色 id（authorType = "character" 时非空；朋友圈里只有发帖者会评论）。 */
    val characterId: String? = null,
    val content: String,
    val createdAt: Long,
)

@Entity(
    tableName = "moment_like",
    indices = [Index(value = ["postId", "characterId"], unique = true)],
)
data class MomentLikeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: Long,
    /** 点赞角色 id；null = 用户点赞。 */
    val characterId: String? = null,
    val createdAt: Long,
)

/** 帖子 + 评论/点赞聚合（@Transaction + @Relation 一对多查询）。 */
data class MomentPostWithComments(
    @Embedded val post: MomentPostEntity,
    @Relation(parentColumn = "id", entityColumn = "postId")
    val comments: List<MomentCommentEntity>,
    @Relation(parentColumn = "id", entityColumn = "postId")
    val likes: List<MomentLikeEntity>,
)

@Dao
interface MomentDao {

    /** 最近帖子窗口（朋友圈只展示最近 N 条，含用户与角色帖，倒序）。 */
    @Transaction
    @Query(
        "SELECT * FROM moment_post ORDER BY createdAt DESC, id DESC LIMIT :limit",
    )
    fun observeRecentPosts(limit: Int): Flow<List<MomentPostWithComments>>

    @Query("SELECT * FROM moment_post WHERE id = :postId")
    suspend fun getPost(postId: Long): MomentPostEntity?

    @Transaction
    @Query("SELECT * FROM moment_post WHERE id = :postId")
    fun observePost(postId: Long): Flow<MomentPostWithComments?>

    @Transaction
    @Query("SELECT * FROM moment_post WHERE id = :postId")
    suspend fun getPostWithInteractions(postId: Long): MomentPostWithComments?

    @Query("SELECT * FROM moment_comment WHERE postId = :postId ORDER BY createdAt ASC, id ASC")
    suspend fun getComments(postId: Long): List<MomentCommentEntity>

    @Insert
    suspend fun insertPost(entity: MomentPostEntity): Long

    @Insert
    suspend fun insertComment(entity: MomentCommentEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLike(entity: MomentLikeEntity): Long

    @Query("DELETE FROM moment_like WHERE postId = :postId AND characterId IS NULL")
    suspend fun deleteUserLike(postId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM moment_like WHERE postId = :postId AND characterId IS NULL)")
    suspend fun hasUserLiked(postId: Long): Boolean

    @Query("SELECT characterId FROM moment_like WHERE postId = :postId")
    suspend fun likeCharacterIds(postId: Long): List<String?>

    @Query("SELECT COUNT(*) FROM moment_post")
    suspend fun postCount(): Int

    @Query(
        "DELETE FROM moment_post WHERE id NOT IN " +
            "(SELECT id FROM moment_post ORDER BY createdAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimToLatest(keep: Int)

    /** 删除单条帖子及其全部互动（用户删自己的帖子）。 */
    @Transaction
    suspend fun deletePostCascade(postId: Long) {
        deleteLikesOfPost(postId)
        deleteCommentsOfPost(postId)
        deletePost(postId)
    }

    @Query("DELETE FROM moment_like WHERE postId = :postId")
    suspend fun deleteLikesOfPost(postId: Long)

    @Query("DELETE FROM moment_comment WHERE postId = :postId")
    suspend fun deleteCommentsOfPost(postId: Long)

    @Query("DELETE FROM moment_post WHERE id = :postId")
    suspend fun deletePost(postId: Long)
}
