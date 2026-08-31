package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.local.MomentCommentEntity
import com.rhodesisland.terminal.data.local.MomentDao
import com.rhodesisland.terminal.data.local.MomentLikeEntity
import com.rhodesisland.terminal.data.local.MomentPostEntity
import com.rhodesisland.terminal.data.local.MomentPostWithComments
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 朋友圈仓库：帖子/评论/点赞的落库与查询。
 *
 * - 列表走 [observeRecentPosts] 窗口（[AppConfig.Moment.FEED_WINDOW] 条，超出 DAO 修剪）；
 * - 图片路径以 JSON 数组持久化在 moment_post.imagesJson（file:// URI，复制落盘后不依赖相册权限）。
 */
class MomentRepository(
    private val dao: MomentDao,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun observeRecentPosts(limit: Int = AppConfig.Moment.FEED_WINDOW): Flow<List<MomentPostWithComments>> =
        dao.observeRecentPosts(limit)

    suspend fun getPost(postId: Long): MomentPostWithComments? = dao.getPostWithInteractions(postId)

    /** 新建角色帖（AI 生成）。返回行 id。 */
    suspend fun addCharacterPost(
        characterId: String,
        content: String,
        images: List<String>,
        imagePrompt: String? = null,
    ): Long {
        val postId = dao.insertPost(
            MomentPostEntity(
                authorType = AUTHOR_CHARACTER,
                characterId = characterId,
                content = content.take(AppConfig.Moment.CAPTION_MAX_CHARS),
                imagesJson = json.encodeToString(images),
                createdAt = System.currentTimeMillis(),
                imagePrompt = imagePrompt,
            ),
        )
        trimIfNeeded()
        return postId
    }

    /** 新建用户帖（手发：文字 + 相册图片，不走 API）。返回行 id。 */
    suspend fun addUserPost(content: String, images: List<String>): Long {
        val postId = dao.insertPost(
            MomentPostEntity(
                authorType = AUTHOR_USER,
                content = content.take(AppConfig.Moment.CAPTION_MAX_CHARS),
                imagesJson = json.encodeToString(images),
                createdAt = System.currentTimeMillis(),
            ),
        )
        trimIfNeeded()
        return postId
    }

    /** 用户点赞/取消点赞（幂等）。返回 true = 现在已点赞。 */
    suspend fun toggleUserLike(postId: Long): Boolean {
        return if (dao.hasUserLiked(postId)) {
            dao.deleteUserLike(postId)
            false
        } else {
            dao.insertLike(MomentLikeEntity(postId = postId, characterId = null, createdAt = System.currentTimeMillis()))
            true
        }
    }

    /**
     * 用户评论并让发帖角色回复。
     * 先落用户评论；[reply] 由调用方生成后经 [addCharacterComment] 落库
     * （拆开以便 ViewModel 在生成期间展示 typing 态）。
     */
    suspend fun addUserComment(postId: Long, content: String): Long =
        dao.insertComment(
            MomentCommentEntity(
                postId = postId,
                authorType = AUTHOR_USER,
                content = content,
                createdAt = System.currentTimeMillis(),
            ),
        )

    suspend fun addCharacterComment(postId: Long, characterId: String, content: String): Long =
        dao.insertComment(
            MomentCommentEntity(
                postId = postId,
                authorType = AUTHOR_CHARACTER,
                characterId = characterId,
                content = content,
                createdAt = System.currentTimeMillis(),
            ),
        )

    /** 取帖子全部评论（评论回复提示词上下文用）。 */
    suspend fun getComments(postId: Long): List<MomentCommentEntity> = dao.getComments(postId)

    /** 帖子的点赞角色 id 列表（自动点赞候选去重用）。 */
    suspend fun likeCharacterIds(postId: Long): List<String?> = dao.likeCharacterIds(postId)

    /** 用户删除自己的帖子（级联评论/点赞）。 */
    suspend fun deletePost(postId: Long) = dao.deletePostCascade(postId)

    /** 超出窗口时修剪最旧帖子（互动行随帖子删除）。 */
    private suspend fun trimIfNeeded() {
        if (dao.postCount() > AppConfig.Moment.FEED_WINDOW) {
            dao.trimToLatest(AppConfig.Moment.FEED_WINDOW)
        }
    }

    companion object {
        const val AUTHOR_USER = "user"
        const val AUTHOR_CHARACTER = "character"
    }
}
