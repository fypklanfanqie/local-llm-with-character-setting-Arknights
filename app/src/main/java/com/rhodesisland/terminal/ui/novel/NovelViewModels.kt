package com.rhodesisland.terminal.ui.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.data.local.NovelChapterEntity
import com.rhodesisland.terminal.data.local.NovelLineEntity
import com.rhodesisland.terminal.data.local.NovelStoryEntity
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.ChatProviderType
import com.rhodesisland.terminal.data.repository.NovelRepository
import com.rhodesisland.terminal.llm.NovelPromptBuilder
import com.rhodesisland.terminal.llm.NovelScriptParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 小说模式 - 故事列表页 VM：故事流 + 新建故事。
 */
class NovelHomeViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val repo: NovelRepository = container.novelRepository
    val stories: StateFlow<List<NovelStoryEntity>> =
        repo.observeStories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val errorMessage = MutableStateFlow<String?>(null)

    fun createStory(
        title: String,
        background: String,
        memberIds: List<String>,
        npcs: List<NovelRepository.CustomNpc>,
        protagonistName: String,
        protagonistPersona: String,
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val id = repo.createStory(title, background, memberIds, npcs, protagonistName, protagonistPersona)
                onCreated(id)
            } catch (e: Exception) {
                errorMessage.value = e.message ?: "创建失败"
            }
        }
    }

    fun deleteStory(storyId: Long) {
        viewModelScope.launch { repo.deleteStory(storyId) }
    }

    fun clearError() {
        errorMessage.value = null
    }
}

/**
 * 小说模式 - 章节管理页 VM：章节流 + 增删/重排。
 */
class NovelStoryViewModel(
    private val container: AppContainer,
    private val storyId: Long,
) : ViewModel() {

    private val repo: NovelRepository = container.novelRepository

    data class UiState(
        val story: NovelStoryEntity? = null,
        val chapters: List<NovelChapterEntity> = emptyList(),
        val errorMessage: String? = null,
    )

    val uiState: StateFlow<UiState> = combine(
        repo.observeStory(storyId),
        repo.observeChapters(storyId),
    ) { story, chapters ->
        UiState(story = story, chapters = chapters)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun createChapter(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repo.createChapter(storyId)
            onCreated(id)
        }
    }

    fun moveChapter(chapter: NovelChapterEntity, delta: Int) {
        viewModelScope.launch {
            repo.moveChapter(chapter, uiState.value.chapters, delta)
        }
    }

    fun deleteChapter(chapterId: Long) {
        viewModelScope.launch { repo.deleteChapter(chapterId) }
    }
}

/**
 * 小说模式 - 对白编辑器 VM：行流 + 手动追加/编辑 + AI 续写（仅云端，流式）。
 */
class NovelEditorViewModel(
    private val container: AppContainer,
    private val chapterId: Long,
) : ViewModel() {

    private val repo: NovelRepository = container.novelRepository

    data class UiState(
        val chapter: NovelChapterEntity? = null,
        val lines: List<NovelLineEntity> = emptyList(),
        /** AI 续写进行中的累积预览（空 = 未在生成）。 */
        val streamingText: String = "",
        val isGenerating: Boolean = false,
        val isCloud: Boolean = true,
        val errorMessage: String? = null,
    )

    private val generating = MutableStateFlow("" to false) // text to isGenerating
    private val errorMessage = MutableStateFlow<String?>(null)
    private val isCloud = MutableStateFlow(true)
    private var generateJob: Job? = null

    val uiState: StateFlow<UiState> = combine(
        repo.observeChapterWithLines(chapterId),
        generating,
        errorMessage,
        isCloud,
    ) { row, (streamText, isGen), err, cloud ->
        UiState(
            chapter = row?.chapter,
            lines = row?.lines ?: emptyList(),
            streamingText = streamText,
            isGenerating = isGen,
            isCloud = cloud,
            errorMessage = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            isCloud.value =
                container.settingsRepository.getActiveProviderNow() == ChatProviderType.CLOUD
        }
    }

    /** 发言人信息：主控名 / 角色名集合（AI 续写提示词与脚本解析用）。 */
    private suspend fun speakersOf(story: NovelStoryEntity): Pair<String, Set<String>> {
        val memberIds = repo.decodeMemberIds(story)
        val all = container.characterRepository.characters.first()
        val names = memberIds.mapNotNull { id -> all.firstOrNull { it.id == id }?.name } +
            repo.decodeCustomNpcs(story).map { it.name }
        return story.protagonistName to names.toSet()
    }

    /** 手动追加一行（选中发言人 + 文本）。 */
    fun addLine(speakerType: String, speakerName: String, characterId: String?, text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                repo.appendLine(
                    NovelLineEntity(
                        chapterId = chapterId,
                        lineOrder = 0, // appendLine 自动接尾
                        speakerType = speakerType,
                        speakerName = speakerName,
                        characterId = characterId,
                        content = content,
                    ),
                )
                repo.getChapter(chapterId)?.let { repo.saveChapterSetting(it) } // touch updatedAt 顺带
            }.onFailure { errorMessage.value = it.message }
        }
    }

    fun updateLine(line: NovelLineEntity, newContent: String) {
        viewModelScope.launch { repo.updateLine(line.copy(content = newContent.trim())) }
    }

    fun deleteLine(lineId: Long) {
        viewModelScope.launch { repo.deleteLine(lineId) }
    }

    /** 保存本话设定（开场白为空正文时自动落第一行旁白，在 repo 内处理）。 */
    fun saveChapterSetting(chapter: NovelChapterEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repo.saveChapterSetting(chapter) }
                .onFailure { errorMessage.value = it.message }
            onDone()
        }
    }

    /**
     * AI 续写：门禁云端 → 流式生成（预览实时更新）→ 解析为脚本行逐条落库。
     */
    fun continuePlot() {
        if (generating.value.second) return
        if (!isCloud.value) {
            errorMessage.value = "AI 续写仅云端 AI 可用，请先在设置中切换到云端"
            return
        }
        generateJob = viewModelScope.launch {
            generating.value = "" to true
            try {
                val chapter = repo.getChapter(chapterId)
                    ?: throw IllegalStateException("章节不存在")
                val story = repo.getStory(chapter.storyId)
                    ?: throw IllegalStateException("故事不存在")
                val (protagonistName, speakerNames) = speakersOf(story)

                val memberIds = repo.decodeMemberIds(story)
                val allCharacters = container.characterRepository.characters.first()
                val characterSheets = memberIds.mapNotNull { id ->
                    allCharacters.firstOrNull { it.id == id }
                }.map { "${it.name}：${it.systemPrompt}" } +
                    repo.decodeCustomNpcs(story).map { "${it.name}：${it.persona}" }
                val nameToId = memberIds.mapNotNull { id ->
                    allCharacters.firstOrNull { it.id == id }?.let { it.name to id }
                }.toMap()

                val existingLines = repo.getLines(chapterId)
                val script = existingLines.map {
                    NovelScriptParser.ScriptLine(it.speakerType, it.speakerName, it.characterId, it.content)
                }
                val apiMessages = buildList {
                    add(
                        ChatMessage(
                            role = "system",
                            content = NovelPromptBuilder.buildSystem(
                                background = story.background,
                                characterSheets = characterSheets,
                                protagonistName = protagonistName,
                                protagonistPersona = story.protagonistPersona,
                            ),
                        ),
                    )
                    add(
                        ChatMessage(
                            role = "user",
                            content = NovelPromptBuilder.buildUser(
                                chapterTitle = chapter.title,
                                summary = chapter.summary,
                                opening = chapter.opening,
                                requirements = chapter.requirements,
                                script = script,
                            ),
                        ),
                    )
                }

                val provider = container.chatProviderManager.getActiveProvider()
                val raw = provider.chat(apiMessages) { accumulated ->
                    generating.value = accumulated to true
                }
                val parsed = NovelScriptParser.parse(raw, speakerNames, protagonistName)
                if (parsed.isEmpty()) throw IllegalStateException("AI 没有产出有效剧情，请重试")
                // 解析行落库：已知角色名回填 characterId
                val entities = parsed.map { line ->
                    NovelLineEntity(
                        chapterId = chapterId,
                        lineOrder = 0,
                        speakerType = line.speakerType,
                        speakerName = line.speakerName,
                        characterId = line.characterId
                            ?: nameToId[line.speakerName],
                        content = line.content,
                    )
                }
                repo.appendLines(chapterId, entities)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = e.message ?: "生成失败，请稍后再试"
            } finally {
                generating.value = "" to false
            }
        }
    }

    /** 停止续写（保留已生成部分不落库——与聊天停止语义一致：半截内容不静默入库）。 */
    fun stopGenerating() {
        generateJob?.cancel()
        generateJob = null
        generating.value = "" to false
    }

    fun clearError() {
        errorMessage.value = null
    }
}
