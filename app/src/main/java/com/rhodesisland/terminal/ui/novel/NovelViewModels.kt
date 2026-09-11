package com.rhodesisland.terminal.ui.novel

import com.rhodesisland.terminal.i18n.t
import com.rhodesisland.terminal.i18n.L10nRuntime

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
        castRoles: Map<String, String> = emptyMap(),
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val id = repo.createStory(title, background, memberIds, npcs, protagonistName, protagonistPersona, castRoles)
                onCreated(id)
            } catch (e: Exception) {
                errorMessage.value = e.message ?: L10nRuntime.t("创建失败")
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

    /** 阵容保存失败等仓库错误（与 UiState.errorMessage 分开，避免组合流再扩一档）。 */
    private val castError = MutableStateFlow<String?>(null)

    val castErrorMessage: StateFlow<String?> = castError

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

    /**
     * 保存角色阵容（写作过程中随时增删角色 / 改主角配角）。
     *
     * 保存后 story Flow 立刻推送新值：故事页与对白编辑器都观察同一张表，
     * 下一次 [NovelEditorViewModel.continuePlot] 从库里读到的是最新阵容，无需手动刷新。
     */
    fun updateCast(
        memberIds: List<String>,
        npcs: List<NovelRepository.CustomNpc>,
        castRoles: Map<String, String>,
    ) {
        viewModelScope.launch {
            runCatching { repo.updateStoryCast(storyId, memberIds, npcs, castRoles) }
                .onFailure { castError.value = it.message ?: L10nRuntime.t("保存失败") }
        }
    }

    fun clearError() {
        castError.value = null
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

    /**
     * 读取当前阵容（一次查询取全，含角色名与人设）。
     *
     * 为什么每次都重新查库：写作过程中随时可以增删角色/改主角配角，续写必须用最新阵容。
     * 这里读的是 Room 查询而非 UI 快照，所以保存后立刻续写即可生效（story 表已被更新）。
     */
    private suspend fun castOf(story: NovelStoryEntity): StoryCastSnapshot {
        val cast = repo.decodeCast(story)
        val all = container.characterRepository.characters.first()
        val members = cast.memberIds.mapNotNull { id ->
            all.firstOrNull { it.id == id }?.let {
                CastMember(id = id, name = it.name, persona = it.systemPrompt, role = cast.roles[id] ?: NovelRepository.ROLE_SUPPORTING)
            }
        }
        // NPC 的定位回填进实体：让调用方只认一个 role 来源（resolveCastRoles 已把内嵌旧值合并进来）
        val npcs = cast.npcs.map { it.copy(role = cast.roles[it.name.trim()] ?: NovelRepository.ROLE_SUPPORTING) }
        return StoryCastSnapshot(members = members, npcs = npcs)
    }

    private data class CastMember(val id: String, val name: String, val persona: String, val role: String)

    private data class StoryCastSnapshot(
        val members: List<CastMember>,
        val npcs: List<NovelRepository.CustomNpc>,
    ) {
        fun protagonists(): List<String> =
            members.filter { it.role == NovelRepository.ROLE_PROTAGONIST }.map { it.name } +
                npcs.filter { it.role == NovelRepository.ROLE_PROTAGONIST }.map { it.name }

        fun supporting(): List<String> =
            members.filter { it.role != NovelRepository.ROLE_PROTAGONIST }.map { it.name } +
                npcs.filter { it.role != NovelRepository.ROLE_PROTAGONIST }.map { it.name }

        /** 角色卡：主角在前的顺序有助于模型把注意力先落在主角身上。 */
        fun sheets(): List<String> {
            val ordered = members.sortedBy { if (it.role == NovelRepository.ROLE_PROTAGONIST) 0 else 1 }
            return ordered.map { "${it.name}：${it.persona}" } + npcs.map { "${it.name}：${it.persona}" }
        }

        fun nameToId(): Map<String, String> = members.associate { it.name to it.id }

        fun speakerNames(): Set<String> = (members.map { it.name } + npcs.map { it.name }).toSet()
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
     * AI 续写：直接调用已配置的云端 LLM（与聊天 Provider 切换解耦）→ 流式生成（预览实时更新）
     * → 解析为脚本行逐条落库。
     */
    fun continuePlot() {
        if (generating.value.second) return
        generateJob = viewModelScope.launch {
            if (!container.settingsRepository.isCloudApiReady()) {
                errorMessage.value = L10nRuntime.t("请先在设置中配置云端 AI API")
                return@launch
            }
            generating.value = "" to true
            try {
                val chapter = repo.getChapter(chapterId)
                    ?: throw IllegalStateException(L10nRuntime.t("章节不存在"))
                val story = repo.getStory(chapter.storyId)
                    ?: throw IllegalStateException(L10nRuntime.t("故事不存在"))
                // 阵容在续写开始时读取一次：本次生成期间用户在弹窗里的改动下一轮才生效
                val cast = castOf(story)
                val protagonistName = story.protagonistName
                val speakerNames = cast.speakerNames()
                val characterSheets = cast.sheets()
                val nameToId = cast.nameToId()

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
                                castProtagonists = cast.protagonists(),
                                castSupporting = cast.supporting(),
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

                val provider = container.cloudChatProvider
                val raw = provider.chat(apiMessages, onChunk = { accumulated ->
                    generating.value = accumulated to true
                })
                val parsed = NovelScriptParser.parse(raw, speakerNames, protagonistName)
                if (parsed.isEmpty()) throw IllegalStateException(L10nRuntime.t("AI 没有产出有效剧情，请重试"))
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
                errorMessage.value = e.message ?: L10nRuntime.t("生成失败，请稍后再试")
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

    /**
     * 写作过程中改阵容（本页入口）：保存后下一次 [continuePlot] 即用新阵容。
     *
     * 走章节反查 storyId——编辑器只拿得到章节 id，而阵容挂在故事上；
     * 这句查询和续写时读 story 走的是同一条路径，不存在"界面看到的"与"提示词用的"两份状态。
     */
    fun updateCast(
        memberIds: List<String>,
        npcs: List<NovelRepository.CustomNpc>,
        castRoles: Map<String, String>,
    ) {
        viewModelScope.launch {
            runCatching {
                val chapter = repo.getChapter(chapterId)
                    ?: throw IllegalStateException(L10nRuntime.t("章节不存在"))
                repo.updateStoryCast(chapter.storyId, memberIds, npcs, castRoles)
            }.onFailure { errorMessage.value = it.message ?: L10nRuntime.t("保存失败") }
        }
    }

    fun clearError() {
        errorMessage.value = null
    }
}
