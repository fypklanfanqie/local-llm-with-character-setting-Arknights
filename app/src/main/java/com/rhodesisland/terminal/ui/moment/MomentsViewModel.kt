package com.rhodesisland.terminal.ui.moment

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.local.MomentCommentEntity
import com.rhodesisland.terminal.data.local.MomentPostEntity
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.model.UserProfileConfig
import com.rhodesisland.terminal.data.repository.MomentRepository
import com.rhodesisland.terminal.util.CharacterImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 朋友圈 ViewModel。
 *
 * - 帖子流：Room Flow（含评论/点赞）+ 角色表 + 博士档案聚合；
 * - 角色发圈：手动触发（选角色 + 图数）→ [AppContainer.momentGenerationCoordinator]；
 * - 评论：用户评论落库 → 发帖角色必回（生成中 typing 态）；
 * - 用户自发自发圈：文字 + 相册图（复制到内部存储，不走 API）。
 */
class MomentsViewModel(
    private val app: Context,
    private val container: AppContainer,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val momentRepository: MomentRepository = container.momentRepository

    /** 生成中的帖子 id（角色发圈/评论回复期间禁重复操作 + 显示进行中）。 */
    data class Generating(
        val posting: Boolean = false,
        val replyingPostId: Long? = null,
    )

    private val generating = MutableStateFlow(Generating())
    private val errorMessage = MutableStateFlow<String?>(null)
    private val userTick = MutableStateFlow(0L) // 相对时间刷新信号

    data class UiState(
        val posts: List<PostUi> = emptyList(),
        val userAvatar: String = "",
        val isCloud: Boolean = true,
        val generating: Generating = Generating(),
        val errorMessage: String? = null,
        val nowMs: Long = 0L,
    )

    data class PostUi(
        val post: MomentPostEntity,
        val comments: List<MomentCommentEntity>,
        val likedByUser: Boolean,
        val likeCharacterIds: List<String>,
        val authorName: String,
        val authorImage: String,
        /** 帖子图片（file:// URI 列表，JSON 解码失败为空）。 */
        val images: List<String> = emptyList(),
    )

    val uiState: StateFlow<UiState> = combine(
        momentRepository.observeRecentPosts(),
        container.characterRepository.characters,
        container.settingsRepository.userProfile,
        container.settingsRepository.activeProvider,
        combine(generating, errorMessage, userTick) { g, e, t -> MomentRuntime(gen = g, err = e, tick = t) },
    ) { posts, characters, profile, provider, runtime ->
        val charById = characters.associateBy { it.id }
        val now = System.currentTimeMillis()
        UiState(
            posts = posts.map { row ->
                val isCharacter = row.post.authorType == MomentRepository.AUTHOR_CHARACTER
                val char = row.post.characterId?.let { charById[it] }
                PostUi(
                    post = row.post,
                    comments = row.comments,
                    likedByUser = row.likes.any { it.characterId == null },
                    likeCharacterIds = row.likes.mapNotNull { it.characterId },
                    authorName = when {
                        !isCharacter -> "我"
                        char != null -> char.name
                        else -> "已注销角色"
                    },
                    authorImage = when {
                        !isCharacter -> profile.avatarPath
                        char != null -> resolveCharacterImage(char.id, char.image)
                        else -> ""
                    },
                    images = runCatching {
                        json.decodeFromString<List<String>>(row.post.imagesJson)
                    }.getOrDefault(emptyList()),
                )
            },
            userAvatar = profile.avatarPath,
            isCloud = provider == ChatProviderType.CLOUD,
            generating = runtime.gen,
            errorMessage = runtime.err,
            nowMs = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** combine 内部运行时态载体（避免 lambda 参数解构限制）。 */
    private data class MomentRuntime(
        val gen: Generating,
        val err: String?,
        val tick: Long,
    )

    private fun resolveCharacterImage(characterId: String, customImage: String): String {
        if (customImage.isNotBlank()) return customImage
        return container.assetRepository.getPicture(characterId)
    }

    /** 轮换刷新相对时间（进入页面后每分钟一次）。 */
    fun startTick() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                userTick.update { it + 1 }
            }
        }
    }

    fun clearError() {
        errorMessage.value = null
    }

    /**
     * 让角色发一条朋友圈（手动触发）。
     * @param imageCount 0 = 纯文字；1..3 带图（生图失败自动降级纯文字）。
     */
    fun postAsCharacter(characterId: String, imageCount: Int) {
        if (generating.value.posting) return
        if (!uiState.value.isCloud) {
            errorMessage.value = "朋友圈生成仅云端 AI 可用"
            return
        }
        generating.value = Generating(posting = true)
        viewModelScope.launch {
            try {
                container.momentGenerationCoordinator.generateAndPost(characterId, imageCount)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = e.message ?: "生成失败，请稍后再试"
            } finally {
                generating.value = Generating()
            }
        }
    }

    /** 用户自发朋友圈（文字 + 相册图片，不走 API）。 */
    fun postAsUser(content: String, imageUris: List<Uri>) {
        val text = content.trim()
        if (text.isEmpty() && imageUris.isEmpty()) return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                imageUris.take(AppConfig.Moment.MAX_IMAGES).mapNotNull { uri ->
                    CharacterImageStore.save(app, uri)
                }
            }
            momentRepository.addUserPost(text, saved)
        }
    }

    /** 用户评论：先落库，再让发帖角色必回（仅发帖者回复）。 */
    fun commentOnPost(post: MomentPostEntity, text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        if (generating.value.replyingPostId != null) return
        viewModelScope.launch {
            val rowId = momentRepository.addUserComment(post.id, content)
            val authorCharId = post.characterId
                ?.takeIf { post.authorType == MomentRepository.AUTHOR_CHARACTER }
                ?: return@launch // 用户帖无必回对象
            generating.value = Generating(replyingPostId = post.id)
            try {
                val reply = container.momentGenerationCoordinator.generateReply(
                    characterId = authorCharId,
                    postCaption = post.content,
                    commentContent = content,
                    isCharacterPost = post.authorType == MomentRepository.AUTHOR_CHARACTER,
                )
                if (reply.isNotBlank()) {
                    momentRepository.addCharacterComment(post.id, authorCharId, reply)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = "回复生成失败：${e.message ?: "请稍后再试"}"
            } finally {
                generating.value = Generating()
            }
        }
    }

    fun toggleLike(postId: Long) {
        viewModelScope.launch { momentRepository.toggleUserLike(postId) }
    }

    fun deletePost(postId: Long) {
        viewModelScope.launch { momentRepository.deletePost(postId) }
    }

    /** 长按封面换图：复制到内部存储后写 DataStore。 */
    fun setCoverFromUri(uri: Uri) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { CharacterImageStore.save(app, uri) }
            if (saved != null) {
                container.settingsRepository.setMomentCoverPath(saved)
            } else {
                errorMessage.value = "封面图片保存失败"
            }
        }
    }
}
