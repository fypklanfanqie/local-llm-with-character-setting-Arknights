package com.rhodesisland.terminal.data.repository

import com.rhodesisland.terminal.config.AppConfig
import com.rhodesisland.terminal.data.local.NovelChapterEntity
import com.rhodesisland.terminal.data.local.NovelDao
import com.rhodesisland.terminal.data.local.NovelLineEntity
import com.rhodesisland.terminal.data.local.NovelStoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 小说模式仓库：故事/章节/脚本行的业务包装。
 * 行追加自动接 max(lineOrder)+1；章节新建自动接尾（maxChapterOrder+1）。
 */
class NovelRepository(
    private val dao: NovelDao,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ===== 故事 =====

    fun observeStories(): Flow<List<NovelStoryEntity>> = dao.observeStories()

    suspend fun getStory(storyId: Long): NovelStoryEntity? = dao.getStory(storyId)

    fun observeStory(storyId: Long): Flow<NovelStoryEntity?> = dao.observeStory(storyId)

    suspend fun createStory(
        title: String,
        background: String,
        memberIds: List<String>,
        customNpcs: List<CustomNpc>,
        protagonistName: String,
        protagonistPersona: String,
    ): Long {
        require(title.isNotBlank()) { "故事名不能为空" }
        val now = System.currentTimeMillis()
        return dao.insertStory(
            NovelStoryEntity(
                title = title.trim(),
                background = background.trim(),
                memberIdsJson = json.encodeToString(memberIds),
                customNpcsJson = json.encodeToString(customNpcs),
                protagonistName = protagonistName.trim(),
                protagonistPersona = protagonistPersona.trim(),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun updateStory(story: NovelStoryEntity) {
        dao.updateStory(story.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteStory(storyId: Long) = dao.deleteStoryCascade(storyId)

    fun decodeMemberIds(story: NovelStoryEntity): List<String> =
        runCatching { json.decodeFromString<List<String>>(story.memberIdsJson) }.getOrDefault(emptyList())

    fun decodeCustomNpcs(story: NovelStoryEntity): List<CustomNpc> =
        runCatching { json.decodeFromString<List<CustomNpc>>(story.customNpcsJson) }.getOrDefault(emptyList())

    // ===== 章节 =====

    fun observeChapters(storyId: Long): Flow<List<NovelChapterEntity>> = dao.observeChapters(storyId)

    fun observeChapterWithLines(chapterId: Long): Flow<com.rhodesisland.terminal.data.local.NovelChapterWithLines?> =
        dao.observeChapterWithLines(chapterId)

    suspend fun getChapter(chapterId: Long): NovelChapterEntity? = dao.getChapter(chapterId)

    suspend fun createChapter(storyId: Long, title: String = ""): Long {
        val now = System.currentTimeMillis()
        val nextOrder = (dao.maxChapterOrder(storyId) ?: -1) + 1
        return dao.insertChapter(
            NovelChapterEntity(
                storyId = storyId,
                orderIndex = nextOrder,
                title = title.ifBlank { "第 ${nextOrder + 1} 话" },
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** 保存本话设定；若本话无正文且开场白非空，开场白自动落为第一行旁白。 */
    suspend fun saveChapterSetting(chapter: NovelChapterEntity) {
        dao.updateChapter(chapter.copy(updatedAt = System.currentTimeMillis()))
        val existing = dao.getLines(chapter.id)
        if (existing.isEmpty() && chapter.opening.isNotBlank()) {
            appendLine(
                NovelLineEntity(
                    chapterId = chapter.id,
                    lineOrder = 0,
                    speakerType = com.rhodesisland.terminal.llm.NovelScriptParser.TYPE_NARRATION,
                    speakerName = "旁白",
                    content = chapter.opening.trim(),
                ),
            )
        }
    }

    /** 上移/下移：与相邻章节换 orderIndex。 */
    suspend fun moveChapter(chapter: NovelChapterEntity, chapters: List<NovelChapterEntity>, delta: Int) {
        val index = chapters.indexOfFirst { it.id == chapter.id }
        val targetIndex = index + delta
        if (index < 0 || targetIndex !in chapters.indices) return
        val target = chapters[targetIndex]
        dao.swapChapterOrder(chapter.id, target.id, chapter.orderIndex, target.orderIndex)
    }

    suspend fun deleteChapter(chapterId: Long) {
        dao.deleteChapterCascade(chapterId)
        // 删除后可重排剩余章节 orderIndex 为 0..n（保持列表紧凑；读侧本就按 orderIndex 排序，可选优化）
    }

    // ===== 行 =====

    suspend fun getLines(chapterId: Long): List<NovelLineEntity> = dao.getLines(chapterId)

    /** 追加一行（自动接尾）。超出每话行数上限时拒绝。 */
    suspend fun appendLine(line: NovelLineEntity): Long {
        val count = dao.getLines(line.chapterId).size
        if (count >= AppConfig.Novel.MAX_LINES_PER_CHAPTER) {
            throw IllegalStateException("本话已达 ${AppConfig.Novel.MAX_LINES_PER_CHAPTER} 行上限")
        }
        val nextOrder = (dao.maxLineOrder(line.chapterId) ?: -1) + 1
        return dao.insertLine(
            line.copy(
                lineOrder = nextOrder,
                content = line.content.take(AppConfig.Novel.LINE_MAX_CHARS),
            ),
        )
    }

    suspend fun updateLine(line: NovelLineEntity) {
        dao.updateLine(line.copy(content = line.content.take(AppConfig.Novel.LINE_MAX_CHARS)))
    }

    suspend fun deleteLine(lineId: Long) = dao.deleteLine(lineId)

    /** 批量追加 AI 续写产物（保持相对顺序）。 */
    suspend fun appendLines(chapterId: Long, lines: List<NovelLineEntity>) {
        lines.forEach { appendLine(it.copy(chapterId = chapterId)) }
    }

    @Serializable
    data class CustomNpc(val name: String, val persona: String = "")
}
